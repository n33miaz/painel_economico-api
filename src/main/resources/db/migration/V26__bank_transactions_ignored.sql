-- LANÇAMENTO IGNORADO — a duplicata que entrou por duas portas, e o que fazer
-- com ela sem apagar histórico.
--
-- O problema, medido no extrato real do dono em 07/09/2026: 18 lançamentos
-- entraram DUAS vezes, R$ 4.855 de um lado. A assinatura é sempre a mesma —
-- mesmo valor, um dia de diferença, um lado com account_id (veio da conexão
-- bancária) e outro sem (veio de um arquivo importado), com o texto escrito de
-- outro jeito. A reconciliação entre fontes não junta os dois porque a data
-- difere em um dia e a descrição não bate string a string.
--
-- POR QUE MARCAR E NÃO APAGAR. O dono pediu para "excluir os duplicados", e
-- apagar de verdade seria pior para ele:
--   1. É irreversível, e o critério de duplicata é heurístico. Dois Pix de
--      R$ 125 no mesmo dia para pessoas diferentes são legítimos; dois
--      "Resgate CDB" idênticos podem ser dois resgates de verdade.
--   2. Reimportar NÃO desfaz o erro: o upload é idempotente por hash do
--      arquivo, então o arquivo já registrado não grava nada de novo. Uma linha
--      apagada por engano estaria perdida para sempre.
--   3. O pedido junto era "mas não pode faltar nada". Marcar entrega o número
--      certo E mantém a linha auditável — some das somas, continua no extrato
--      com um selo, e um toque a traz de volta.
--
-- ignored: fora de toda soma (análise, previsão, casa), como internal_transfer
-- já faz. As duas marcas coexistem de propósito e dizem coisas diferentes:
-- internal_transfer é "isto é meu dinheiro trocando de bolso" (o movimento
-- existiu); ignored é "esta linha não deveria existir".
--
-- ignored_reason: DUPLICATE (a varredura achou) ou USER (a pessoa decidiu). A
-- distinção importa porque a varredura pode rodar de novo, e o que o usuário
-- decidiu à mão nunca é desfeito por ela.
ALTER TABLE bank_transactions ADD COLUMN ignored BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bank_transactions ADD COLUMN ignored_reason VARCHAR(16);

-- Índice parcial: a esmagadora maioria das linhas nunca é ignorada, e as
-- consultas de soma filtram por "ignored = false". Indexar só as ignoradas
-- mantém o índice minúsculo e serve à tela que lista o que foi descartado.
CREATE INDEX idx_bank_tx_ignored ON bank_transactions (user_id)
    WHERE ignored = TRUE;
