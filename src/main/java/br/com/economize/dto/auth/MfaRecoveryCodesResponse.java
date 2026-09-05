package br.com.economize.dto.auth;

import java.util.List;

/**
 * Os códigos de recuperação, em claro — a ÚNICA vez que eles aparecem. No banco
 * ficam só os hashes; nem o servidor consegue mostrá-los de novo.
 */
public record MfaRecoveryCodesResponse(List<String> codes) {
}
