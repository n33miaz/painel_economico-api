package br.com.economize.dto.statement;

import jakarta.validation.constraints.NotNull;

/**
 * A pessoa dizendo se esta linha é dinheiro que ficou dentro da casa (V28).
 *
 * <p>Objeto com um campo, e não um booleano solto no corpo, pela mesma razão de
 * {@code UpdateInternalTransferRequest}: um dia esta marca vai querer dizer
 * TAMBEM contra quem, e aí o contrato cresce sem quebrar quem já chama.
 */
public record UpdateFamilyTransferRequest(
        @NotNull(message = "Informe se a linha é transferência dentro da casa")
        Boolean familyTransfer
) {
}
