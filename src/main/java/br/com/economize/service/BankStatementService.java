package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.event.StatementImportedEvent;
import br.com.economize.service.recurrence.MerchantKeyExtractor;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import br.com.economize.service.statement.category.DescriptionNormalizer;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import br.com.economize.service.statement.parser.StatementParserFactory;
import br.com.economize.service.statement.parser.StatementParserStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementService {

    // sugestão de IA nunca supera regra ou keyword — entra com a menor confiança
    private static final BigDecimal CONF_AI = new BigDecimal("0.50");

    private static final int DESCRIPTION_MAX = 255;

    /**
     * Meia-janela (em dias) para parear retroativamente as duas pernas de um
     * pagamento de fatura. O débito sai da conta corrente no vencimento e o
     * crédito é registrado no cartão no mesmo dia ou logo depois; cinco dias
     * cobrem fim de semana, feriado e a compensação mais lenta que se vê na
     * prática. O teto importa mais do que o piso: a fatura seguinte está a ~30
     * dias, então esta janela não alcança o pagamento de outro mês nem quando os
     * dois têm exatamente o mesmo valor.
     */
    private static final int INTERNAL_MATCH_WINDOW_DAYS = 5;

    /**
     * Ids por UPDATE no carimbo retroativo de origem. 500 é conservador de
     * propósito: fica ordens de grandeza abaixo do teto de 65535 parâmetros do
     * protocolo do Postgres, mantém o plano da consulta reaproveitável e ainda
     * assim resolve um backfill de dois anos (~5 mil linhas) em uma dezena de
     * idas ao banco.
     */
    private static final int BACKFILL_CHUNK = 500;

    private final BankTransactionRepository bankTransactionRepository;
    private final StatementUploadRepository statementUploadRepository;
    private final UserRepository userRepository;
    private final StatementParserFactory parserFactory;
    private final CategorizationEngine categorizationEngine;
    private final CategoryRepository categoryRepository;
    private final StatementImportWriter importWriter;
    // Resolve a origem opcional do upload — e recusa conta de outra pessoa
    private final ConnectorAccountService accountService;
    private final DomainEventPublisher eventPublisher;
    // ObjectProvider e não injeção direta: desde o EC-107 o bean existe sempre
    // (a decisão de usar IA passou a ser POR USUÁRIO, não mais por ambiente),
    // mas a indireção segura o acoplamento fraco e mantém de pé os testes que
    // montam este serviço sem o módulo de IA
    private final ObjectProvider<AiCategorySuggester> aiSuggester;

    public Mono<ImportResult> processFile(String email, FilePart filePart) {
        return processFile(email, filePart, null);
    }

    /**
     * Upload de arquivo, opcionalmente carimbando a ORIGEM de todas as linhas.
     *
     * <p>Sem {@code accountId} o comportamento é o de sempre (origem nula), que
     * e o que o APK publicado espera. Com ele, cada lancamento do arquivo passa
     * a saber de qual conta veio — e o Extrato consegue separar duas contas
     * correntes e um cartao, em vez de amontoar tudo numa lista so.
     *
     * <p>A conta e resolvida ANTES de ler o arquivo: id de outra pessoa responde
     * 404 sem que nada seja importado, e nao depois de meio extrato ja estar
     * gravado sem dono.
     */
    public Mono<ImportResult> processFile(String email, FilePart filePart, UUID accountId) {
        return Mono.fromCallable(() -> userRepository.findByEmail(email)
                        .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> DataBufferUtils.join(filePart.content())
                        .map(buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            return bytes;
                        })
                        .flatMap(bytes -> processBytes(user, filePart.filename(), bytes, accountId)));
    }

    private Mono<ImportResult> processBytes(User user, String fileName, byte[] bytes, UUID accountId) {
        return Mono.fromCallable(() -> {
            StatementFormat format = StatementFormat.fromFilename(fileName);
            // dono como FILTRO: conta alheia responde 404 igual a inexistente
            UUID origem = accountId == null
                    ? null
                    : accountService.requireOwned(accountId, user.getId()).getId();
            String hash = sha256(bytes);
            return statementUploadRepository.findByUserIdAndFileHash(user.getId(), hash)
                    .map(existing -> duplicatedResult(user, existing, format))
                    .orElseGet(() -> importFresh(user, fileName, bytes, format, hash, origem));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // reimport do mesmo arquivo devolve o estado real da importação original,
    // inclusive quantas transações dela ainda aguardam revisão
    private ImportResult duplicatedResult(User user, StatementUpload existing, StatementFormat format) {
        List<BankTransaction> txs = bankTransactionRepository
                .findAllByUserIdAndUploadIdOrderByDateDesc(user.getId(), existing.getId());
        int suggested = 0;
        int uncategorized = 0;
        for (BankTransaction tx : txs) {
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.SUGGESTED) suggested++;
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.UNCATEGORIZED) uncategorized++;
        }
        return new ImportResult(existing.getId(), existing.getTransactionsImported(),
                suggested, uncategorized, 0, true, format.name());
    }

    private ImportResult importFresh(User user, String fileName, byte[] bytes, StatementFormat format,
                                     String hash, UUID accountId) {
        StatementParserStrategy parser = parserFactory.resolve(format);
        List<ParsedTransaction> parsed = parser.parse(new ByteArrayInputStream(bytes));
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma transação encontrada no arquivo");
        }
        if (accountId != null) {
            // a origem viaja com a linha ate a gravacao, do mesmo jeito que no
            // sync do conector (EC-113): depois dela ninguem mais saberia dizer
            // de qual conta a transacao veio. A linha e imutavel, entao a
            // origem entra numa COPIA — ver o toBuilder em ParsedTransaction
            parsed = parsed.stream()
                    .map(tx -> tx.toBuilder().accountId(accountId).build())
                    .toList();
        }
        return persist(user, parsed, fileName, format, hash);
    }

    /**
     * Entrada dos conectores (Meu Pluggy): mesmas garantias do upload — motor de
     * categorização, dedup por id externo e reconciliação entre fontes. O hash
     * sintético único registra cada sincronização no histórico de uploads.
     */
    public ImportResult importFromConnector(User user, String sourceName, StatementFormat format,
                                            List<ParsedTransaction> parsed) {
        if (parsed.isEmpty()) {
            return new ImportResult(null, 0, 0, 0, 0, false, format.name());
        }
        String syntheticHash = sha256((sourceName + "|" + UUID.randomUUID())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return persist(user, parsed, sourceName, format, syntheticHash);
    }

    private ImportResult persist(User user, List<ParsedTransaction> parsed, String fileName,
                                 StatementFormat format, String hash) {
        List<BankTransaction> existing = existingInWindow(user, parsed);
        // os ids já conhecidos saem da mesma leitura da janela: perguntar ao banco
        // uma vez por transação custava ~1 minuto num backfill de dois anos
        // indexado pelo id externo (unique por usuário): além de responder "já
        // conheço?", é por ele que a origem alcança a linha que a dedupe pulou
        Map<String, BankTransaction> knownById = new java.util.HashMap<>();
        for (BankTransaction tx : existing) {
            if (tx.getTransactionId() != null) knownById.put(tx.getTransactionId(), tx);
        }
        ReconciliationLedger ledger = new ReconciliationLedger(existing);
        // linhas já gravadas que ganham a origem desta importação (EC-113)
        Map<UUID, UUID> accountBackfill = new java.util.LinkedHashMap<>();

        // 1º passe: o que já existe pelo id externo sai da frente, mas consome o
        // crédito da PRÓPRIA linha no ledger — senão uma transação nova de mesmo
        // dia e valor herdaria esse crédito e seria descartada no lugar dela
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        // pernas internas que a dedupe descartou: a linha equivalente já está no
        // banco, possivelmente gravada quando o cartão ainda nem estava
        // conectado — e sem esta lista o "pular duplicata" viraria "nunca
        // corrigir a marca" (ver reconcileInternalLegs)
        List<Candidate> skippedInternal = new ArrayList<>();
        for (ParsedTransaction tx : parsed) {
            String normalized = DescriptionNormalizer.normalize(tx.getDescription());
            // id repetido dentro do próprio arquivo violaria unique_transaction_per_user
            if (tx.getExternalId() != null && !seenInFile.add(tx.getExternalId())) continue;
            BankTransaction known = knownById.get(tx.getExternalId());
            if (known != null) {
                ledger.consume(tx.getDate(), tx.getAmount(), normalized);
                if (tx.isInternalTransfer()) skippedInternal.add(new Candidate(tx, normalized));
                // A origem chega junto com a linha nova, mas a linha nova é
                // descartada — a que ficou é a mesma transação e precisa herdá-la.
                // Só pelo ID EXTERNO, que é prova de identidade: os níveis por
                // dia+valor da reconciliação são pareamento plausível, e atribuir
                // por eles poderia carimbar uma compra de cartão numa linha que
                // veio do extrato da conta corrente.
                if (tx.getAccountId() != null && known.getAccountId() == null && known.getId() != null) {
                    accountBackfill.put(known.getId(), tx.getAccountId());
                }
                continue;
            }
            candidates.add(new Candidate(tx, normalized));
        }

        // 2º passe: casamento exato (dia+valor+descrição normalizada) antes de
        // qualquer aproximação. Fazer os dois níveis no mesmo passe deixava a
        // primeira linha do arquivo consumir o crédito de uma transação diferente
        // e descartar um lançamento legítimo, enquanto a duplicata real entrava.
        List<Candidate> unmatched = new ArrayList<>();
        int reconciled = 0;
        for (Candidate c : candidates) {
            if (ledger.consumeExact(c.tx().getDate(), c.tx().getAmount(), c.normalized())) {
                reconciled++;
                if (c.tx().isInternalTransfer()) skippedInternal.add(c);
            } else {
                unmatched.add(c);
            }
        }

        // 3º passe: o mesmo lançamento descrito de outro jeito por outro formato
        List<Candidate> fresh = new ArrayList<>();
        for (Candidate c : unmatched) {
            if (ledger.consumeAny(c.tx().getDate(), c.tx().getAmount())) {
                reconciled++;
                if (c.tx().isInternalTransfer()) skippedInternal.add(c);
            } else {
                fresh.add(c);
            }
        }

        // categorizar só o que de fato entra: rodar antes da reconciliação inflava
        // o contador de acertos das regras com transações descartadas
        CategorizationEngine.Context ctx = categorizationEngine.contextFor(user.getId());
        List<BankTransaction> toSave = new ArrayList<>();
        for (Candidate candidate : fresh) {
            ParsedTransaction tx = candidate.tx();
            CategorizationEngine.Result result = categorizationEngine.categorize(
                    ctx, tx.getDescription(), tx.getType(), tx.isInternalTransfer());
            toSave.add(BankTransaction.builder()
                    .user(user)
                    .transactionId(tx.getExternalId())
                    .type(tx.getType())
                    .amount(tx.getAmount())
                    .description(truncate(tx.getDescription()))
                    .date(tx.getDate())
                    .categoryId(result.resolved() ? result.category().getId() : null)
                    .category(result.resolved() ? TransactionReviewService.legacyKey(result.category()) : null)
                    .reviewStatus(result.resolved()
                            ? BankTransaction.ReviewStatus.SUGGESTED
                            : BankTransaction.ReviewStatus.UNCATEGORIZED)
                    .categorizedBy(result.by())
                    .confidence(result.confidence())
                    .normalizedDescription(result.normalizedDescription())
                    // quem importou já sabia se a linha é perna de movimentação
                    // entre contas do titular (EC-106); daqui para frente
                    // ninguém mais teria como descobrir
                    .internalTransfer(tx.isInternalTransfer())
                    // ... e o mesmo vale para a ORIGEM (EC-113): só o conector
                    // sabe de qual conta puxou. Nulo no upload manual de arquivo,
                    // que não tem conta de provedor.
                    .accountId(tx.getAccountId())
                    .build());
        }

        applyAiSuggestions(user, toSave);

        int suggested = 0;
        int uncategorized = 0;
        for (BankTransaction tx : toSave) {
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.SUGGESTED) suggested++;
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.UNCATEGORIZED) uncategorized++;
        }

        // upload + transações + hits das regras num único commit (EC-075):
        // qualquer falha desfaz o conjunto e o arquivo continua reenviável
        StatementUpload upload = importWriter.write(
                StatementUpload.builder()
                        .user(user)
                        .fileHash(hash)
                        .fileName(fileName)
                        .format(format.name())
                        .transactionsImported(toSave.size())
                        .build(),
                toSave,
                ctx.getDirtyRules());

        reconcileInternalLegs(user, parsed, existing, skippedInternal);
        backfillAccountOrigin(user, accountBackfill);

        eventPublisher.publish(new StatementImportedEvent(user.getId(), format, toSave.size(), upload.getId()));
        log.info("Importadas {} novas transações ({}): {} sugeridas, {} sem categoria, {} reconciliadas, user={}",
                toSave.size(), format, suggested, uncategorized, reconciled, user.getEmail());
        return new ImportResult(upload.getId(), toSave.size(), suggested, uncategorized, reconciled, false, format.name());
    }

    /**
     * Carimba a origem (EC-113) nas linhas que a dedupe descartou.
     *
     * <p>A dimensão de conta nasceu depois do histórico, então o extrato já
     * sincronizado está todo sem origem — e é justamente ele que a dedupe por id
     * externo descarta em toda sincronização seguinte. Sem esta passada,
     * "pular duplicata" significaria "o que já foi importado nunca terá origem",
     * a fatura do usuário abriria vazia e só voltaria a encher meses depois. É o
     * mesmo raciocínio da promoção de perna interna, com a diferença de que aqui
     * o pareamento exige o id externo: identidade provada, não plausível.
     *
     * <p>Nunca sobrescreve origem já existente (a condição vive na própria
     * consulta) e vai EM LOTES: a primeira sync depois do deploy é o pior caso
     * por construção — todo o extrato já importado do usuário é duplicata pelo id
     * externo e cai aqui de uma vez. Um único UPDATE ... IN (...) com milhares de
     * ids vira uma consulta com milhares de parâmetros, que o Postgres recusa
     * acima de 65535 e que, bem antes disso, já estoura o cache de plano e o
     * limite de tamanho de statement do driver.
     */
    private void backfillAccountOrigin(User user, Map<UUID, UUID> accountBackfill) {
        if (accountBackfill.isEmpty()) return;
        Map<UUID, List<UUID>> byAccount = new java.util.LinkedHashMap<>();
        accountBackfill.forEach((txId, accountId) ->
                byAccount.computeIfAbsent(accountId, k -> new ArrayList<>()).add(txId));
        int marked = 0;
        for (Map.Entry<UUID, List<UUID>> entry : byAccount.entrySet()) {
            List<UUID> ids = entry.getValue();
            for (int from = 0; from < ids.size(); from += BACKFILL_CHUNK) {
                List<UUID> chunk = ids.subList(from, Math.min(from + BACKFILL_CHUNK, ids.size()));
                marked += bankTransactionRepository.assignAccount(user.getId(), entry.getKey(), chunk);
            }
        }
        if (marked > 0) {
            log.info("Origem carimbada em {} lançamento(s) já existentes, user={}", marked, user.getEmail());
        }
    }

    /**
     * Estende a marca de perna interna (EC-106) para linhas que JÁ ESTAVAM no
     * banco.
     *
     * <p>
     * A marca é decidida na importação, com um contexto que só existe ali (o
     * tipo da conta de origem no agregador). O problema é que a ordem em que o
     * usuário conecta as coisas quase nunca é a ideal: o extrato OFX da conta
     * corrente entra primeiro e o pagamento da fatura é gravado como despesa
     * comum; o cartão só é conectado semanas depois. Quando a contrapartida
     * enfim aparece, aquele débito antigo continuaria contado como despesa para
     * sempre — e a mesma compra ficaria no relatório duas vezes. Dois caminhos
     * corrigem isso, e nenhum deles inventa marca nova:
     *
     * <ol>
     * <li><b>Promoção do que a dedupe pulou.</b> A linha veio nesta sync já
     * marcada, mas foi descartada como duplicata — a que está no banco é a mesma
     * e precisa herdar a marca. Sem isto, "pular duplicata" significaria "nunca
     * mais corrigir".</li>
     * <li><b>Pareamento retroativo.</b> Para cada crédito de cartão desta sync
     * que NÃO encontrou o débito correspondente no próprio lote, procura-se no
     * banco um débito que ainda esteja sem marca.</li>
     * </ol>
     *
     * <p>
     * O pareamento é deliberadamente estreito, porque marcar errado APAGA uma
     * despesa real: valor exato com sinal, âncora "fatura" na descrição (o mesmo
     * sinal curado pelo {@link MerchantKeyExtractor}) e no máximo UM débito por
     * crédito. É pareamento, não transmissão em massa: dez débitos de "fatura"
     * de R$ 500 continuam despesa se só houver um crédito de R$ 500.
     */
    private void reconcileInternalLegs(User user, List<ParsedTransaction> parsed,
                                       List<BankTransaction> existing, List<Candidate> skippedInternal) {
        // upload de arquivo nunca traz perna interna (só conector sabe disso):
        // para ele o método inteiro custa uma varredura da lista e mais nada
        if (skippedInternal.isEmpty() && parsed.stream().noneMatch(ParsedTransaction::isInternalTransfer)) {
            return;
        }

        // um id só pode ser pareado uma vez, venha de qual caminho vier
        Set<UUID> toMark = new LinkedHashSet<>();
        promoteSkippedInternalLegs(existing, skippedInternal, toMark);
        pairLeftoverCardCredits(user, parsed, toMark);
        if (toMark.isEmpty()) return;

        bankTransactionRepository.markAsInternalTransfer(user.getId(), toMark);
        // as entidades já lidas continuam vivas neste passe: mantê-las coerentes
        // evita que uma segunda passada as considere candidatas de novo
        existing.stream().filter(tx -> toMark.contains(tx.getId()))
                .forEach(tx -> tx.setInternalTransfer(true));
        log.info("Movimentação entre contas do titular: {} lançamento(s) já existentes remarcados, user={}",
                toMark.size(), user.getEmail());
    }

    /** Caminho (1): a duplicata pulada empresta a marca à linha que ficou. */
    private void promoteSkippedInternalLegs(List<BankTransaction> existing, List<Candidate> skippedInternal,
                                            Set<UUID> toMark) {
        for (Candidate candidate : skippedInternal) {
            existing.stream()
                    .filter(tx -> tx.getId() != null && !tx.isInternalTransfer())
                    .filter(tx -> !toMark.contains(tx.getId()))
                    .filter(tx -> utcDay(tx.getDate()).equals(utcDay(candidate.tx().getDate())))
                    .filter(tx -> tx.getAmount().compareTo(candidate.tx().getAmount()) == 0)
                    // o mesmo id externo é prova; a descrição idêntica é o
                    // segundo melhor sinal; dia+valor é o último recurso
                    .min(Comparator.comparingInt(tx -> promotionRank(tx, candidate)))
                    .ifPresent(tx -> toMark.add(tx.getId()));
        }
    }

    private int promotionRank(BankTransaction existing, Candidate candidate) {
        if (candidate.tx().getExternalId() != null
                && candidate.tx().getExternalId().equals(existing.getTransactionId())) {
            return 0;
        }
        return candidate.normalized().equals(existing.getNormalizedDescription()) ? 1 : 2;
    }

    /** Caminho (2): crédito de cartão sem par no lote procura débito no banco. */
    private void pairLeftoverCardCredits(User user, List<ParsedTransaction> parsed, Set<UUID> toMark) {
        List<ParsedTransaction> cardCredits = new ArrayList<>();
        Map<String, Integer> pairedInBatch = new java.util.HashMap<>();
        for (ParsedTransaction tx : parsed) {
            if (!tx.isInternalTransfer()) continue;
            // dentro do lote, o positivo é a perna do cartão e o negativo é a da
            // conta corrente — ela já foi pareada na importação
            if (tx.getAmount().signum() > 0) {
                cardCredits.add(tx);
            } else if (tx.getAmount().signum() < 0) {
                pairedInBatch.merge(amountKey(tx.getAmount()), 1, Integer::sum);
            }
        }

        for (ParsedTransaction credit : cardCredits) {
            String key = amountKey(credit.getAmount());
            Integer paired = pairedInBatch.get(key);
            if (paired != null && paired > 0) {
                pairedInBatch.put(key, paired - 1);
                continue;
            }
            findRetroactiveCounterpart(user, credit, toMark)
                    .ifPresent(tx -> toMark.add(tx.getId()));
        }
    }

    private java.util.Optional<BankTransaction> findRetroactiveCounterpart(User user, ParsedTransaction credit,
                                                                          Set<UUID> toMark) {
        OffsetDateTime start = credit.getDate().minusDays(INTERNAL_MATCH_WINDOW_DAYS);
        OffsetDateTime end = credit.getDate().plusDays(INTERNAL_MATCH_WINDOW_DAYS);
        return bankTransactionRepository
                .findUnmarkedByAmountInWindow(user.getId(), credit.getAmount().negate(), start, end)
                .stream()
                .filter(tx -> tx.getId() != null && !toMark.contains(tx.getId()))
                .filter(tx -> "fatura".equals(MerchantKeyExtractor.extract(tx.getDescription()).anchor()))
                // o mais próximo do crédito é o par mais plausível
                .min(Comparator.comparingLong(tx -> Math.abs(
                        java.time.Duration.between(credit.getDate(), tx.getDate()).toMillis())));
    }

    private static String amountKey(BigDecimal amount) {
        return amount.abs().stripTrailingZeros().toPlainString();
    }

    /**
     * Tudo que o usuário já tem dentro da janela de datas do arquivo — base do
     * ledger de reconciliação e também dos ids externos já conhecidos.
     */
    private List<BankTransaction> existingInWindow(User user, List<ParsedTransaction> parsed) {
        OffsetDateTime min = null;
        OffsetDateTime max = null;
        for (ParsedTransaction tx : parsed) {
            if (min == null || tx.getDate().isBefore(min)) min = tx.getDate();
            if (max == null || tx.getDate().isAfter(max)) max = tx.getDate();
        }
        // a janela é recortada no MESMO fuso da chave do ledger (UTC); derivar o
        // dia no offset da data parseada deslocava a janela quando a data não
        // vinha em UTC e nada reconciliava
        return bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(),
                        utcDay(min).atStartOfDay().atOffset(java.time.ZoneOffset.UTC),
                        utcDay(max).plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }

    /** Transação do arquivo com a descrição já normalizada uma única vez. */
    private record Candidate(ParsedTransaction tx, String normalized) {
    }

    /**
     * Ledger de reconciliação entre fontes: conta quantas transações já existem
     * para cada trinca (dia, valor, descrição normalizada) dentro da janela do
     * arquivo — e um segundo nível só (dia, valor) para o mesmo lançamento
     * descrito de forma diferente por formatos distintos (o OFX do Inter tem
     * "Cp :NNN-Fulano" onde o CSV tem "Fulano"). O consumo é por contagem:
     * duas compras idênticas no mesmo dia continuam sendo duas — só o excedente
     * do arquivo é importado. Os dois níveis são consumidos em passes separados
     * (ver {@code persist}): misturados, uma descrição diferente consumia o
     * crédito reservado ao casamento exato de outra transação.
     */
    static class ReconciliationLedger {
        private final Map<String, Integer> exact = new java.util.HashMap<>();
        private final Map<String, Integer> byDayAmount = new java.util.HashMap<>();

        ReconciliationLedger(List<BankTransaction> existing) {
            for (BankTransaction tx : existing) {
                String base = dayAmountKey(tx.getDate(), tx.getAmount());
                byDayAmount.merge(base, 1, Integer::sum);
                if (tx.getNormalizedDescription() != null && !tx.getNormalizedDescription().isBlank()) {
                    exact.merge(base + "|" + tx.getNormalizedDescription(), 1, Integer::sum);
                }
            }
        }

        /** Casamento forte: mesmo dia, mesmo valor e mesma descrição normalizada. */
        boolean consumeExact(OffsetDateTime date, java.math.BigDecimal amount, String normalizedDescription) {
            String base = dayAmountKey(date, amount);
            String exactKey = base + "|" + normalizedDescription;
            Integer exactCount = exact.get(exactKey);
            if (exactCount == null || exactCount <= 0) return false;
            exact.put(exactKey, exactCount - 1);
            decrementBase(base);
            return true;
        }

        /** Rede: o mesmo lançamento que dois formatos descrevem de jeitos diferentes. */
        boolean consumeAny(OffsetDateTime date, java.math.BigDecimal amount) {
            String base = dayAmountKey(date, amount);
            Integer baseCount = byDayAmount.get(base);
            if (baseCount == null || baseCount <= 0) return false;
            byDayAmount.put(base, baseCount - 1);
            return true;
        }

        void consume(OffsetDateTime date, java.math.BigDecimal amount, String normalizedDescription) {
            if (!consumeExact(date, amount, normalizedDescription)) consumeAny(date, amount);
        }

        private void decrementBase(String base) {
            Integer baseCount = byDayAmount.get(base);
            if (baseCount != null && baseCount > 0) byDayAmount.put(base, baseCount - 1);
        }

        private static String dayAmountKey(OffsetDateTime date, java.math.BigDecimal amount) {
            return utcDay(date) + "|" + amount.stripTrailingZeros().toPlainString();
        }
    }

    private static java.time.LocalDate utcDay(OffsetDateTime date) {
        return date.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /**
     * `bank_transactions.description` é VARCHAR(255) e os parsers passaram a
     * concatenar campos (MEMO+NAME no OFX, Histórico+Descrição no CSV): sem o
     * corte, uma linha comprida derruba o lote inteiro na gravação.
     */
    private static String truncate(String description) {
        if (description == null || description.length() <= DESCRIPTION_MAX) return description;
        return description.substring(0, DESCRIPTION_MAX);
    }

    private void applyAiSuggestions(User user, List<BankTransaction> toSave) {
        AiCategorySuggester suggester = aiSuggester.getIfAvailable();
        if (suggester == null) return;
        List<BankTransaction> unresolved = toSave.stream()
                .filter(tx -> tx.getCategoryId() == null)
                .filter(tx -> tx.getNormalizedDescription() != null && !tx.getNormalizedDescription().isBlank())
                .toList();
        if (unresolved.isEmpty()) return;

        // EC-107: perguntar se existe IA aplicável a esta conta vem ANTES de
        // carregar o catálogo, e a ordem é o ponto. Enquanto o suggester era
        // @ConditionalOnProperty, quem estava com a flag desligada não pagava
        // nada aqui; agora o bean existe sempre, e sem esta guarda toda
        // importação — inclusive a de quem nunca ligou IA — pagaria a consulta
        // do catálogo para descobrir que não havia nada a sugerir.
        if (!suggester.appliesTo(user)) return;

        List<Category> catalog = categoryRepository.findVisibleTo(user.getId()).stream()
                .filter(c -> !c.isArchived())
                .toList();
        List<String> keys = unresolved.stream()
                .map(BankTransaction::getNormalizedDescription)
                .distinct()
                .toList();
        // EC-107: o dono do extrato vai junto — é ele quem decide se a chamada
        // sai na chave própria dele ou na do servidor (ou se não sai)
        Map<String, String> suggestions = suggester.suggest(user, keys, catalog);
        if (suggestions.isEmpty()) return;

        Map<String, Category> bySlug = catalog.stream()
                .collect(Collectors.toMap(Category::getSlug, Function.identity(), (a, b) -> a));
        for (BankTransaction tx : unresolved) {
            Category category = bySlug.get(suggestions.get(tx.getNormalizedDescription()));
            if (category == null) continue;
            tx.setCategoryId(category.getId());
            tx.setCategory(TransactionReviewService.legacyKey(category));
            tx.setReviewStatus(BankTransaction.ReviewStatus.SUGGESTED);
            tx.setCategorizedBy(BankTransaction.CategorizedBy.AI);
            tx.setConfidence(CONF_AI);
        }
    }

    public List<BankTransaction> listTransactions(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        return bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular hash", e);
        }
    }

    public record ImportResult(UUID uploadId, int transactionsImported, int suggested,
                               int uncategorized, int reconciled, boolean duplicated, String format) {
    }
}
