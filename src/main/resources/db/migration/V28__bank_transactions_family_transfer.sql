-- TRANSFERÊNCIA ENTRE MEMBROS DA CASA — o dinheiro que muda de bolso dentro da
-- própria família e estava sendo contado duas vezes.
--
-- O problema, medido na produção em 07/09/2026 depois do EC-187: a Casa de
-- agosto somava R$ 7.058,28 de receita para um casal cuja renda real do mês era
-- R$ 6.021,17. A diferença — R$ 1.037,11 — são dois Pix do marido para a esposa
-- (R$ 650,00 e R$ 387,11): saem como despesa dele e entram como receita dela, e
-- a casa lê os dois lados como se fossem dinheiro novo entrando duas vezes.
--
-- POR QUE NÃO SERVE O internal_transfer (V15/EC-187). Aquela marca diz "é o meu
-- dinheiro trocando do meu bolso esquerdo para o meu bolso direito" e vale para
-- contas do MESMO titular. Aqui os titulares são duas pessoas diferentes, e a
-- leitura muda com o ponto de vista:
--   * Na tela "Eu" da esposa, aquilo É receita. O dinheiro entrou na conta dela,
--     ela pode gastar, e apagar isso da visão pessoal dela seria mentir sobre o
--     extrato que ela tem na mão.
--   * Na tela da Casa, NÃO é. A renda da casa é o que entra de fora dela; o que
--     circula entre os dois já foi contado quando entrou.
-- Usar internal_transfer resolveria a Casa quebrando a visão pessoal. Por isso a
-- marca é outra, e só as consultas da CASA a filtram.
--
-- MARCA E NÃO APAGA, como a V26: o lançamento continua no extrato, continua na
-- análise pessoal do dono da linha, e some apenas da soma da casa.
ALTER TABLE bank_transactions ADD COLUMN family_transfer BOOLEAN NOT NULL DEFAULT FALSE;

-- Índice parcial pelo mesmo motivo do idx_bank_tx_ignored: a marca atinge uma
-- minoria das linhas, e o que se quer indexar é justamente essa minoria — a
-- tela que mostra "o que a casa descontou" e a reconciliação, que precisa saber
-- o que já marcou para não refazer o trabalho.
CREATE INDEX idx_bank_tx_family_transfer ON bank_transactions (user_id)
    WHERE family_transfer = TRUE;
