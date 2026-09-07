package br.com.economize.dto.statement;

import br.com.economize.model.BankTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BankTransactionResponse(
        UUID id,
        String transactionId,
        String type,
        BigDecimal amount,
        /*
         * O que a tela mostra: apelido quando existe, senão o descritivo do
         * banco (EC-094). O campo continua sendo este para que TODA listagem já
         * escrita passe a respeitar o apelido sem mudar de contrato.
         */
        String description,
        // O descritivo cru do banco, sempre presente — é ele que o modal de
        // detalhes exibe ao lado do apelido, e sem ele o usuário perderia a
        // referência para conferir o lançamento no app do banco
        String originalDescription,
        // Nulo quando não há apelido: é o que diz ao app se o campo "description"
        // veio renomeado e pré-preenche o formulário de edição
        String displayAlias,
        OffsetDateTime date,
        UUID categoryId,
        BankTransaction.ReviewStatus reviewStatus,
        BankTransaction.CategorizedBy categorizedBy,
        BigDecimal confidence,
        String normalizedDescription,
        UUID uploadId,
        /*
         * EC-113: a ORIGEM da linha — de qual conta bancária ou cartão de crédito
         * ela veio. Só o id: nome, instituição e tipo saem de
         * GET /api/v1/accounts, que o app carrega uma vez e casa em memória em
         * vez de receber os mesmos rótulos repetidos em cada linha do extrato.
         *
         * NULO é um valor legítimo e permanente, não um erro: é o histórico
         * anterior à V16 e é todo lançamento de upload manual de arquivo, que não
         * tem conta de provedor. O app deve apresentá-lo como "origem não
         * informada". Campo somado ao contrato, nenhum removido.
         */
        UUID accountId,
        // EC-106: perna de movimentação entre contas do próprio titular
        // (pagamento de fatura, dos dois lados). A lista continua mostrando o
        // lançamento com o sinal real — este campo diz ao app para NÃO
        // apresentá-lo como receita/despesa do mês, que é como ele entra nos
        // totais. Campo somado ao contrato, nenhum removido.
        boolean internalTransfer,
        // V26: a linha entrou duas vezes (pela conexão e por um arquivo) ou o
        // usuário a descartou. Sai de toda soma e continua no extrato com selo.
        // Sem este campo o app marcava a linha e não tinha como desenhar a marca
        boolean ignored,
        // V28: dinheiro que ficou dentro da casa (Pix entre o casal, mesada).
        // Sai SÓ da soma da Casa — na análise pessoal do dono da linha o dinheiro
        // entrou mesmo, e escondê-lo dele seria mentir sobre o extrato
        boolean familyTransfer
) {
    public static BankTransactionResponse from(BankTransaction tx) {
        return new BankTransactionResponse(
                tx.getId(),
                tx.getTransactionId(),
                tx.getType(),
                tx.getAmount(),
                tx.displayDescription(),
                tx.getDescription(),
                tx.getDisplayAlias(),
                tx.getDate(),
                tx.getCategoryId(),
                tx.getReviewStatus(),
                tx.getCategorizedBy(),
                tx.getConfidence(),
                // chave normalizada do MOTOR: derivada do descritivo do banco,
                // nunca do apelido
                tx.getNormalizedDescription(),
                tx.getUploadId(),
                tx.getAccountId(),
                tx.isInternalTransfer(),
                tx.isIgnored(),
                tx.isFamilyTransfer());
    }
}
