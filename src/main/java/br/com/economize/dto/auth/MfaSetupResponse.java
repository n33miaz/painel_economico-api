package br.com.economize.dto.auth;

/**
 * O que a tela de cadastro do fator precisa mostrar UMA vez: o QR (a
 * {@code otpauthUri}) e o mesmo segredo em texto, para quem digita à mão porque
 * a câmera não lê o código.
 *
 * <p>Esta é a única resposta da API que carrega o segredo. Depois da
 * confirmação ele não sai mais daqui — pedir o setup de novo GERA outro segredo
 * e invalida o anterior.
 */
public record MfaSetupResponse(String secret, String otpauthUri) {
}
