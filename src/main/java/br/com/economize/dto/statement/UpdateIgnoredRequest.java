package br.com.economize.dto.statement;

import jakarta.validation.constraints.NotNull;

/**
 * A linha deve sair das somas, ou voltar para elas?
 *
 * <p>{@code NotNull} pelo mesmo motivo do {@code UpdateInternalTransferRequest}:
 * corpo sem o campo significaria "não sei", e não há default honesto — ignorar
 * por omissão esconderia dinheiro, e não ignorar por omissão manteria a
 * duplicata.
 */
public record UpdateIgnoredRequest(
        @NotNull Boolean ignored
) {
}
