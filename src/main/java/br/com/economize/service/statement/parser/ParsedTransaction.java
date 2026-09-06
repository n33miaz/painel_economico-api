package br.com.economize.service.statement.parser;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Value
// toBuilder porque a linha e IMUTAVEL e mesmo assim precisa ganhar a origem
// depois de lida: quem sabe de qual conta o arquivo veio e o upload, nao o
// parser. Copiar com a origem preenchida mantem a imutabilidade de pe.
@Builder(toBuilder = true)
public class ParsedTransaction {
    String externalId;
    String type; // CREDIT, DEBIT
    BigDecimal amount;
    String description;
    OffsetDateTime date;

    // Perna de movimentação entre contas do próprio titular (EC-106): pagamento
    // de fatura de cartão, dos dois lados. Só os conectores sabem disso na
    // importação (é o tipo da conta de origem que denuncia); os parsers de
    // arquivo deixam no default false, e por isso o campo não é obrigatório em
    // nenhum builder já escrito.
    boolean internalTransfer;

    // Origem do lançamento (EC-113): id da ConnectorAccount de onde ele veio.
    // Os PARSERS nunca sabem disso — o arquivo não diz a qual conta do usuário
    // pertence —, então eles deixam nulo e o campo não é obrigatório em builder
    // nenhum. Quem preenche é quem tem a informação: o conector, que resolveu a
    // conta antes de puxar as transações, ou o upload, quando o usuário diz a
    // qual conta o arquivo pertence (?accountId=).
    java.util.UUID accountId;
}
