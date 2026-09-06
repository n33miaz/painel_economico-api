package br.com.economize.service;

import br.com.economize.dto.account.AccountResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro das contas de origem do usuário — EC-113.
 *
 * <p>Fica fora do pacote do Pluggy de propósito: a dimensão de conta é do
 * domínio, não do agregador. O conector traduz o que a API dele devolve para um
 * {@link AccountSnapshot} e este serviço decide como isso vira (ou atualiza)
 * uma linha — o dia em que existir um segundo conector, nada aqui muda.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorAccountService {

    private static final int NAME_MAX = 120;
    private static final int INSTITUTION_MAX = 160;

    private final ConnectorAccountRepository accountRepository;
    private final UserRepository userRepository;

    /** Origem já traduzida do provedor, pronta para virar linha. */
    public record AccountSnapshot(String providerAccountId, String name, String institution,
                                  ConnectorAccount.AccountType type,
                                  Integer statementClosingDay, Integer statementDueDay,
                                  UUID pluggyItemId) {
    }

    public List<AccountResponse> list(String email) {
        User user = requireUser(email);
        return accountRepository.findAllByUserIdOrderByNameAsc(user.getId()).stream()
                .map(AccountResponse::from)
                .toList();
    }

    /**
     * Conta do usuário, ou 404. O dono é FILTRO da consulta, não checagem
     * posterior: conta de outro usuário responde igual a conta inexistente, para
     * não servir de oráculo sobre a existência de contas alheias.
     */
    public ConnectorAccount requireOwned(UUID accountId, UUID userId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conta não encontrada"));
    }

    /**
     * Cria a origem ou atualiza a que já existe. É upsert, e não insert, porque
     * a sincronização roda de novo toda vez: rótulo comercial, instituição e
     * principalmente as datas de fechamento/vencimento mudam com o tempo, e o
     * usuário que trocou de vencimento não pode ficar com a fatura recortada
     * pelo dia antigo para sempre.
     *
     * <p>O que NUNCA é sobrescrito é o id interno — é ele que os lançamentos já
     * gravados referenciam.
     *
     * <p>A chave normal é o id da conta no provedor. Quando ele não é conhecido,
     * ainda há uma segunda chance antes de criar linha nova: o REVÍNCULO (ver
     * {@link #adoptable}).
     */
    public ConnectorAccount register(User user, AccountSnapshot snapshot) {
        ConnectorAccount known = accountRepository
                .findByUserIdAndProviderAccountId(user.getId(), snapshot.providerAccountId())
                .orElse(null);
        if (known != null) return update(known, snapshot);

        ConnectorAccount orphan = adoptable(user, snapshot);
        if (orphan != null) {
            log.info("Revínculo de instituição: origem existente readotada em vez de duplicar a conta "
                    + "(instituição=\"{}\", tipo={})", snapshot.institution(), snapshot.type());
            orphan.setProviderAccountId(snapshot.providerAccountId());
            return update(orphan, snapshot);
        }
        return insert(user, snapshot);
    }

    /**
     * A origem ÓRFÃ que este registro está reencontrando, ou nulo.
     *
     * <p>O problema. Ao revincular uma instituição, o widget cria um item novo e
     * o Pluggy devolve ids de conta NOVOS para o mesmo cartão. Sem isto,
     * {@code register} cria uma segunda origem: {@code GET /accounts} passa a
     * listar o mesmo cartão duas vezes — uma {@code linked=false} com todo o
     * histórico, outra {@code linked=true} vazia — e a fatura fica cortada ao
     * meio na data da revinculação, porque os lançamentos re-sincronizados
     * chegam com ids externos novos, caem na reconciliação por dia+valor e essa
     * NÃO carimba origem (decisão desta rodada: pareamento plausível não prova
     * identidade).
     *
     * <p>O casamento é deliberadamente estreito, porque adotar a conta errada
     * mistura o histórico de dois cartões — erro pior do que a duplicata que
     * este método evita. Exige, TODOS ao mesmo tempo:
     * <ol>
     * <li>a candidata estar DESVINCULADA ({@code pluggy_item_id IS NULL}) — uma
     * conta que ainda recebe sincronização não é um reencontro;</li>
     * <li>instituição, rótulo e tipo iguais aos do registro que chega — e o
     * rótulo já carrega os últimos dígitos, que é o que distingue dois cartões
     * do mesmo banco;</li>
     * <li>a candidata ser ÚNICA. Duas órfãs com o mesmo rótulo é exatamente o
     * caso em que não há como saber qual é qual: aí a resposta certa é não
     * adivinhar e criar origem nova, que é o comportamento anterior.</li>
     * </ol>
     */
    private ConnectorAccount adoptable(User user, AccountSnapshot snapshot) {
        String name = truncate(snapshot.name(), NAME_MAX);
        String institution = truncate(snapshot.institution(), INSTITUTION_MAX);
        List<ConnectorAccount> candidates = accountRepository
                .findAllByUserIdAndPluggyItemIdIsNull(user.getId()).stream()
                .filter(account -> account.getType() == snapshot.type())
                .filter(account -> Objects.equals(account.getName(), name))
                .filter(account -> Objects.equals(account.getInstitution(), institution))
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private ConnectorAccount update(ConnectorAccount account, AccountSnapshot snapshot) {
        account.setName(truncate(snapshot.name(), NAME_MAX));
        account.setInstitution(truncate(snapshot.institution(), INSTITUTION_MAX));
        account.setType(snapshot.type());
        account.setStatementClosingDay(validDay(snapshot.statementClosingDay()));
        account.setStatementDueDay(validDay(snapshot.statementDueDay()));
        account.setPluggyItemId(snapshot.pluggyItemId());
        return accountRepository.save(account);
    }

    /**
     * Origem criada à mão por quem importa extrato em arquivo.
     *
     * <p>Nasce sempre DESVINCULADA ({@code pluggyItemId} nulo), que é a
     * verdade: nada vai sincronizar nela sozinho. O {@code providerAccountId}
     * ganha um id interno prefixado — a coluna é obrigatória e é a chave
     * natural da tabela, e o prefixo garante que ele jamais colida com um id do
     * Pluggy nem seja confundido com um.
     *
     * <p>Consequência boa e deliberada: uma conta criada aqui é candidata a
     * REVÍNCULO ({@code adoptable}) se depois a mesma instituição for conectada
     * pelo widget — o histórico importado à mão e o sincronizado passam a viver
     * na mesma origem, em vez de virarem duas linhas iguais na tela.
     */
    public ConnectorAccount createManual(String email, String name, String institution,
                                         ConnectorAccount.AccountType type,
                                         Integer statementClosingDay, Integer statementDueDay) {
        User user = requireUser(email);
        return accountRepository.save(ConnectorAccount.builder()
                .user(user)
                .providerAccountId("manual:" + UUID.randomUUID())
                .name(truncate(name.trim(), NAME_MAX))
                .institution(institution == null || institution.isBlank()
                        ? null : truncate(institution.trim(), INSTITUTION_MAX))
                .type(type)
                .statementClosingDay(validDay(statementClosingDay))
                .statementDueDay(validDay(statementDueDay))
                .build());
    }

    private ConnectorAccount insert(User user, AccountSnapshot snapshot) {
        try {
            // saveAndFlush: duas sincronizações simultâneas do mesmo usuário
            // disputam o unique (user_id, provider_account_id). A violação tem
            // que estourar AQUI para o catch abaixo reaproveitar a linha do
            // vencedor — deixada para o flush do commit, ela aconteceria fora do
            // alcance deste bloco e derrubaria a sync inteira (mesmo padrão do
            // EC-096/EC-106).
            return accountRepository.saveAndFlush(ConnectorAccount.builder()
                    .user(user)
                    .pluggyItemId(snapshot.pluggyItemId())
                    .providerAccountId(snapshot.providerAccountId())
                    .name(truncate(snapshot.name(), NAME_MAX))
                    .institution(truncate(snapshot.institution(), INSTITUTION_MAX))
                    .type(snapshot.type())
                    .statementClosingDay(validDay(snapshot.statementClosingDay()))
                    .statementDueDay(validDay(snapshot.statementDueDay()))
                    .build());
        } catch (DataIntegrityViolationException race) {
            log.info("Conta de origem já registrada por uma sincronização concorrente — reaproveitando");
            return accountRepository.findByUserIdAndProviderAccountId(user.getId(), snapshot.providerAccountId())
                    .orElseThrow(() -> race);
        }
    }

    /**
     * Dia do mês só é aceito de 1 a 31. Vem de campo de terceiro; um 0 ou 45
     * gravado aqui estouraria o recorte do ciclo meses depois, num lugar sem
     * nenhuma pista da origem do defeito — melhor perder o metadado e cair no
     * ciclo do calendário, que é um caminho declarado na resposta.
     */
    private Integer validDay(Integer day) {
        return day != null && day >= 1 && day <= 31 ? day : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
