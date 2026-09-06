-- APARELHOS DE CONFIANÇA — o segundo fator deixa de atrapalhar quem já provou
-- quem é, e continua valendo para quem chega de fora.
--
-- O problema. Com a V20, um segundo fator ativo pede código em TODO login,
-- inclusive no celular do dono, dez vezes por dia. Fator que atrapalha o dono é
-- fator que o dono desliga — e aí a conta fica sem nenhum.
--
-- O que é "aparelho conhecido". Depois de um segundo passo bem-sucedido, o
-- cliente pode pedir para este aparelho ser lembrado. A API emite um segredo
-- longo e aleatório, guarda só o HASH dele aqui, e o aparelho o apresenta nos
-- próximos logins. Se conferir, o segundo passo é dispensado.
--
-- POR QUE NÃO O IP, E POR QUE NÃO A "IMPRESSÃO DIGITAL" DO NAVEGADOR. O pedido
-- falava em "internet nova". IP de celular muda a cada troca de torre e a cada
-- Wi-Fi: usá-lo como porteiro faria o app pedir código o dia inteiro, que é
-- exatamente o problema que esta tabela existe para resolver. E impressão
-- digital de navegador é adivinhação — muda com uma atualização do sistema e
-- volta a pedir código sem motivo. O que é estável é um SEGREDO que só este
-- aparelho tem. O IP entra por outro caminho, e para outra coisa: a coluna
-- abaixo guarda o hash do último visto, para o AVISO de acesso de lugar novo
-- (que é informação, não bloqueio).
--
-- VALIDADE. 90 dias. Aparelho esquecido num lugar não pode ser uma porta aberta
-- para sempre; renovar é só entrar de novo com o código.

CREATE TABLE trusted_devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 do segredo entregue ao aparelho. Nunca o segredo: um dump do
    -- banco não pode virar uma lista de chaves que pulam o segundo fator.
    -- UNIQUE porque é por ele que a busca acontece — um índice e uma garantia.
    token_hash VARCHAR(64) NOT NULL UNIQUE,

    -- "iPhone de Alice", "Chrome no Windows". Serve para a pessoa reconhecer o
    -- que está na lista e revogar o que não reconhece. Vem do cliente, então é
    -- rótulo, nunca prova de nada.
    label VARCHAR(120),

    -- Hash do último IP visto. Guardado como hash porque IP é dado pessoal e
    -- aqui ele só precisa responder a UMA pergunta: "é o mesmo de antes?".
    -- Guardar o endereço em claro daria a resposta e mais um monte de coisa
    -- que ninguém precisa.
    last_ip_hash VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);

-- A consulta de listagem e a revogação em massa são sempre por dono.
CREATE INDEX idx_trusted_devices_user ON trusted_devices (user_id);
