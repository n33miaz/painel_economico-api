package br.com.economize.dto.account;

import br.com.economize.model.ConnectorAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Uma origem criada À MÃO, para quem importa extrato em arquivo.
 *
 * <p>Antes, origem só nascia de conexão do Pluggy — e quem envia OFX ou PDF
 * ficava com todos os lançamentos num monte só, sem saber qual banco pagou o
 * quê. Duas contas correntes e um cartão viravam uma lista indistinta.
 *
 * <p>Os dias de fechamento e vencimento existem só para cartão, e são os
 * mesmos do EC-113: sem eles a fatura é recortada pelo mês do calendário, e a
 * resposta da fatura declara que foi assim.
 */
public record CreateAccountRequest(
        @Schema(description = "Como a conta aparece na tela", example = "Nubank ····0777")
        @NotBlank(message = "Dê um nome à conta")
        @Size(max = 120, message = "O nome não pode passar de 120 caracteres")
        String name,

        @Schema(description = "Instituição", example = "Nubank")
        @Size(max = 160, message = "A instituição não pode passar de 160 caracteres")
        String institution,

        @NotNull(message = "Informe se é conta ou cartão")
        ConnectorAccount.AccountType type,

        @Schema(description = "Dia do fechamento da fatura (1 a 31), só para cartão")
        Integer statementClosingDay,

        @Schema(description = "Dia do vencimento da fatura (1 a 31), só para cartão")
        Integer statementDueDay) {
}
