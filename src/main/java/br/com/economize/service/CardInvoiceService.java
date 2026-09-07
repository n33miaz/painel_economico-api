package br.com.economize.service;

import br.com.economize.dto.account.CardInvoicesResponse;
import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fatura como AGRUPAMENTO — EC-113.
 *
 * <p>A fatura não é um objeto que o agregador entrega pronto: o que existe é o
 * extrato do cartão, linha a linha. Este serviço recorta esse extrato em ciclos
 * e soma. Toda fatura devolvida é, portanto, derivada — e a resposta declara
 * qual das duas regras recortou (ver {@code CycleSource}), porque a diferença
 * entre "o provedor me disse que fecha dia 10" e "eu usei o mês do calendário"
 * é visível para o usuário nos últimos dias do mês.
 *
 * <p><b>Não colide com o EC-106/V15.</b> O total da fatura é o que o ciclo gerou
 * de dívida: compras menos estornos. As duas pernas do pagamento continuam fora
 * dele: a da conta corrente porque pertence a OUTRA conta (e o recorte é por
 * accountId), e a do cartão porque é crédito MARCADO como perna interna — ela
 * aparece em {@code paymentsTotal}, que é informação de quitação, não receita.
 * Nada aqui soma em receita/despesa: a agregação mensal continua sendo a do
 * AnalyticsService, que exclui perna interna.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardInvoiceService {

    // Faixa da janela pedida, em faturas FECHADAS (a em aberto vem sempre por
    // cima — ver buildCycles). 24 ciclos cobrem a retrospectiva de dois anos que
    // o app oferece; acima disso a resposta carrega o extrato inteiro do cartão
    // dentro de um JSON e deixa de ser "abrir uma fatura".
    private static final int MIN_MONTHS = 1;
    private static final int MAX_MONTHS = 24;

    private final ConnectorAccountService accountService;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;
    private final InvoiceReserveService reserveService;

    public CardInvoicesResponse invoices(String email, UUID accountId, int months) {
        // validação de entrada antes de qualquer I/O, como no sync do EC-106:
        // parâmetro fora da faixa é 400 dizendo o limite, nunca correção
        // silenciosa que devolve um período que ninguém pediu
        if (months < MIN_MONTHS || months > MAX_MONTHS) {
            throw new IllegalArgumentException(String.format(
                    "Janela inválida: months deve estar entre %d e %d (recebido: %d)",
                    MIN_MONTHS, MAX_MONTHS, months));
        }
        User user = requireUser(email);
        ConnectorAccount account = accountService.requireOwned(accountId, user.getId());
        if (!account.isCreditCard()) {
            // conta bancária não tem fatura. Note que isto é 400 e não 404: a
            // conta EXISTE e é do usuário — esconder isso não protege ninguém,
            // e o 404 do dono errado continua sendo dado no requireOwned acima
            throw new IllegalArgumentException(
                    "Esta conta não é um cartão de crédito — fatura só existe para cartão");
        }

        Integer closingDay = account.getStatementClosingDay();
        CardInvoicesResponse.CycleSource source = closingDay != null
                ? CardInvoicesResponse.CycleSource.PROVIDER_CLOSING_DAY
                : CardInvoicesResponse.CycleSource.CALENDAR_MONTH;

        // O fuso é UTC porque é nele que os parsers gravam a data do extrato e
        // que a janela de análise (EC-092) já recorta; qualquer outro deslocaria
        // o dia de virada do ciclo
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Cycle> cycles = buildCycles(closingDay, account.getStatementDueDay(), today, months);
        if (cycles.isEmpty()) {
            return empty(account, source);
        }

        LocalDate windowStart = cycles.get(cycles.size() - 1).start();
        LocalDate windowEnd = cycles.get(0).end();
        List<BankTransaction> transactions = bankTransactionRepository
                .findAllByUserIdAndAccountIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(), account.getId(),
                        windowStart.atStartOfDay().atOffset(ZoneOffset.UTC),
                        windowEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        // Uma consulta para todas as reservas do cartão (EC-181): dentro do
        // laço seria uma por ciclo, e a janela chega a 24
        Map<String, CardInvoicesResponse.Reserve> reservas =
                reserveService.byReference(user.getId(), account.getId());
        List<CardInvoicesResponse.Invoice> invoices = new ArrayList<>();
        for (Cycle cycle : cycles) {
            List<BankTransaction> inCycle = transactions.stream()
                    .filter(tx -> cycle.contains(utcDay(tx.getDate())))
                    .toList();
            // ciclo vazio não vira fatura: o usuário abriria um mês em branco de
            // um cartão que só começou a ser sincronizado depois
            if (inCycle.isEmpty()) continue;
            invoices.add(toInvoice(cycle, inCycle, today,
                    reservas.get(cycle.reference().toString())));
        }
        return new CardInvoicesResponse(account.getId(), account.getName(), account.getType(),
                account.getInstitution(), source, invoices);
    }

    /**
     * Soma o ciclo separando as TRÊS coisas que um crédito de cartão pode ser.
     *
     * <p>O sinal diz o que é compra (negativo, porque o sync espelha a convenção
     * do Pluggy) e o que é crédito. Entre os créditos, o que separa PAGAMENTO de
     * ESTORNO é o sinal ESTRUTURAL que a V15 já grava, não heurística de texto:
     * pagamento de fatura é perna de movimentação entre contas do titular e vem
     * marcado na importação; estorno de compra é crédito sem marca.
     *
     * <p>A diferença muda o número que o usuário lê. Compra de 100 estornada no
     * mesmo ciclo deixa dívida ZERO — mas com os dois no mesmo balde a resposta
     * era {@code total=100, paymentsTotal=100}, e não havia como o app derivar
     * "não deve nada" a partir disso: exatamente o mesmo par de números sai de
     * uma fatura de 100 já paga, que é outra situação.
     *
     * <p><b>Limitação assumida:</b> pagamento de fatura importado ANTES da V15
     * (ou de conta cuja contrapartida nunca apareceu) está sem marca e será lido
     * como estorno, abatendo o total. O erro é sempre a favor do usuário — mostra
     * dívida menor, nunca maior — e some na sincronização em que a marca chegar.
     */
    private CardInvoicesResponse.Invoice toInvoice(Cycle cycle, List<BankTransaction> inCycle,
                                                   LocalDate today,
                                                   CardInvoicesResponse.Reserve reserve) {
        BigDecimal purchases = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal payments = BigDecimal.ZERO;
        for (BankTransaction tx : inCycle) {
            if (tx.getAmount().signum() < 0) {
                purchases = purchases.add(tx.getAmount().negate());
            } else if (tx.getAmount().signum() > 0) {
                if (tx.isInternalTransfer()) {
                    payments = payments.add(tx.getAmount());
                } else {
                    refunds = refunds.add(tx.getAmount());
                }
            }
        }
        return new CardInvoicesResponse.Invoice(
                cycle.reference().toString(),
                cycle.start(),
                cycle.end(),
                cycle.end(),
                cycle.dueDate(),
                purchases.subtract(refunds),
                purchases,
                refunds,
                payments,
                inCycle.size(),
                !cycle.end().isBefore(today),
                reserve,
                inCycle.stream().map(BankTransactionResponse::from).toList());
    }

    /**
     * A fatura EM ABERTO mais os {@code months} ciclos fechados anteriores, do
     * mais novo para o mais antigo.
     *
     * <p>Com dia de fechamento conhecido, o ciclo de referência YYYY-MM vai do
     * dia seguinte ao fechamento anterior até o fechamento do mês. Sem ele, o
     * ciclo é o mês do calendário — a aproximação declarada na resposta.
     *
     * <p><b>O ciclo em aberto não consome o orçamento de {@code months}.</b> Ele
     * é o primeiro da lista sempre, porque contém hoje por construção. Se
     * contasse, {@code months=1} devolveria SÓ ele — e no dia seguinte ao
     * fechamento esse ciclo tem um dia de vida e quase nada dentro, então a
     * resposta seria {@code invoices: []} (ciclo vazio é omitido) justamente no
     * dia em que o usuário abre o app para ver a fatura que fechou ontem e ainda
     * vai vencer. Com a regra atual, {@code months=1} devolve a fatura fechada
     * mais recente, que é o que o parâmetro promete.
     *
     * <p>Visível para o pacote de propósito: é aqui que o calendário entra, e
     * travar a virada do fechamento exige um teste com data FIXA — relativo a
     * {@code hoje} o cenário "fechou ontem" não é reproduzível todo dia do ano.
     */
    List<Cycle> buildCycles(Integer closingDay, Integer dueDay, LocalDate today, int months) {
        YearMonth current = YearMonth.from(today);
        if (closingDay != null && closing(current, closingDay).isBefore(today)) {
            // o ciclo deste mês já fechou: a fatura em aberto é a do mês que vem
            current = current.plusMonths(1);
        }
        List<Cycle> cycles = new ArrayList<>();
        for (int i = 0; i <= months; i++) {
            YearMonth reference = current.minusMonths(i);
            LocalDate end = closingDay != null ? closing(reference, closingDay) : reference.atEndOfMonth();
            LocalDate start = closingDay != null
                    ? closing(reference.minusMonths(1), closingDay).plusDays(1)
                    : reference.atDay(1);
            cycles.add(new Cycle(reference, start, end, dueDate(end, dueDay)));
        }
        return cycles;
    }

    /** O dia de fechamento no mês, aparado ao tamanho dele (dia 31 em fevereiro). */
    private LocalDate closing(YearMonth month, int closingDay) {
        return month.atDay(Math.min(closingDay, month.lengthOfMonth()));
    }

    /**
     * O vencimento é a PRÓXIMA ocorrência do dia de vencimento a partir do
     * fechamento — nunca antes dele. Fechou dia 28 e vence dia 5? O vencimento é
     * o dia 5 do mês SEGUINTE, e não um vencimento no passado.
     */
    private LocalDate dueDate(LocalDate closing, Integer dueDay) {
        if (dueDay == null) return null;
        YearMonth month = YearMonth.from(closing);
        LocalDate candidate = month.atDay(Math.min(dueDay, month.lengthOfMonth()));
        if (!candidate.isAfter(closing)) {
            YearMonth next = month.plusMonths(1);
            candidate = next.atDay(Math.min(dueDay, next.lengthOfMonth()));
        }
        return candidate;
    }

    private CardInvoicesResponse empty(ConnectorAccount account, CardInvoicesResponse.CycleSource source) {
        return new CardInvoicesResponse(account.getId(), account.getName(), account.getType(),
                account.getInstitution(), source, List.of());
    }

    private static LocalDate utcDay(OffsetDateTime date) {
        return date.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    /** Ciclo já recortado: referência, extremos inclusivos e vencimento. */
    record Cycle(YearMonth reference, LocalDate start, LocalDate end, LocalDate dueDate) {
        boolean contains(LocalDate day) {
            return !day.isBefore(start) && !day.isAfter(end);
        }
    }
}
