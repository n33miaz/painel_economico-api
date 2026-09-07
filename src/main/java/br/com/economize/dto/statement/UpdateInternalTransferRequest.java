package br.com.economize.dto.statement;

import jakarta.validation.constraints.NotNull;

/**
 * A decisão do usuário sobre uma linha do extrato: é dinheiro dele trocando de
 * bolso, ou é receita/despesa de verdade?
 *
 * <p>{@code NotNull} de propósito: corpo sem o campo significaria "não sei", e
 * não há default honesto para essa pergunta — quem chama tem de dizer qual dos
 * dois lados quer.
 */
public record UpdateInternalTransferRequest(
        @NotNull Boolean internalTransfer
) {
}
