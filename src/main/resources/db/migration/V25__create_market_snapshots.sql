-- SNAPSHOT DE MERCADO PERSISTIDO — o último preço bom sobrevive ao reinício.
--
-- O problema. Quando a AwesomeAPI responde 429 (QuotaExceeded), a API serve o
-- último snapshot bom marcado como stale: preço velho é melhor que Home vazia.
-- Só que esse snapshot vivia em memória, e no plano free o Render reinicia o
-- container o tempo todo (deploy, falta de memória, hibernação). Reiniciou com
-- a cota já estourada, o log mostra "sem snapshot stale" e o "Mercado agora"
-- da Home fica em esqueleto até a virada do dia.
--
-- O que é. Uma linha por chave de snapshot ("awesome:all", "brapi:PETR4",
-- "data:macro"...), com o payload em JSON exatamente como o ObjectMapper do
-- Spring o serializa. A memória continua sendo a primeira camada (24h, como
-- sempre foi); o banco é a segunda, gravada de forma assíncrona a cada snapshot
-- novo e lida quando a memória está vazia — no boot, ou depois que as 24h da
-- memória venceram. Snapshot com mais de 7 dias não é servido nunca.
--
-- Por que TEXT e não JSONB. Ninguém consulta dentro do payload: ele entra e sai
-- inteiro, por chave. JSONB custaria parse na gravação para um dado que só é
-- lido pelo mesmo código que o escreveu — e TEXT funciona igual no H2 dos testes.

CREATE TABLE market_snapshots (
    -- Chave lógica do snapshot; os prefixos são convenção do MarketSnapshotStore
    -- ("search:" fica fora do agregado da Home, "data:" não é lista de cotação).
    key VARCHAR(80) PRIMARY KEY,

    payload TEXT NOT NULL,

    -- Quando o snapshot foi gravado. É contra ela que se decide se um
    -- snapshot ainda pode ser servido (até 7 dias, sempre marcado stale).
    saved_at TIMESTAMPTZ NOT NULL,

    -- Fonte verdadeira do payload ("AwesomeAPI", "Frankfurter (BCE)+CoinGecko",
    -- "Tesouro Transparente"): é o que o app mostra ao lado de "atualizado às".
    source VARCHAR(40)
);
