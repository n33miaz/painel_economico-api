package br.com.economize.dto.investment;

import br.com.economize.model.InvestmentInterest;
import br.com.economize.model.InvestmentPosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * As respostas do módulo de investimentos.
 *
 * <p>Campo nulo aqui quer dizer <b>"não sei"</b>, nunca zero — a mesma regra de
 * {@code WishResponses}. A posição manual sem cotação vem com
 * {@code currentValue} nulo e {@code needsQuote} verdadeiro, e o app completa
 * com o indicador ao vivo; inventar um zero faria "R$ 0,00 na ETF" parecer um
 * prejuízo total.
 */
public final class InvestmentResponses {

    private InvestmentResponses() {
    }

    /** Rótulo em pt-BR de cada tipo, para o app não manter a tabela em dobro. */
    public static String typeLabel(InvestmentPosition.Type type) {
        if (type == null) return "Outros";
        return switch (type) {
            case FIXED_INCOME -> "Renda fixa";
            case TREASURY -> "Tesouro Direto";
            case FUND -> "Fundos";
            case EQUITY -> "Ações";
            case ETF -> "ETFs";
            case CRYPTO -> "Cripto";
            case PENSION -> "Previdência";
            case OTHER -> "Outros";
        };
    }

    /**
     * Uma posição como o app a mostra.
     *
     * @param stale      a posição não é atualizada há tempo demais (sumiu do
     *                   provedor ou a conexão foi desvinculada); nunca vale
     *                   para a manual, cujo dado é o que o usuário digitou
     * @param needsQuote sem valor atual e com código: o app busca a cotação
     * @param editable   só a manual aceita PATCH; a do conector é substituída
     *                   pelo sync e editar seria perder a edição na próxima
     */
    public record PositionItem(
            UUID id,
            String source,
            UUID pluggyItemId,
            UUID accountId,
            String institution,
            String name,
            String code,
            String type,
            String typeLabel,
            String subtype,
            String indexer,
            String rate,
            String currency,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal investedAmount,
            BigDecimal currentValue,
            BigDecimal profit,
            LocalDate maturityDate,
            LocalDate positionDate,
            boolean stale,
            boolean needsQuote,
            boolean editable,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static PositionItem from(InvestmentPosition p, boolean stale) {
            BigDecimal profit = p.getCurrentValue() != null && p.getInvestedAmount() != null
                    ? p.getCurrentValue().subtract(p.getInvestedAmount()) : null;
            return new PositionItem(
                    p.getId(),
                    p.getSource().name(),
                    p.getPluggyItemId(),
                    p.getAccountId(),
                    p.getInstitution(),
                    p.getName(),
                    p.getCode(),
                    p.getType().name(),
                    // qualificado: dentro do record, o nome simples resolve
                    // para o acessor do componente homônimo
                    InvestmentResponses.typeLabel(p.getType()),
                    p.getSubtype(),
                    p.getIndexer() != null ? p.getIndexer().name() : null,
                    p.getRate(),
                    p.getCurrency(),
                    p.getQuantity(),
                    p.getUnitPrice(),
                    p.getInvestedAmount(),
                    p.getCurrentValue(),
                    profit,
                    p.getMaturityDate(),
                    p.getPositionDate(),
                    stale,
                    p.getCurrentValue() == null && p.getCode() != null,
                    p.isManual(),
                    p.getCreatedAt(),
                    p.getUpdatedAt());
        }
    }

    /** Fatia do resumo por tipo. {@code share} é fração de 0 a 1 do valor atual conhecido. */
    public record TypeSlice(String type, String label, BigDecimal currentValue, BigDecimal share) {
    }

    public record InstitutionSlice(String institution, BigDecimal currentValue, BigDecimal share) {
    }

    public record IndexerSlice(String indexer, BigDecimal currentValue, BigDecimal share) {
    }

    /** Os movimentos dos últimos 12 meses, na forma resumida do painel. */
    public record MovementTotals12m(BigDecimal applied, BigDecimal redeemed, BigDecimal yield, BigDecimal net) {
    }

    /**
     * O painel.
     *
     * @param totalInvested   soma do aplicado em TODAS as posições que o informam
     * @param currentValue    soma do valor atual das posições que o TÊM — as sem
     *                        cotação ficam fora, e {@code needsQuote} diz quais
     * @param profit          valor atual − aplicado, só nas posições com os dois;
     *                        nulo quando nenhuma tem
     * @param profitPercent   idem, em % sobre o aplicado dessas posições
     * @param pricedPositions quantas posições entraram em {@code currentValue}
     * @param stalePositions  quantas estão desatualizadas (ver PositionItem)
     * @param sources         origens presentes: CONNECTOR, STATEMENT, MANUAL
     * @param needsQuote      códigos das posições sem valor atual — o app busca
     *                        a cotação e completa o total do lado dele
     */
    public record Summary(
            BigDecimal totalInvested,
            BigDecimal currentValue,
            BigDecimal profit,
            BigDecimal profitPercent,
            int positionsCount,
            int pricedPositions,
            List<TypeSlice> byType,
            List<InstitutionSlice> byInstitution,
            List<IndexerSlice> byIndexer,
            OffsetDateTime updatedAt,
            int stalePositions,
            List<String> sources,
            MovementTotals12m movements12m,
            List<String> needsQuote
    ) {
    }

    /**
     * Um lançamento do extrato lido como movimento de investimento.
     *
     * @param kind   APPLY (dinheiro indo para o investimento), REDEEM (voltando),
     *               YIELD (rendimento creditado) ou OTHER (ajuste, IR, estorno)
     * @param amount com o SINAL do extrato: negativo saiu da conta, positivo
     *               entrou — a mesma convenção da listagem de transações
     */
    public record MovementRow(
            UUID transactionId,
            LocalDate date,
            String kind,
            BigDecimal amount,
            String description,
            String institution,
            UUID accountId
    ) {
    }

    /** Totais em valor ABSOLUTO por tipo de movimento na janela. */
    public record MovementTotals(BigDecimal applied, BigDecimal redeemed, BigDecimal yield, BigDecimal other) {
    }

    /**
     * Os movimentos de investimento derivados do extrato numa janela de meses
     * de calendário (o mês atual conta como um).
     *
     * @param netInvested aplicado − resgatado na janela: quanto de dinheiro
     *                    novo foi para os investimentos
     */
    public record Movements(
            int months,
            LocalDate from,
            LocalDate to,
            List<MovementRow> movements,
            MovementTotals totals,
            BigDecimal netInvested
    ) {
    }

    /**
     * O que a sincronização fez.
     *
     * @param synced           posições processadas (criadas + atualizadas)
     * @param itemsRead        conexões consultadas com sucesso
     * @param skippedItems     conexões cuja consulta falhou no provedor — as
     *                         demais seguiram; nada delas foi apagado
     * @param skippedPositions posições sem id na resposta, ignoradas
     */
    public record SyncResult(int synced, int created, int updated, int itemsRead, int skippedItems,
                             int skippedPositions) {
    }

    /**
     * Um item a acompanhar. {@code source} diz de onde veio: DERIVED (das
     * posições/movimentos — não se remove, some quando a posição sumir) ou
     * MANUAL (o usuário pediu — e pode desdizer em DELETE /interests).
     */
    public record WatchItem(String kind, String code, String market, String source) {
    }

    /**
     * Por que o perfil é o que é, em frases curtas para a tela "por que vejo
     * isto". {@code note} só vem preenchido no perfil padrão.
     */
    public record DerivedFrom(List<String> positions, List<String> movements, List<String> manualInterests,
                              String note) {
    }

    /**
     * A personalização: o que este usuário acompanha, derivado do que ele TEM.
     *
     * @param indexers  indicadores a mostrar em destaque (CDI, SELIC, IPCA, USD…)
     * @param topics    ids do vocabulário fixo de notícias
     * @param isDefault nada foi derivado nem declarado: perfil genérico
     */
    public record Profile(
            List<String> indexers,
            List<WatchItem> watch,
            List<String> topics,
            DerivedFrom derivedFrom,
            boolean isDefault
    ) {
    }

    public record InterestItem(UUID id, String kind, String code, String market, OffsetDateTime createdAt) {
        public static InterestItem from(InvestmentInterest interest) {
            return new InterestItem(interest.getId(), interest.getKind().name(), interest.getCode(),
                    interest.getMarket(), interest.getCreatedAt());
        }
    }
}
