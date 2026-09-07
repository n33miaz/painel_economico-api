package br.com.economize.dto.investment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Os corpos de entrada do módulo de investimentos.
 *
 * <p>Enums chegam como texto e são validados no service — enum no record
 * derrubaria a desserialização inteira com 500 em vez de responder 400 dizendo
 * qual valor era esperado (mesma decisão de {@code WishRequests}).
 */
public final class InvestmentRequests {

    private InvestmentRequests() {
    }

    /**
     * Cadastro MANUAL de posição — o caminho para o que nenhum conector
     * alcança (ETF em corretora no exterior, CDB de banco fora do Open
     * Finance). Só nome e tipo são obrigatórios: quem tem a ETF sabe a
     * quantidade, mas não necessariamente quanto pagou; quem tem o CDB sabe
     * quanto aplicou, mas não a cotação. Valor atual ausente NÃO vira zero —
     * a resposta declara que falta cotação.
     */
    public record CreatePosition(
            @NotBlank(message = "Nome da posição é obrigatório")
            @Size(max = 160, message = "Nome deve ter no máximo 160 caracteres")
            String name,

            @NotBlank(message = "Tipo é obrigatório")
            String type,

            @Size(max = 32, message = "Subtipo deve ter no máximo 32 caracteres")
            String subtype,

            String indexer,

            @Size(max = 40, message = "Taxa deve ter no máximo 40 caracteres")
            String rate,

            @Size(max = 32, message = "Código deve ter no máximo 32 caracteres")
            String code,

            @Size(max = 160, message = "Instituição deve ter no máximo 160 caracteres")
            String institution,

            @Size(min = 3, max = 3, message = "Moeda deve ser um código ISO de 3 letras")
            String currency,

            @DecimalMin(value = "0", message = "Quantidade não pode ser negativa")
            @Digits(integer = 11, fraction = 8, message = "Quantidade fora da faixa aceita")
            BigDecimal quantity,

            @DecimalMin(value = "0", message = "Preço unitário não pode ser negativo")
            @Digits(integer = 13, fraction = 6, message = "Preço unitário fora da faixa aceita")
            BigDecimal unitPrice,

            @DecimalMin(value = "0", message = "Valor aplicado não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Valor aplicado fora da faixa aceita")
            BigDecimal investedAmount,

            @DecimalMin(value = "0", message = "Valor atual não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Valor atual fora da faixa aceita")
            BigDecimal currentValue,

            LocalDate maturityDate,

            LocalDate positionDate
    ) {
    }

    /** Todos os campos opcionais: o PATCH altera só o que veio. */
    public record UpdatePosition(
            @Size(max = 160, message = "Nome deve ter no máximo 160 caracteres")
            String name,

            String type,

            @Size(max = 32, message = "Subtipo deve ter no máximo 32 caracteres")
            String subtype,

            String indexer,

            @Size(max = 40, message = "Taxa deve ter no máximo 40 caracteres")
            String rate,

            @Size(max = 32, message = "Código deve ter no máximo 32 caracteres")
            String code,

            @Size(max = 160, message = "Instituição deve ter no máximo 160 caracteres")
            String institution,

            @Size(min = 3, max = 3, message = "Moeda deve ser um código ISO de 3 letras")
            String currency,

            @DecimalMin(value = "0", message = "Quantidade não pode ser negativa")
            @Digits(integer = 11, fraction = 8, message = "Quantidade fora da faixa aceita")
            BigDecimal quantity,

            @DecimalMin(value = "0", message = "Preço unitário não pode ser negativo")
            @Digits(integer = 13, fraction = 6, message = "Preço unitário fora da faixa aceita")
            BigDecimal unitPrice,

            @DecimalMin(value = "0", message = "Valor aplicado não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Valor aplicado fora da faixa aceita")
            BigDecimal investedAmount,

            @DecimalMin(value = "0", message = "Valor atual não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Valor atual fora da faixa aceita")
            BigDecimal currentValue,

            LocalDate maturityDate,

            LocalDate positionDate
    ) {
    }

    /**
     * Um interesse declarado à mão. {@code market} só faz sentido para TICKER
     * (US, BR) e é ignorado nos demais tipos.
     */
    public record CreateInterest(
            @NotBlank(message = "Tipo do interesse é obrigatório")
            String kind,

            @NotBlank(message = "Código do interesse é obrigatório")
            @Size(max = 32, message = "Código deve ter no máximo 32 caracteres")
            String code,

            @Size(max = 8, message = "Mercado deve ter no máximo 8 caracteres")
            String market
    ) {
    }
}
