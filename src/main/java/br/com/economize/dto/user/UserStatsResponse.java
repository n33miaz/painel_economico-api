package br.com.economize.dto.user;

/**
 * Os três contadores do hub do Perfil: linhas de extrato, lançamentos da
 * carteira e relatórios salvos.
 *
 * <p>Existe por causa do custo do que havia antes: a tela baixava as três
 * listas inteiras para desenhar três números — só o extrato são ~100 KB para
 * 1.752 linhas, e o Perfil media 3,6 s. Três {@code count} no banco respondem
 * o mesmo em algumas centenas de bytes, e o cliente não precisa mais carregar
 * dado que ele não vai mostrar.
 */
public record UserStatsResponse(
        long bankTransactions,
        long walletTransactions,
        long reports) {
}
