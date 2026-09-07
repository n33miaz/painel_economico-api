-- RESERVA DE FATURA — o dinheiro que já está separado para pagar um cartão.
--
-- O pedido, na frase do dono (07/09/2026): "parece que você não registrou que
-- tem o saldo exato do valor da fatura na minha conta de lá — deixei separado".
-- O caso concreto é a compra de R$ 641,14 no Mercado Livre, dividida em 2× de
-- R$ 320,57 (fecha dia 8, vence dia 14): o valor inteiro está parado na conta
-- Mercado Pago, e para ele aquilo não é saldo — é fatura já paga que ainda não
-- saiu.
--
-- POR QUE UMA TABELA E NÃO UM LANÇAMENTO. A tentação é registrar a reserva
-- como uma transação de saída na conta. Seria errado por três motivos:
--   1. O dinheiro NÃO saiu. Inventar um débito falsifica o extrato, e o extrato
--      é a única coisa no sistema que espelha o banco linha a linha.
--   2. Quando a fatura for paga de verdade, o débito real chega pelo extrato e
--      passaria a haver dois — um inventado e um verdadeiro.
--   3. Reserva é uma INTENÇÃO, e intenção se desfaz: o dono pode gastar o
--      dinheiro em outra coisa. Apagar uma linha da tabela é barato; desfazer
--      um lançamento que já entrou em relatório, não.
--
-- POR QUE PRESA AO CICLO E NÃO AO CARTÃO. "Reservei para a fatura" só quer
-- dizer alguma coisa junto de QUAL fatura: a de setembro está coberta, a de
-- outubro ainda não existe. Daí a chave única (cartão, referência) — uma
-- reserva por ciclo, que o app sobrescreve quando o dono corrige o valor.
CREATE TABLE invoice_reserves (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- O cartão cuja fatura está sendo coberta.
    card_account_id UUID NOT NULL REFERENCES connector_accounts(id) ON DELETE CASCADE,

    -- O ciclo, no mesmo formato que a API já usa em CardInvoicesResponse: o mês
    -- em que a fatura FECHA ("2026-09"). Texto, e não data, porque é assim que
    -- o app pede a fatura e assim que a pessoa fala dela.
    reference VARCHAR(7) NOT NULL,

    -- Quanto está separado. Positivo sempre. Pode ser MENOR que a fatura (o
    -- dono separou uma parte) e pode ser MAIOR (separou de mais, ou a fatura
    -- ainda vai crescer até fechar) — quem compara os dois é a leitura, não o
    -- banco, justamente para a tela poder dizer "cobre 62%" em vez de recusar
    -- o registro.
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),

    -- Onde o dinheiro está parado. NULÁVEL e ON DELETE SET NULL: a reserva
    -- continua verdadeira mesmo se a conta for desconectada, e há quem separe
    -- fora do que o sistema enxerga (um envelope, outra instituição). Quando
    -- vem preenchido, a tela consegue dizer "R$ 641,14 na conta Mercado Pago".
    held_in_account_id UUID REFERENCES connector_accounts(id) ON DELETE SET NULL,

    -- Anotação livre do dono ("dinheiro do 13º", "adiantei o parcelamento").
    note VARCHAR(200),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_invoice_reserves_card_reference UNIQUE (card_account_id, reference)
);

-- A leitura mais comum é "todas as reservas deste usuário" (o Perfil e a
-- previsão de saldo precisam do total separado, não de um ciclo específico).
CREATE INDEX idx_invoice_reserves_user ON invoice_reserves (user_id);
