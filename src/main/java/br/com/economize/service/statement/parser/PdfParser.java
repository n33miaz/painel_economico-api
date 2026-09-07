package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Component
public class PdfParser implements StatementParserStrategy {

    private final TxtParser txtParser;

    public PdfParser(TxtParser txtParser) {
        this.txtParser = txtParser;
    }

    @Override
    public StatementFormat format() {
        return StatementFormat.PDF;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        try (PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Ordenar por posição é o que mantém a linha do lançamento inteira:
            // sem isto, num layout de colunas o texto sai na ordem em que foi
            // desenhado, e data, valor e descrição chegam embaralhados
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            // O Mercado Pago não exporta OFX nem CSV — PDF é o único formato que
            // o cliente consegue baixar. O layout dele quebra a descrição em três
            // linhas e o leitor genérico não dá conta; ver MercadoPagoPdfLayout.
            if (MercadoPagoPdfLayout.reconhece(text)) {
                List<ParsedTransaction> mercadoPago = MercadoPagoPdfLayout.parse(text);
                if (!mercadoPago.isEmpty()) return mercadoPago;
                // Extrato de mês parado: o documento foi lido inteiro e diz, ele
                // mesmo, que não houve movimento. Mandar exportar em OFX aqui é
                // culpar o arquivo por um mês em que nada aconteceu, e faz o
                // usuário procurar defeito onde não há.
                if (MercadoPagoPdfLayout.semMovimento(text)) {
                    throw new IllegalArgumentException(
                            "Este extrato não tem lançamentos no período — "
                                    + "o próprio arquivo informa entradas e saídas zeradas");
                }
                log.warn("PDF reconhecido como Mercado Pago, mas sem lançamentos legíveis — "
                        + "seguindo pelo leitor genérico");
            }

            List<ParsedTransaction> raw = txtParser.parse(
                    new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            // Layout de PDF raramente casa com o regex de linha do TXT: textos de
            // cabeçalho viram "transações" de descrição vazia (testado com extratos
            // reais — o período "12-08-2024 a 11-08-2026" virava duas linhas lixo).
            // Só passam adiante linhas com descrição minimamente identificável.
            List<ParsedTransaction> plausible = raw.stream()
                    .filter(tx -> countLetters(tx.getDescription()) >= 3)
                    .toList();
            int dropped = raw.size() - plausible.size();
            if (dropped > 0) {
                log.warn("PDF: {} linhas implausíveis descartadas de {}", dropped, raw.size());
            }
            if (plausible.isEmpty()) {
                throw new IllegalArgumentException(
                        "Não foi possível extrair transações confiáveis do PDF — "
                                + "exporte o extrato do banco em OFX (recomendado) ou CSV");
            }
            return plausible;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler PDF", e);
        }
    }

    private int countLetters(String value) {
        if (value == null) return 0;
        int letters = 0;
        for (char c : value.toCharArray()) {
            if (Character.isLetter(c)) letters++;
        }
        return letters;
    }
}
