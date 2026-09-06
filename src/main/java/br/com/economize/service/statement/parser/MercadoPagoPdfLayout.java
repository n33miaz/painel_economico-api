package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O extrato do Mercado Pago em PDF.
 *
 * <p><b>Por que um leitor só dele.</b> O Mercado Pago não exporta OFX nem CSV:
 * PDF é o único formato que o cliente consegue baixar. O leitor genérico de PDF
 * (que reaproveita o de TXT) não dá conta do layout porque a DESCRIÇÃO QUEBRA
 * EM TRÊS LINHAS em volta da linha do lançamento:
 *
 * <pre>
 *   Pagamento com QR Pix                            &lt;- começo da descrição
 *   01-08-2026 SUPERMERCADOS 170691932753 R$ -194,99 R$ 905,48
 *   SEROPEDICA LTDA                                 &lt;- fim da descrição
 * </pre>
 *
 * <p><b>O problema difícil, e a regra que o resolve.</b> Entre dois lançamentos
 * pode sobrar uma linha só — e ela tanto pode ser o FIM da descrição de cima
 * quanto o COMEÇO da de baixo. Ler errado troca o estabelecimento de dono, que é
 * exatamente o dado que a categorização usa.
 *
 * <p>O que desempata é uma propriedade do próprio documento: toda descrição
 * COMEÇA por um tipo de operação do vocabulário do Mercado Pago ("Pix enviado",
 * "Rendimentos", "Pagamento…"). Então, ao chegar num lançamento, pergunta-se se
 * o texto que veio na linha dele já começa por um desses tipos:
 *
 * <ul>
 *   <li><b>Começa</b> — a descrição está completa ali; a sobra pendente é o fim
 *       da descrição ANTERIOR.</li>
 *   <li><b>Não começa</b> (ou está vazio) — falta o começo, e ele é a ÚLTIMA
 *       linha da sobra pendente; o que vier antes dela é o fim da anterior.</li>
 * </ul>
 *
 * <p>Vocabulário desconhecido cai no caso "não começa", que é o erro seguro:
 * junta-se texto demais numa descrição em vez de atribuí-lo à pessoa errada.
 *
 * <p>O id da operação vira o {@code externalId}: é ele que torna a importação
 * idempotente quando o mesmo mês é enviado duas vezes.
 */
@Slf4j
final class MercadoPagoPdfLayout {

    /** Assinaturas do documento — sem as duas, este leitor nem tenta. */
    private static final String MARCA_TITULO = "DETALHE DOS MOVIMENTOS";
    private static final String MARCA_EMISSOR = "Mercado Pago";

    /**
     * A linha do lançamento: data, texto do meio, id da operação e os dois
     * valores (o do lançamento e o saldo depois dele).
     *
     * <p>O id tem 11 dígitos ou mais em toda amostra real; exigir o tamanho
     * evita que um número solto dentro da descrição seja confundido com ele.
     */
    private static final Pattern LINHA = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4})\\s+(.*?)\\s*(\\d{11,})\\s+"
                    + "R\\$\\s*(-?[\\d.]+,\\d{2})\\s+R\\$\\s*(-?[\\d.]+,\\d{2})\\s*$");

    /** Cabeçalho que se repete a cada página, e o rodapé de paginação. */
    private static final Pattern RUIDO = Pattern.compile(
            "^(Data\\s+Descri.*|\\d+/\\d+|EXTRATO DE CONTA|DETALHE DOS MOVIMENTOS)$");

    /**
     * Como toda descrição começa. É o vocabulário do próprio emissor —
     * fechado, curto e estável —, e foi conferido contra sete extratos reais
     * (seis meses de duas contas). Palavra desconhecida não quebra nada: cai no
     * caminho "falta o começo", que só arrisca juntar texto a mais.
     */
    private static final Set<String> INICIOS_DE_DESCRICAO = Set.of(
            "rendimentos", "pix", "pagamento", "dinheiro", "reembolso",
            "transferencia", "transferência", "saque", "deposito", "depósito",
            "compra", "estorno", "cobranca", "cobrança", "tarifa",
            "debito", "débito", "credito", "crédito", "recarga", "resgate",
            "aplicacao", "aplicação", "taxa", "antecipacao", "antecipação");

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private MercadoPagoPdfLayout() {
    }

    /** O texto extraído é de um extrato do Mercado Pago? */
    static boolean reconhece(String texto) {
        return texto.contains(MARCA_TITULO) && texto.contains(MARCA_EMISSOR);
    }

    static List<ParsedTransaction> parse(String texto) {
        List<Lancamento> lancamentos = new ArrayList<>();
        List<String> sobrasPendentes = new ArrayList<>();

        for (String bruta : texto.split("\\R")) {
            String linha = bruta.trim();
            if (linha.isEmpty() || RUIDO.matcher(linha).matches()) continue;

            Matcher m = LINHA.matcher(linha);
            if (!m.matches()) {
                if (ehSobraDeDescricao(linha)) sobrasPendentes.add(linha);
                // linha que não é lançamento nem sobra (resumo, rodapé legal)
                // encerra a costura: o que estava pendente não pertence a ninguém
                else sobrasPendentes.clear();
                continue;
            }

            String meio = m.group(2).trim();
            Lancamento atual = new Lancamento(m.group(1), meio, m.group(3), m.group(4));

            if (comecaDescricao(meio)) {
                // A descrição já está completa nesta linha: tudo o que estava
                // pendente é o FIM da descrição anterior
                anexarAoAnterior(lancamentos, sobrasPendentes);
            } else {
                // Falta o começo, e ele é a última sobra; o resto fecha a anterior
                if (!sobrasPendentes.isEmpty()) {
                    atual.inicio = sobrasPendentes.remove(sobrasPendentes.size() - 1);
                }
                anexarAoAnterior(lancamentos, sobrasPendentes);
            }
            sobrasPendentes.clear();
            lancamentos.add(atual);
        }
        // O que sobrou no fim fecha o último lançamento
        anexarAoAnterior(lancamentos, sobrasPendentes);

        List<ParsedTransaction> saida = new ArrayList<>(lancamentos.size());
        for (Lancamento l : lancamentos) {
            BigDecimal valor = valorDe(l.valor);
            if (valor == null) continue;
            saida.add(ParsedTransaction.builder()
                    // O id da operação é único no Mercado Pago e é o que torna
                    // reenviar o mesmo mês uma operação sem efeito
                    .externalId("MP-" + l.id)
                    .type(valor.signum() < 0 ? "DEBIT" : "CREDIT")
                    .amount(valor)
                    .description(l.descricao())
                    .date(LocalDate.parse(l.data, DATA).atStartOfDay().atOffset(ZoneOffset.UTC))
                    .build());
        }
        log.info("Extrato do Mercado Pago: {} lançamento(s) lidos", saida.size());
        return saida;
    }

    private static void anexarAoAnterior(List<Lancamento> lancamentos, List<String> sobras) {
        if (sobras.isEmpty() || lancamentos.isEmpty()) return;
        Lancamento anterior = lancamentos.get(lancamentos.size() - 1);
        anterior.fim.addAll(sobras);
    }

    private static boolean comecaDescricao(String meio) {
        if (meio.isBlank()) return false;
        String primeira = meio.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return INICIOS_DE_DESCRICAO.contains(primeira);
    }

    /**
     * A linha é pedaço de descrição quebrada? Sobra de descrição não tem data,
     * não tem valor e não é longa — o rodapé legal do documento é.
     */
    private static boolean ehSobraDeDescricao(String linha) {
        if (linha.contains("R$")) return false;
        if (linha.matches("^\\d{2}-\\d{2}-\\d{4}.*")) return false;
        return linha.length() <= 60;
    }

    private static BigDecimal valorDe(String bruto) {
        try {
            return new BigDecimal(bruto.replace(".", "").replace(",", "."));
        } catch (NumberFormatException e) {
            // valor ilegível é linha que não é lançamento: pular é o correto,
            // e derrubar o arquivo inteiro por causa dela seria desproporcional
            return null;
        }
    }

    /** Um lançamento em montagem: o meio veio na linha, começo e fim são sobras. */
    private static final class Lancamento {
        private final String data;
        private final String meio;
        private final String id;
        private final String valor;
        private String inicio;
        private final List<String> fim = new ArrayList<>(2);

        private Lancamento(String data, String meio, String id, String valor) {
            this.data = data;
            this.meio = meio;
            this.id = id;
            this.valor = valor;
        }

        private String descricao() {
            StringBuilder texto = new StringBuilder();
            if (inicio != null) texto.append(inicio).append(' ');
            texto.append(meio);
            for (String parte : fim) texto.append(' ').append(parte);
            return texto.toString().replaceAll("\\s+", " ").trim();
        }
    }
}
