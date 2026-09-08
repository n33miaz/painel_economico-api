package br.com.economize.service.recurrence;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.RecurringSeriesLink;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.RecurringSeriesLinkRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Detecta séries recorrentes (EC-095) varrendo as transações do usuário:
 * extrai a entidade de cada descrição ({@link MerchantKeyExtractor}), agrupa
 * por (entidade, fluxo), mede cadência/âncora/tolerância/tipo de valor e
 * materializa {@link RecurringSeries} + vínculos.
 *
 * <p>Idempotente e re-executável por construção: a série é única por
 * (usuário, merchant_key, fluxo), o vínculo é único por transação, e os
 * agregados são recalculados do zero a cada varredura — rodar duas vezes sobre
 * os mesmos dados não muda nada. Série sem ocorrência há 2+ ciclos vira
 * {@code active=false}, nunca é apagada (o histórico alimenta o EC-096); série
 * cuja chave o motor não produz mais cede os vínculos à que a substitui e
 * também é só desativada (ver {@link #releaseOrphanLinks}).
 *
 * <p>Transação programática ({@link TransactionTemplate}) em vez de
 * {@code @Transactional}: o listener pós-importação e o {@code POST /detect}
 * manual podem varrer o MESMO usuário ao mesmo tempo, e a corrida estoura como
 * violação de unique — que precisa ser capturada aqui e re-executada numa
 * transação NOVA. Com a anotação, o commit acontece no proxy, depois do método,
 * fora do alcance de qualquer catch; e chamar um método {@code @Transactional}
 * da própria classe não passa pelo proxy. O trecho JPA continua bloqueante de
 * ponta a ponta, rodando em {@code boundedElastic} como nos demais services.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecurrenceDetectionService {

    // 2 ocorrências dão um único intervalo — coincidência, não padrão
    static final int MIN_OCCURRENCES = 3;
    // intervalos mais recentes que decidem cadência, âncora e tolerância (ver computeStats)
    static final int RECENT_INTERVALS = 12;

    /**
     * Janela do VALOR, mais curta que a da cadência — e são coisas diferentes.
     *
     * <p>Cadência é um hábito e leva um ano para se estabelecer; valor é um número
     * que anda. Medido na fatura do cartão do dono, 34 pagamentos entre 12/2024 e
     * 09/2026: ela subiu de R$ 189 para R$ 2.311 sem um degrau, e a média das
     * últimas 13 previa <b>R$ 1.480</b> — mil reais abaixo do que ele paga hoje.
     * Seis ocorrências cobrem meio ano de cobrança mensal: perto o bastante para
     * seguir a tendência, largo o bastante para uma fatura paga pela metade num
     * mês não virar a previsão.
     */
    static final int RECENT_AMOUNTS = 6;
    private static final int STALE_CYCLES = 2;
    private static final int MAX_DAY_TOLERANCE = 15;
    // ciclo do dia do mês para distância circular: dia 31 e dia 1 distam 1
    private static final int MONTH_CYCLE = 31;
    // valor "fixo" tolera arredondamento de câmbio/imposto de até 5%
    private static final BigDecimal FIXED_SPREAD_RATIO = new BigDecimal("0.05");
    // Banda de conciliação da série agendada pelo usuário: a chave curada é um
    // token genérico ("luz", "aluguel") e sem banda casaria com qualquer PIX que
    // o cite. FIXED é assinatura/mensalidade — a detecção só chama de fixo o que
    // varia menos de 5%, e 15% dá espaço para um reajuste anual sem aceitar
    // cobrança de outra ordem de grandeza. VARIABLE é conta de consumo, cuja
    // oscilação entre verão e inverno passa fácil de 40%: 50% mantém a conta
    // legítima dentro e ainda descarta o valor que nada tem a ver.
    private static final BigDecimal CURATED_FIXED_BAND = new BigDecimal("0.15");
    private static final BigDecimal CURATED_VARIABLE_BAND = new BigDecimal("0.50");

    private final UserRepository userRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final RecurringSeriesRepository seriesRepository;
    private final RecurringSeriesLinkRepository linkRepository;
    private final TransactionTemplate transactionTemplate;

    public record DetectionSummary(int seriesCreated, int seriesUpdated, int linksCreated) {
    }

    public DetectionSummary detect(String email) {
        return detectWithRetry(() -> userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado")));
    }

    public DetectionSummary detectByUserId(UUID userId) {
        return detectWithRetry(() -> userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado")));
    }

    /**
     * Corrida entre duas varreduras do mesmo usuário (listener pós-importação ×
     * gatilho manual): ambas leem "série não existe" e inserem a mesma chave —
     * uma perde no unique. Como a varredura é idempotente, a resposta certa é
     * re-executar UMA vez em transação nova: a repassada enxerga o que a outra
     * commitou e não duplica nada. Se a repassada também perder (corrida em
     * cascata), os fatos já foram materializados por outra execução — devolver
     * "nada novo" é honesto e nunca vira 500 no endpoint manual.
     */
    private DetectionSummary detectWithRetry(Supplier<User> userLoader) {
        try {
            return transactionTemplate.execute(status -> runDetection(userLoader.get()));
        } catch (DataIntegrityViolationException firstLoss) {
            log.info("Varredura concorrente detectada (unique violado), re-executando uma vez: {}",
                    firstLoss.getMessage());
            try {
                return transactionTemplate.execute(status -> runDetection(userLoader.get()));
            } catch (DataIntegrityViolationException secondLoss) {
                log.warn("Varredura perdeu a corrida duas vezes; outra execução materializou os fatos: {}",
                        secondLoss.getMessage());
                return new DetectionSummary(0, 0, 0);
            }
        }
    }

    private DetectionSummary runDetection(User user) {
        // O MESMO filtro da análise pessoal, e não um critério próprio: previsão
        // que soma linha que a Análise não soma responde outra pergunta e vira
        // número que ninguém consegue conferir.
        //
        // `ignored` é a linha que entrou duas vezes, por duas fontes: ela não
        // deveria existir, quanto menos virar um hábito. Vinte delas no extrato
        // do dono, e sem este filtro cada uma engorda a série do lado que ficou.
        //
        // `internalTransfer` FICA, e não é esquecimento: o detector já funde as
        // duas pernas numa série INTERNAL, que a previsão ignora por construção.
        // Filtrar aqui apagaria esse registro sem melhorar número nenhum.
        //
        // `familyTransfer` também fica: o Pix para a esposa sai da conta dele de
        // verdade, e a previsão DELE tem de contar com isso. Aquela marca muda a
        // soma da Casa, não a de quem é dono da linha.
        List<BankTransaction> transactions =
                bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()).stream()
                        .filter(tx -> !tx.isIgnored())
                        .toList();
        if (transactions.isEmpty()) {
            return new DetectionSummary(0, 0, 0);
        }

        Map<UUID, MerchantKeyExtractor.Extraction> extractions = new HashMap<>();
        Map<String, Integer> tokenMonthCounts = buildTokenMonthCounts(transactions, extractions);
        Set<UUID> pixPairIds = detectPixPairs(transactions);
        List<String> nameTokens = MerchantKeyExtractor.nameTokens(user.getName());

        List<RecurringSeries> existing = seriesRepository.findAllByUserId(user.getId());
        Map<String, RecurringSeries> seriesByKey = new HashMap<>();
        Map<String, RecurringSeries> curatedSeries = new HashMap<>();
        for (RecurringSeries series : existing) {
            seriesByKey.put(seriesKey(series.getMerchantKey(), series.getFlow()), series);
            // série descartada perde a prioridade da chave curada: o usuário
            // mandou o agendamento embora, e a chave dele não pode continuar
            // capturando transação nenhuma (elas voltam à descoberta normal)
            if (series.getSource() == RecurringSeries.Source.USER && !series.isDismissed()) {
                curatedSeries.put(seriesKey(series.getMerchantKey(), series.getFlow()), series);
            }
        }

        Map<UUID, UUID> seriesIdByLinkedTx = loadLinkedSeriesByTransaction(existing);
        Map<String, List<BankTransaction>> groups = groupByEntityAndFlow(
                transactions, extractions, tokenMonthCounts, pixPairIds, nameTokens,
                curatedSeries, seriesIdByLinkedTx);
        Set<UUID> linkedTransactionIds = new HashSet<>(seriesIdByLinkedTx.keySet());
        Set<UUID> orphanSeriesIds = releaseOrphanLinks(existing, seriesByKey, groups,
                seriesIdByLinkedTx, linkedTransactionIds);
        LocalDate referenceDay = newestDay(transactions);

        int created = 0;
        int updated = 0;
        int linksCreated = 0;
        List<RecurringSeries> dirtySeries = new ArrayList<>();
        List<RecurringSeriesLink> newLinks = new ArrayList<>();
        Set<UUID> matchedSeriesIds = new HashSet<>();

        for (Map.Entry<String, List<BankTransaction>> entry : groups.entrySet()) {
            List<BankTransaction> groupTxs = entry.getValue();
            // O mínimo de 3 ocorrências separa padrão de coincidência — mas só
            // para DESCOBRIR série nova. Série agendada pelo usuário (USER) já é
            // um pedido explícito de casamento: a primeira transação real que
            // cair na chave dela concilia na hora, sem esperar histórico.
            RecurringSeries scheduled = seriesByKey.get(entry.getKey());
            if (!materializes(groupTxs, scheduled)) continue;

            String merchantKey = entry.getKey().substring(0, entry.getKey().lastIndexOf('|'));
            RecurringSeries.Flow flow = RecurringSeries.Flow
                    .valueOf(entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1));
            GroupStats stats = computeStats(groupTxs, extractions, merchantKey);

            RecurringSeries series = scheduled;
            boolean isNew = series == null;
            if (isNew) {
                series = seriesRepository.save(
                        newSeries(user, merchantKey, flow, stats, groupTxs.size(), referenceDay));
                created++;
            } else {
                matchedSeriesIds.add(series.getId());
            }

            int freshLinks = 0;
            for (BankTransaction tx : groupTxs) {
                // transação já vinculada (a esta ou a outra série) nunca revincula
                if (!linkedTransactionIds.add(tx.getId())) continue;
                newLinks.add(RecurringSeriesLink.builder()
                        .seriesId(series.getId())
                        .bankTransactionId(tx.getId())
                        .matchedAt(OffsetDateTime.now())
                        .build());
                freshLinks++;
            }
            linksCreated += freshLinks;

            if (!isNew) {
                boolean changed = updateSeries(series, stats, groupTxs.size(), freshLinks, referenceDay);
                if (changed) {
                    dirtySeries.add(series);
                    updated++;
                }
            }
        }

        // séries que a varredura não reencontrou. Órfã de verdade (tinha vínculos
        // e o motor acabou de redistribuí-los, ver releaseOrphanLinks) sai de
        // cena agora: ativa, ela apareceria na tela e na previsão ao lado da
        // gêmea que herdou o histórico — e IRREGULAR nunca fica stale, então
        // ficaria lá para sempre. Sem vínculo a liberar (dado que parou de vir),
        // a inatividade continua sendo medida pela própria série.
        for (RecurringSeries series : existing) {
            if (matchedSeriesIds.contains(series.getId())) continue;
            if (series.getSource() != RecurringSeries.Source.DETECTED || !series.isActive()) continue;
            boolean orphaned = orphanSeriesIds.contains(series.getId());
            if (!orphaned) {
                if (series.getLastSeenAt() == null) continue;
                if (!isStale(series.getCadence(), series.getDayTolerance(),
                        utcDay(series.getLastSeenAt()), referenceDay)) continue;
            }
            series.setActive(false);
            dirtySeries.add(series);
            updated++;
        }

        if (!dirtySeries.isEmpty()) seriesRepository.saveAll(dirtySeries);
        if (!newLinks.isEmpty()) linkRepository.saveAll(newLinks);
        // flush explícito: a violação de unique de uma varredura concorrente tem
        // que estourar AQUI, como DataIntegrityViolationException traduzida, para
        // o retry enxergá-la — deixada para o flush do commit, ela chegaria
        // embrulhada em TransactionSystemException e escaparia do catch
        linkRepository.flush();

        log.info("Detecção de recorrência: {} séries novas, {} atualizadas, {} vínculos, user={}",
                created, updated, linksCreated, user.getEmail());
        return new DetectionSummary(created, updated, linksCreated);
    }

    /**
     * O grupo vira (ou alimenta) uma série? O mínimo de 3 ocorrências separa
     * padrão de coincidência — mas só para DESCOBRIR série nova. Série agendada
     * pelo usuário (USER) já é um pedido explícito de casamento: a primeira
     * transação real que cair na chave dela concilia na hora, sem esperar
     * histórico.
     */
    private boolean materializes(List<BankTransaction> groupTxs, RecurringSeries scheduled) {
        boolean curatedTarget = scheduled != null
                && scheduled.getSource() == RecurringSeries.Source.USER
                && !scheduled.isDismissed();
        return groupTxs.size() >= MIN_OCCURRENCES || curatedTarget;
    }

    /**
     * Libera os vínculos das séries DETECTED que esta varredura não vai
     * reencontrar, para que as transações delas possam ser vinculadas de novo
     * onde o motor as agrupa AGORA.
     *
     * <p>Quando a derivação da chave muda (EC-111: nome do banco virou
     * stopword, PIX ganhou chave composta) ou o token dominante oscila entre
     * varreduras, a série gravada com a chave antiga não é reencontrada e o
     * mesmo histórico nasce de novo sob a chave nova. Sem esta liberação a
     * gêmea nascia SEM vínculo nenhum — "transação já vinculada nunca
     * revincula" — e a previsão de saldo nunca a via liquidada no mês, enquanto
     * a órfã, ainda ativa, projetava a mesma cobrança ao lado dela. Os
     * vínculos seguem a transação para a série que a explica hoje; a órfã fica
     * sem nenhum e é desativada logo adiante, com a contagem de ocorrências
     * preservada como histórico.
     *
     * <p>Todos os vínculos da órfã são liberados, não só os que alguma série
     * absorve nesta varredura: a transação que hoje cai num grupo abaixo do
     * mínimo, presa à órfã, bloquearia a série dela quando vier a existir.
     * Série USER nunca entra aqui — é curadoria, e a chave dela é reencontrada
     * pela prioridade que tem em {@link #electKey}.
     *
     * <p>O delete é em lote e roda AGORA, antes de qualquer insert desta
     * varredura: no flush o Hibernate executa INSERTs antes de DELETEs, e o
     * UNIQUE de {@code bank_transaction_id} estouraria (ver
     * {@link RecurringSeriesLinkRepository#deleteAllBySeriesIdIn}).
     *
     * @return ids das séries órfãs cujos vínculos foram liberados
     */
    private Set<UUID> releaseOrphanLinks(List<RecurringSeries> existing,
                                         Map<String, RecurringSeries> seriesByKey,
                                         Map<String, List<BankTransaction>> groups,
                                         Map<UUID, UUID> seriesIdByLinkedTx,
                                         Set<UUID> linkedTransactionIds) {
        Set<UUID> refound = new HashSet<>();
        for (Map.Entry<String, List<BankTransaction>> entry : groups.entrySet()) {
            RecurringSeries series = seriesByKey.get(entry.getKey());
            if (series != null && materializes(entry.getValue(), series)) refound.add(series.getId());
        }
        Set<UUID> candidates = new HashSet<>();
        for (RecurringSeries series : existing) {
            if (series.getSource() != RecurringSeries.Source.DETECTED) continue;
            if (!refound.contains(series.getId())) candidates.add(series.getId());
        }
        Set<UUID> orphans = new HashSet<>();
        for (Map.Entry<UUID, UUID> link : seriesIdByLinkedTx.entrySet()) {
            if (!candidates.contains(link.getValue())) continue;
            orphans.add(link.getValue());
            linkedTransactionIds.remove(link.getKey());
        }
        if (!orphans.isEmpty()) {
            int released = linkRepository.deleteAllBySeriesIdIn(orphans);
            log.info("Detecção de recorrência: {} vínculos liberados de {} séries não reencontradas",
                    released, orphans.size());
        }
        return orphans;
    }

    // ------------------------------------------------------------------
    // agrupamento
    // ------------------------------------------------------------------

    private Map<String, Integer> buildTokenMonthCounts(List<BankTransaction> transactions,
                                                       Map<UUID, MerchantKeyExtractor.Extraction> extractions) {
        Map<String, Set<YearMonth>> tokenMonths = new HashMap<>();
        for (BankTransaction tx : transactions) {
            MerchantKeyExtractor.Extraction extraction = MerchantKeyExtractor.extract(tx.getDescription());
            extractions.put(tx.getId(), extraction);
            if (extraction.anchor() != null) continue;
            YearMonth month = YearMonth.from(utcDay(tx.getDate()));
            for (String token : new HashSet<>(extraction.tokens())) {
                tokenMonths.computeIfAbsent(token, k -> new HashSet<>()).add(month);
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        tokenMonths.forEach((token, months) -> counts.put(token, months.size()));
        return counts;
    }

    /**
     * PIX de mesmo valor entrando e saindo no MESMO dia é o titular movendo
     * dinheiro entre os próprios bancos — os dois lados são candidatos a
     * transferência interna.
     */
    private Set<UUID> detectPixPairs(List<BankTransaction> transactions) {
        Map<String, List<UUID>> credits = new HashMap<>();
        Map<String, List<UUID>> debits = new HashMap<>();
        for (BankTransaction tx : transactions) {
            String description = tx.getDescription();
            if (description == null || !description.toLowerCase(java.util.Locale.ROOT).contains("pix")) continue;
            String key = utcDay(tx.getDate()) + "|" + tx.getAmount().abs().stripTrailingZeros().toPlainString();
            ("CREDIT".equalsIgnoreCase(tx.getType()) ? credits : debits)
                    .computeIfAbsent(key, k -> new ArrayList<>()).add(tx.getId());
        }
        Set<UUID> flagged = new HashSet<>();
        for (Map.Entry<String, List<UUID>> entry : credits.entrySet()) {
            List<UUID> matchingDebits = debits.get(entry.getKey());
            if (matchingDebits == null) continue;
            flagged.addAll(entry.getValue());
            flagged.addAll(matchingDebits);
        }
        return flagged;
    }

    private Map<String, List<BankTransaction>> groupByEntityAndFlow(
            List<BankTransaction> transactions,
            Map<UUID, MerchantKeyExtractor.Extraction> extractions,
            Map<String, Integer> tokenMonthCounts,
            Set<UUID> pixPairIds,
            List<String> nameTokens,
            Map<String, RecurringSeries> curatedSeries,
            Map<UUID, UUID> seriesIdByLinkedTx) {

        // 1º passe: agrupa por (entidade, direção do dinheiro)
        Map<String, List<BankTransaction>> byDirection = new LinkedHashMap<>();
        Map<String, Boolean> anchoredGroups = new HashMap<>();
        // Grupos com perna de movimentação entre contas do titular (EC-106). Ao
        // contrário das heurísticas do 2º passe, esta marca é FATO gravado na
        // importação (o tipo da conta de origem no Pluggy), não palpite sobre
        // texto: uma transação marcada basta para o grupo inteiro ser interno.
        Set<String> internalGroups = new HashSet<>();
        for (BankTransaction tx : transactions) {
            MerchantKeyExtractor.Extraction extraction = extractions.get(tx.getId());
            RecurringSeries.Flow direction = "CREDIT".equalsIgnoreCase(tx.getType())
                    ? RecurringSeries.Flow.INCOME
                    : RecurringSeries.Flow.EXPENSE;
            // a âncora (fatura/salário) sai da própria descrição e não disputa com
            // chave curada nenhuma: não há sequestro a evitar
            String key = extraction.anchor() != null
                    ? extraction.anchor()
                    : electKey(tx, direction, extraction, tokenMonthCounts,
                    curatedSeries, seriesIdByLinkedTx);
            if (key == null || key.isBlank()) continue;
            String groupKey = seriesKey(key, direction);
            byDirection.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(tx);
            anchoredGroups.putIfAbsent(groupKey, extraction.anchor() != null);
            if (tx.isInternalTransfer()) internalGroups.add(groupKey);
        }

        // 2º passe: resolve INTERNAL por grupo e funde os dois sentidos da
        // transferência do próprio titular numa série só. TreeMap para a ordem
        // de processamento (e os contadores do resumo) ser determinística.
        Map<String, List<BankTransaction>> resolved = new TreeMap<>();
        for (Map.Entry<String, List<BankTransaction>> entry : byDirection.entrySet()) {
            String merchantKey = entry.getKey().substring(0, entry.getKey().lastIndexOf('|'));
            RecurringSeries.Flow flow = RecurringSeries.Flow
                    .valueOf(entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1));
            // A marca da importação vem ANTES da âncora: o pagamento de fatura é
            // ancorado em "fatura" e, pela regra antiga, jamais viraria INTERNAL
            // — nasciam duas séries mensais, "fatura|EXPENSE" (a saída da conta
            // corrente) e "fatura|INCOME" (o crédito dentro do cartão), e a
            // previsão de saldo projetava uma receita do tamanho da fatura que
            // não existe. Marcado como interno, os dois sentidos caem na MESMA
            // série INTERNAL pela fusão que já existe logo abaixo, e a previsão
            // (que ignora INTERNAL) para de projetá-la.
            if (internalGroups.contains(entry.getKey())) {
                flow = RecurringSeries.Flow.INTERNAL;
            } else if (!anchoredGroups.get(entry.getKey())) {
                // âncoras (fatura/salário) citam o titular por natureza — nunca viram INTERNAL
                // Par PIX (mesmo dia+valor nos dois sentidos) sozinho NÃO basta:
                // pagar a diarista no dia em que entra um PIX de mesmo valor é
                // coincidência, não movimentação própria. O par só conta quando a
                // descrição cita o titular ou não identifica contraparte nenhuma.
                long internalCount = entry.getValue().stream()
                        .filter(tx -> {
                            if (MerchantKeyExtractor.mentionsName(
                                    MerchantKeyExtractor.lightNormalize(tx.getDescription()), nameTokens)) {
                                return true;
                            }
                            return pixPairIds.contains(tx.getId())
                                    && extractions.get(tx.getId()).tokens().isEmpty();
                        })
                        .count();
                if (internalCount * 2 >= entry.getValue().size()) {
                    flow = RecurringSeries.Flow.INTERNAL;
                }
            }
            resolved.computeIfAbsent(seriesKey(merchantKey, flow), k -> new ArrayList<>())
                    .addAll(entry.getValue());
        }
        return resolved;
    }

    /**
     * Elege a chave de agrupamento da transação.
     *
     * <p>A chave de uma série agendada pelo usuário (EC-096) tem prioridade sobre
     * o token dominante: a chave dela nasceu do mesmo extrator (deriveKey sobre o
     * matchHint/nome), mas o dominante do histórico real pode eleger OUTRO token
     * da mesma descrição ("apartamento" em vez de "aluguel") e a conciliação
     * pedida explicitamente nunca aconteceria.
     *
     * <p>Prioridade sem guarda, porém, é sequestro: chave curada é palavra
     * genérica ("luz", "aluguel", "escola"), e qualquer PIX que a cite entraria
     * na série agendada — marcando o mês como pago e criando um vínculo que
     * nenhum endpoint desfaz. Por isso a transação só entra na série curada se
     * {@link #matchesCuratedSeries} concordar; senão a chave é reeleita entre os
     * tokens restantes e a transação segue o fluxo normal de descoberta. Quando
     * não sobra token nenhum ela simplesmente não é agrupada — como já acontece
     * com descrição feita só de ruído.
     *
     * <p>A chave "natural" da transação (o que a descoberta daria a ela — token
     * dominante, ou nome composto no PIX, ver {@link MerchantKeyExtractor#entityKey})
     * é testada contra as curadas ANTES dos tokens soltos: é assim que o
     * agendamento "Pix Maria Souza" (chave "maria souza", pelo mesmo deriveKey)
     * concilia os PIX reais dessa pessoa.
     */
    private String electKey(BankTransaction tx, RecurringSeries.Flow direction,
                            MerchantKeyExtractor.Extraction extraction,
                            Map<String, Integer> tokenMonthCounts,
                            Map<String, RecurringSeries> curatedSeries,
                            Map<UUID, UUID> seriesIdByLinkedTx) {
        if (curatedSeries.isEmpty()) {
            return MerchantKeyExtractor.entityKey(extraction, extraction.tokens(), tokenMonthCounts);
        }
        List<String> candidates = new ArrayList<>(extraction.tokens());
        while (!candidates.isEmpty()) {
            String dominant = MerchantKeyExtractor.dominantToken(candidates, tokenMonthCounts);
            String natural = MerchantKeyExtractor.entityKey(extraction, candidates, tokenMonthCounts);
            String elected = natural;
            if (!curatedSeries.containsKey(seriesKey(natural, direction))) {
                for (String token : candidates) {
                    if (curatedSeries.containsKey(seriesKey(token, direction))) {
                        elected = token;
                        break;
                    }
                }
            }
            RecurringSeries curated = curatedSeries.get(seriesKey(elected, direction));
            // a chave natural nasce do próprio dominante: não há token marginal a
            // comparar, e o teste de meses se faz com o dominante
            String curatedToken = elected.equals(natural) ? dominant : elected;
            if (curated == null || matchesCuratedSeries(curated, tx, curatedToken, dominant,
                    tokenMonthCounts, seriesIdByLinkedTx)) {
                return elected;
            }
            candidates.remove(curatedToken);
        }
        return null;
    }

    /**
     * A transação parece mesmo a cobrança agendada? Duas evidências, ambas
     * necessárias:
     *
     * <ol>
     * <li>a chave curada não pode ser um token marginal da descrição — se o token
     * que a detecção elegeria aparece em MAIS meses do histórico, quem manda é
     * ele ("Supermercado Luz da Manhã" é do supermercado, não da conta de luz);
     * <li>o valor tem que cair na banda do {@code expectedAmount} declarado
     * (ver {@link #CURATED_FIXED_BAND}/{@link #CURATED_VARIABLE_BAND}) — é o
     * discriminador que separa a conta de luz de R$ 240 do PIX de R$ 60 para
     * "Ana Luz".
     * </ol>
     *
     * <p>Transação JÁ conciliada com esta série numa varredura anterior passa
     * direto: revisar vínculo consolidado faria um reajuste acima da banda expulsar
     * da série o próprio histórico que a originou.
     */
    private boolean matchesCuratedSeries(RecurringSeries curated, BankTransaction tx,
                                         String curatedKey, String dominant,
                                         Map<String, Integer> tokenMonthCounts,
                                         Map<UUID, UUID> seriesIdByLinkedTx) {
        if (curated.getId() != null && curated.getId().equals(seriesIdByLinkedTx.get(tx.getId()))) {
            return true;
        }
        if (tokenMonthCounts.getOrDefault(curatedKey, 0) < tokenMonthCounts.getOrDefault(dominant, 0)) {
            return false;
        }
        BigDecimal expected = curated.getExpectedAmount();
        // sem valor de referência não há banda a aplicar (o agendamento pela API
        // sempre tem um; é proteção contra dado legado)
        if (expected == null || expected.signum() <= 0) return true;
        BigDecimal band = curated.getAmountType() == RecurringSeries.AmountType.VARIABLE
                ? CURATED_VARIABLE_BAND
                : CURATED_FIXED_BAND;
        return tx.getAmount().abs().subtract(expected).abs()
                .compareTo(expected.multiply(band)) <= 0;
    }

    // ------------------------------------------------------------------
    // estatística do grupo
    // ------------------------------------------------------------------

    private record GroupStats(RecurringSeries.Cadence cadence, Short anchorDay, Short dayTolerance,
                              RecurringSeries.AmountType amountType, BigDecimal expectedAmount,
                              OffsetDateTime firstSeen, OffsetDateTime lastSeen,
                              String displayName, UUID categoryId) {
    }

    private GroupStats computeStats(List<BankTransaction> groupTxs,
                                    Map<UUID, MerchantKeyExtractor.Extraction> extractions,
                                    String merchantKey) {
        List<BankTransaction> sorted = new ArrayList<>(groupTxs);
        sorted.sort(java.util.Comparator.comparing(BankTransaction::getDate));
        BankTransaction latest = sorted.get(sorted.size() - 1);

        List<LocalDate> allDays = sorted.stream().map(tx -> utcDay(tx.getDate())).distinct().sorted().toList();
        // A cadência é o que a série FAZ HOJE, não a média de toda a vida dela.
        // Medido no extrato real (EC-111): a fatura do cartão foi paga em duas a
        // cinco parcelas por mês no primeiro ano e em uma só nos 14 meses
        // seguintes; sobre os 33 intervalos a mediana caía num gap de 23 dias e
        // a maior despesa recorrente do usuário saía IRREGULAR — fora da previsão,
        // que ignora IRREGULAR por construção. A janela dos últimos 12 intervalos
        // cobre um ano de cobrança mensal ou um trimestre de semanal, que é o
        // horizonte em que o comportamento atual se estabelece; série curta usa
        // tudo o que tem, como antes.
        List<LocalDate> days = allDays.size() > RECENT_INTERVALS + 1
                ? allDays.subList(allDays.size() - RECENT_INTERVALS - 1, allDays.size())
                : allDays;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
        }

        RecurringSeries.Cadence hint = corroboratedHint(sorted, extractions);
        RecurringSeries.Cadence cadence = hint != null ? hint : classifyCadence(gaps);
        if (cadence == RecurringSeries.Cadence.IRREGULAR && mensalPelosMeses(days)) {
            cadence = RecurringSeries.Cadence.MONTHLY;
        }

        Short anchorDay = null;
        Short dayTolerance = null;
        if (cadence == RecurringSeries.Cadence.MONTHLY || cadence == RecurringSeries.Cadence.QUARTERLY) {
            int anchor = circularAnchor(days);
            anchorDay = (short) anchor;
            int tolerance = 0;
            for (LocalDate day : days) {
                tolerance = Math.max(tolerance, circularDistance(anchor, day.getDayOfMonth()));
            }
            dayTolerance = (short) Math.min(tolerance, MAX_DAY_TOLERANCE);
        }

        // Valor esperado e tipo de valor saem da MESMA janela recente: a média de
        // toda a vida da fatura (R$ 623, puxada pelas parcelas de R$ 75 do
        // primeiro ano) previa um terço do que ela custa hoje (R$ 1,3 mil a 2,6
        // mil nos últimos doze meses), e uma assinatura reajustada há um ano
        // continuaria VARIABLE para sempre por causa do preço antigo.
        Set<LocalDate> recentDays = new HashSet<>(days);
        List<BankTransaction> recent = sorted.stream()
                .filter(tx -> recentDays.contains(utcDay(tx.getDate())))
                .toList();
        List<BigDecimal> todos = recent.stream().map(tx -> tx.getAmount().abs()).toList();
        // A janela do valor é mais curta que a da cadência — ver RECENT_AMOUNTS
        List<BigDecimal> amounts = todos.size() > RECENT_AMOUNTS
                ? todos.subList(todos.size() - RECENT_AMOUNTS, todos.size())
                : todos;
        BigDecimal min = amounts.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = amounts.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        // mediana de verdade exige ordenar por VALOR: "amounts" está em ordem
        // cronológica, e indexar o meio dela pegava um valor arbitrário como
        // escala da banda de 5% do FIXED
        List<BigDecimal> byValue = new ArrayList<>(amounts);
        byValue.sort(BigDecimal::compareTo);
        BigDecimal median = byValue.get(byValue.size() / 2);
        boolean fixed = max.subtract(min)
                .compareTo(median.multiply(FIXED_SPREAD_RATIO).max(new BigDecimal("0.01"))) <= 0;
        // FIXED segue o último valor (reajuste de assinatura muda a previsão na
        // hora); VARIABLE usa a MEDIANA da janela curta, e não a média.
        //
        // A média foi trocada por dois motivos medidos no extrato real. Ela se
        // deixa puxar por um único valor fora da curva — o dono pagou R$ 1.291 de
        // uma fatura de R$ 2 mil em 07/2026, e essa única linha derrubava a
        // previsão das seguintes — e, num número que sobe mês a mês, ela fica
        // sempre atrás. Nas seis últimas faturas: média R$ 2.123, mediana
        // R$ 2.213, próxima fatura real R$ 2.311.
        BigDecimal expected = fixed
                ? latest.getAmount().abs().setScale(4, RoundingMode.HALF_UP)
                : median.setScale(4, RoundingMode.HALF_UP);

        String displayName = latest.getDescription() == null || latest.getDescription().isBlank()
                ? merchantKey
                : truncate(latest.getDescription().trim());

        return new GroupStats(cadence, anchorDay, dayTolerance,
                fixed ? RecurringSeries.AmountType.FIXED : RecurringSeries.AmountType.VARIABLE,
                expected, sorted.get(0).getDate(), latest.getDate(), displayName,
                dominantCategory(sorted));
    }

    /**
     * A dica de cadência do descritivo ("Mensal"/"Trimestral") só manda quando
     * ela é a regra do grupo, não a exceção. A regra original — a dica da
     * transação MAIS RECENTE vence o histórico — existe para a troca de plano:
     * o descritivo novo já diz a cadência daqui para frente. Mas ela pressupõe
     * que o grupo é uma entidade só. No extrato real (EC-111) um único crédito
     * "Plano ... Trimestral" caiu num grupo de 271 lançamentos sem dica nenhuma e
     * o classificou inteiro como QUARTERLY. Exigir dica em pelo menos metade do
     * grupo mantém a troca de plano (todas as cobranças do plano trazem a dica) e
     * neutraliza o intruso: sem maioria, quem decide são os intervalos.
     */
    private RecurringSeries.Cadence corroboratedHint(List<BankTransaction> sorted,
                                                     Map<UUID, MerchantKeyExtractor.Extraction> extractions) {
        BankTransaction latest = sorted.get(sorted.size() - 1);
        RecurringSeries.Cadence hint = extractions.get(latest.getId()).cadenceHint();
        if (hint == null) return null;
        long hinted = sorted.stream()
                .filter(tx -> extractions.get(tx.getId()).cadenceHint() != null)
                .count();
        return hinted * 2 >= sorted.size() ? hint : null;
    }

    /**
     * Resgate do que é mensal e os intervalos não viram — só quando eles já
     * desistiram (IRREGULAR), então esta regra nunca reclassifica nada, só
     * acrescenta.
     *
     * <p>O caso medido: o vale-refeição do dono cai todo mês (25/06, 29/07,
     * 28/08), e uma recarga extra de R$ 24 em 07/07 partiu os intervalos em
     * 12/22/30 dias. A mediana caiu em 22 e a segunda maior renda dele saiu
     * IRREGULAR — fora da previsão, que ignora irregular por construção.
     *
     * <p>A assinatura aqui não olha intervalo nenhum: <b>o que acontece em todo
     * mês é mensal</b>, por definição. Exige três meses de calendário seguidos,
     * sem buraco, e no máximo uma ocorrência e meia por mês — esse teto é o que
     * impede a padaria de três visitas por mês de virar "conta mensal".
     */
    private boolean mensalPelosMeses(List<LocalDate> days) {
        List<YearMonth> meses = days.stream().map(YearMonth::from).distinct().sorted().toList();
        if (meses.size() < 3) return false;
        for (int i = 1; i < meses.size(); i++) {
            if (ChronoUnit.MONTHS.between(meses.get(i - 1), meses.get(i)) != 1) return false;
        }
        return days.size() * 2 <= meses.size() * 3;
    }

    private RecurringSeries.Cadence classifyCadence(List<Long> gaps) {
        if (gaps.isEmpty()) return RecurringSeries.Cadence.IRREGULAR;
        if (gaps.size() == 2) {
            // Com só dois intervalos a "mediana" superior era o MAIOR gap: um
            // único mês falhado (jan/fev/abr) virava IRREGULAR, e um pulo de dois
            // meses virava QUARTERLY falso. Voto por gap: cada intervalo
            // classifica sozinho; empate entre cadências fica com a mais curta,
            // porque um gap longo pode ser múltiplo de um ciclo curto perdido —
            // o contrário nunca.
            RecurringSeries.Cadence first = cadenceBand(gaps.get(0));
            RecurringSeries.Cadence second = cadenceBand(gaps.get(1));
            if (first == second) return first != null ? first : RecurringSeries.Cadence.IRREGULAR;
            if (first == null) return second;
            if (second == null) return first;
            return cycleDays(first) <= cycleDays(second) ? first : second;
        }
        List<Long> ordered = new ArrayList<>(gaps);
        ordered.sort(Long::compareTo);
        RecurringSeries.Cadence band = cadenceBand(ordered.get(ordered.size() / 2));
        return band != null ? band : RecurringSeries.Cadence.IRREGULAR;
    }

    /** Faixa de cadência de um único intervalo; null quando não cai em nenhuma. */
    private RecurringSeries.Cadence cadenceBand(long gap) {
        if (gap >= 26 && gap <= 35) return RecurringSeries.Cadence.MONTHLY;
        if (gap >= 5 && gap <= 9) return RecurringSeries.Cadence.WEEKLY;
        if (gap >= 80 && gap <= 100) return RecurringSeries.Cadence.QUARTERLY;
        return null;
    }

    private int cycleDays(RecurringSeries.Cadence cadence) {
        return switch (cadence) {
            case WEEKLY -> 7;
            case MONTHLY -> 31;
            case QUARTERLY -> 92;
            case IRREGULAR -> Integer.MAX_VALUE; // sem ciclo definido
        };
    }

    /**
     * Dia âncora por mediana circular: a cobrança que desliza do dia 30 para o
     * dia 02 do mês seguinte continua a ~2 dias da âncora, não a ~28. Escolhe o
     * dia (1..31) que minimiza a soma das distâncias circulares.
     */
    private int circularAnchor(List<LocalDate> days) {
        int bestAnchor = 1;
        int bestSum = Integer.MAX_VALUE;
        for (int candidate = 1; candidate <= MONTH_CYCLE; candidate++) {
            int sum = 0;
            for (LocalDate day : days) {
                sum += circularDistance(candidate, day.getDayOfMonth());
            }
            if (sum < bestSum) {
                bestSum = sum;
                bestAnchor = candidate;
            }
        }
        return bestAnchor;
    }

    private int circularDistance(int a, int b) {
        int diff = Math.abs(a - b);
        return Math.min(diff, MONTH_CYCLE - diff);
    }

    private UUID dominantCategory(List<BankTransaction> sorted) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (BankTransaction tx : sorted) {
            if (tx.getCategoryId() != null) counts.merge(tx.getCategoryId(), 1, Integer::sum);
        }
        UUID best = null;
        int bestCount = 0;
        // percorre do mais recente ao mais antigo: empate fica com a categoria atual
        for (int i = sorted.size() - 1; i >= 0; i--) {
            UUID categoryId = sorted.get(i).getCategoryId();
            if (categoryId == null) continue;
            int count = counts.get(categoryId);
            if (count > bestCount) {
                bestCount = count;
                best = categoryId;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // persistência
    // ------------------------------------------------------------------

    private RecurringSeries newSeries(User user, String merchantKey, RecurringSeries.Flow flow,
                                      GroupStats stats, int occurrences, LocalDate referenceDay) {
        return RecurringSeries.builder()
                .user(user)
                .merchantKey(merchantKey)
                .displayName(stats.displayName())
                .categoryId(stats.categoryId())
                .flow(flow)
                .cadence(stats.cadence())
                .anchorDay(stats.anchorDay())
                .dayTolerance(stats.dayTolerance())
                .amountType(stats.amountType())
                .expectedAmount(stats.expectedAmount())
                .occurrences(occurrences)
                .firstSeenAt(stats.firstSeen())
                .lastSeenAt(stats.lastSeen())
                .active(!isStale(stats.cadence(), stats.dayTolerance(), utcDay(stats.lastSeen()), referenceDay))
                .source(RecurringSeries.Source.DETECTED)
                .build();
    }

    /** Aplica os agregados recalculados; devolve true se algo mudou de fato. */
    private boolean updateSeries(RecurringSeries series, GroupStats stats, int occurrences,
                                 int freshLinks, LocalDate referenceDay) {
        boolean changed = freshLinks > 0;
        if (series.getOccurrences() != occurrences) {
            series.setOccurrences(occurrences);
            changed = true;
        }
        if (series.getSource() == RecurringSeries.Source.DETECTED) {
            changed |= set(series.getCadence(), stats.cadence(), series::setCadence);
            changed |= set(series.getAnchorDay(), stats.anchorDay(), series::setAnchorDay);
            changed |= set(series.getDayTolerance(), stats.dayTolerance(), series::setDayTolerance);
            changed |= set(series.getAmountType(), stats.amountType(), series::setAmountType);
            changed |= setAmount(series, stats.expectedAmount());
            changed |= set(series.getFirstSeenAt(), stats.firstSeen(), series::setFirstSeenAt);
            changed |= set(series.getLastSeenAt(), stats.lastSeen(), series::setLastSeenAt);
            // nome e categoria escolhidos pelo usuário nunca são sobrescritos
            if (series.getDisplayName() == null || series.getDisplayName().isBlank()) {
                changed |= set(series.getDisplayName(), stats.displayName(), series::setDisplayName);
            }
            if (series.getCategoryId() == null && stats.categoryId() != null) {
                series.setCategoryId(stats.categoryId());
                changed = true;
            }
            boolean stale = isStale(stats.cadence(), stats.dayTolerance(),
                    utcDay(stats.lastSeen()), referenceDay);
            if (stale && series.isActive()) {
                series.setActive(false);
                changed = true;
            } else if (!stale && !series.isActive() && !series.isDismissed() && freshLinks > 0) {
                // só evidência nova reativa — e nunca uma série descartada pelo
                // usuário (dismissed): o descarte só sai via PATCH active=true.
                // Sem cobrança nova, a desativação do motor também permanece.
                series.setActive(true);
                changed = true;
            }
        } else {
            // série curada pelo usuário: a detecção só registra evidência nova
            changed |= set(series.getFirstSeenAt(), stats.firstSeen(), series::setFirstSeenAt);
            changed |= set(series.getLastSeenAt(), stats.lastSeen(), series::setLastSeenAt);
        }
        return changed;
    }

    private <T> boolean set(T current, T next, Consumer<T> setter) {
        if (Objects.equals(current, next)) return false;
        setter.accept(next);
        return true;
    }

    private boolean setAmount(RecurringSeries series, BigDecimal next) {
        BigDecimal current = series.getExpectedAmount();
        if (current != null && next != null && current.compareTo(next) == 0) return false;
        if (current == null && next == null) return false;
        series.setExpectedAmount(next);
        return true;
    }

    private boolean isStale(RecurringSeries.Cadence cadence, Short dayTolerance,
                            LocalDate lastSeenDay, LocalDate referenceDay) {
        // sem ciclo definido não há como medir atraso
        if (cadence == RecurringSeries.Cadence.IRREGULAR) return false;
        long silence = ChronoUnit.DAYS.between(lastSeenDay, referenceDay);
        int tolerance = dayTolerance != null ? dayTolerance : 0;
        return silence > (long) STALE_CYCLES * cycleDays(cadence) + tolerance;
    }

    /** Transação já vinculada -> série que a conciliou (nunca há mais de uma). */
    private Map<UUID, UUID> loadLinkedSeriesByTransaction(List<RecurringSeries> existing) {
        Map<UUID, UUID> linked = new HashMap<>();
        if (existing.isEmpty()) return linked;
        List<UUID> ids = existing.stream().map(RecurringSeries::getId).toList();
        for (RecurringSeriesLink link : linkRepository.findAllBySeriesIdIn(ids)) {
            linked.put(link.getBankTransactionId(), link.getSeriesId());
        }
        return linked;
    }

    private LocalDate newestDay(List<BankTransaction> transactions) {
        LocalDate newest = null;
        for (BankTransaction tx : transactions) {
            LocalDate day = utcDay(tx.getDate());
            if (newest == null || day.isAfter(newest)) newest = day;
        }
        return newest;
    }

    private static String seriesKey(String merchantKey, RecurringSeries.Flow flow) {
        return merchantKey + "|" + flow.name();
    }

    private static LocalDate utcDay(OffsetDateTime date) {
        return date.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private static String truncate(String value) {
        return value.length() > 160 ? value.substring(0, 160).trim() : value;
    }
}
