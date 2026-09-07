package br.com.economize.dto.account;

import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.model.ConnectorAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Faturas de um cartão — EC-113. É o agrupamento que permite ao app abrir "a
 * fatura de agosto" e ver o que a compõe.
 *
 * <p><b>Toda fatura aqui é DERIVADA.</b> A API não recebe do agregador uma
 * fatura fechada com o que entrou nela; ela recorta o extrato do cartão em
 * ciclos e soma. O campo {@link #cycleSource} diz qual regra recortou, e as duas
 * possíveis são derivações — a diferença é o quanto de metadado do provedor
 * entrou na conta.
 */
public record CardInvoicesResponse(
        UUID accountId,
        String accountName,
        ConnectorAccount.AccountType accountType,
        String institution,
        CycleSource cycleSource,
        List<Invoice> invoices
) {

    /** De onde saiu o recorte do ciclo. Ambos são derivação, nunca fatura pronta. */
    public enum CycleSource {
        /**
         * O provedor informou o dia de fechamento da conta e o ciclo foi recortado
         * por ele: de (fechamento anterior + 1 dia) até o fechamento do mês.
         */
        PROVIDER_CLOSING_DAY,
        /**
         * Sem dia de fechamento confiável, o ciclo é o MÊS DO CALENDÁRIO. As
         * compras dos últimos dias do mês provavelmente caem na fatura seguinte
         * no app do banco — a API prefere declarar a aproximação a fingir uma
         * precisão que não tem.
         */
        CALENDAR_MONTH
    }

    /**
     * Um ciclo. {@code reference} é o mês em que o ciclo FECHA (uma fatura que
     * fecha em 10/08 é "2026-08"), e as datas vêm junto justamente para o app
     * poder rotular de outro jeito sem que a API mude de contrato.
     */
    public record Invoice(
            // "2026-08"
            String reference,
            // Primeiro e último dia do ciclo, ambos INCLUSIVOS
            LocalDate periodStart,
            LocalDate periodEnd,
            // Fechamento = periodEnd. Repetido com nome próprio porque é assim
            // que o usuário fala da data, e o app não deveria precisar saber que
            // as duas coincidem.
            LocalDate closingDate,
            // Nulo quando o provedor não informou o dia de vencimento
            LocalDate dueDate,

            // ---------------------------------------------------------------
            // Os três valores do ciclo, todos em POSITIVO e sem sobreposição:
            // total = purchasesTotal - refundsTotal, e paymentsTotal fica fora
            // dessa conta porque não é fatura, é quitação.
            // ---------------------------------------------------------------

            // O QUE O USUÁRIO DEVE por este ciclo: compras menos estornos. É o
            // número que o app mostra como "valor da fatura". Comprou 100 e o
            // lojista estornou 100 no mesmo ciclo? Deve zero — e antes desta
            // conta a API devolvia 100, sem nenhuma forma de o app descobrir
            // que não havia nada a pagar. Pode ser NEGATIVO no caso raro de os
            // estornos superarem as compras do ciclo: aí é saldo a favor do
            // usuário, e arredondar para zero esconderia o crédito.
            BigDecimal total,
            // O BRUTO de compras do ciclo, sem desconto nenhum — para o app que
            // quiser mostrar "gastou X, estornou Y, deve Z".
            BigDecimal purchasesTotal,
            // Estornos do ciclo: crédito que entrou no cartão e NÃO é perna de
            // movimentação entre contas do titular. É devolução de compra, então
            // abate a fatura.
            BigDecimal refundsTotal,
            // PAGAMENTO DE FATURA: crédito marcado como perna interna (EC-106/V15
            // — o outro lado é o débito na conta corrente). Nunca é receita e
            // NUNCA abate o total: o total é o que este ciclo gerou de dívida, e
            // o pagamento normalmente quita o ciclo ANTERIOR. Está aqui só para
            // o app poder mostrar "quanto já entrou no cartão neste período".
            BigDecimal paymentsTotal,
            int transactionCount,
            // O ciclo ainda não fechou — é a fatura em aberto
            boolean open,
            // A reserva deste ciclo (EC-181), ou nulo quando o dono não separou
            // nada para esta fatura. Vem DENTRO da fatura porque é sobre ela que
            // a reserva fala: separar dinheiro "para o cartão" sem dizer para qual
            // ciclo não responde à pergunta que a tela faz — "esta aqui está
            // coberta?". Quem compara reserva e total é a leitura: o valor pode ser
            // menor (cobre em parte) ou maior (a fatura ainda vai crescer)
            Reserve reserve,
            List<BankTransactionResponse> transactions
    ) {
    }

    /**
     * Dinheiro já separado para pagar esta fatura — EC-181.
     *
     * <p>Não é lançamento e não entra em soma nenhuma: nada saiu da conta. É a
     * declaração de que parte do saldo tem destino certo, para o app poder
     * mostrar "a de setembro já está coberta" sem falsificar o extrato.
     *
     * <p>{@code heldInAccountId} e {@code heldInAccountName} são nulos quando o
     * dono separou fora do que o sistema enxerga, ou quando a conta que
     * guardava o valor foi desconectada depois.
     */
    public record Reserve(
            UUID id,
            BigDecimal amount,
            UUID heldInAccountId,
            String heldInAccountName,
            String note
    ) {
    }
}
