-- INVESTIMENTOS: posições consolidadas + interesses do usuário.
--
-- O problema. Até aqui o produto só conhecia investimento como LANÇAMENTO: o
-- extrato traz "Aplicação CDB" e "Rendimentos", o motor categoriza como
-- Investimentos (V8) e acabou — o dinheiro sai da conta corrente e some do
-- retrato. Quanto está aplicado, em quê, rendendo o quê, ninguém sabe. E o
-- sync do Pluggy IGNORA de propósito a conta INVESTMENT ("investimento é
-- posição, não lançamento"): a decisão estava certa para o pipeline de
-- extrato, mas deixava a posição sem lugar nenhum para existir.
--
-- Por que uma tabela de POSIÇÃO e não mais linhas em bank_transactions. Um
-- lançamento é um fato datado que aconteceu uma vez; uma posição é um ESTADO
-- que muda a cada dia (saldo, cotação, rendimento acumulado) e é substituído a
-- cada sincronização. Guardar estado como lançamento exigiria inventar uma
-- "transação de reavaliação" por dia — e o extrato do usuário viraria ruído.
--
-- Três origens, na mesma tabela, porque a pergunta do usuário é UMA ("quanto
-- eu tenho investido?") e a resposta não pode depender de por onde o dado
-- entrou:
--   CONNECTOR  veio do agregador (GET /investments do Pluggy), com id do
--              provedor para o upsert de cada sync;
--   STATEMENT  reservada para posição DERIVADA do extrato (saldo líquido de
--              aplicações e resgates por instituição). Nesta rodada nenhuma
--              rotina grava com esta origem — o lado "extrato" dos
--              investimentos é servido como MOVIMENTOS derivados na leitura,
--              sem materializar posição. A origem existe no vocabulário para
--              a derivação não precisar de migração quando vier;
--   MANUAL     cadastrada pelo usuário. É o único caminho para o que nenhum
--              conector brasileiro alcança — a ETF em corretora no exterior,
--              o CDB do banco que não está no Open Finance.

CREATE TABLE investment_positions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- CONNECTOR | STATEMENT | MANUAL (ver acima)
    source VARCHAR(12) NOT NULL,

    -- id da posição na API do provedor — texto para não acoplar o schema ao
    -- formato de id de um terceiro (mesma decisão de pluggy_items e
    -- connector_accounts). NULO na posição manual: ela não tem provedor.
    provider_position_id VARCHAR(80),

    -- Vínculos informativos, NULÁVEIS e ON DELETE SET NULL: desvincular a
    -- instituição no agregador não pode apagar a posição que o usuário já viu.
    -- Ela fica marcada como desatualizada (position_date para de avançar) até
    -- o usuário removê-la ou reconectar — a mesma regra das origens (V16).
    pluggy_item_id UUID REFERENCES pluggy_items(id) ON DELETE SET NULL,
    account_id UUID REFERENCES connector_accounts(id) ON DELETE SET NULL,

    -- onde a posição está custodiada ("Banco Inter", "Mercado Pago", "Avenue").
    -- Copiada do conector no sync, digitada no cadastro manual. É a dimensão do
    -- corte "por instituição" do resumo
    institution VARCHAR(160),
    name VARCHAR(160) NOT NULL,
    -- ticker/código curto quando existe: VT, PETR4, TESOURO SELIC 2029. É por
    -- ele que o app busca cotação para a posição manual sem valor atual
    code VARCHAR(32),

    -- FIXED_INCOME | TREASURY | FUND | EQUITY | ETF | CRYPTO | PENSION | OTHER.
    -- Vocabulário do PRODUTO, não do provedor: o Pluggy diz MUTUAL_FUND e
    -- SECURITY, o usuário diz "fundo" e "previdência". A tradução mora no
    -- código (PluggyInvestmentMapper) e o banco guarda a palavra final.
    type VARCHAR(16) NOT NULL,
    -- refinamento livre dentro do tipo: CDB, LCI, LCA, TESOURO_SELIC,
    -- TESOURO_IPCA, TESOURO_PREFIXADO, FII, PGBL... Texto e não enum porque a
    -- lista cresce com cada conector novo e não vale uma migração por sigla
    subtype VARCHAR(32),
    -- CDI | SELIC | IPCA | PREFIXADO | USD | NONE. É o que liga a posição ao
    -- indicador que o usuário quer acompanhar: quem tem CDB acompanha o CDI,
    -- quem tem Tesouro IPCA+ acompanha a inflação. Base da personalização
    indexer VARCHAR(16),
    -- taxa como texto de apresentação ("110% CDI", "IPCA + 6,20%"): o provedor
    -- devolve a taxa em três campos com semânticas diferentes e o app só
    -- precisa mostrá-la — parsear de volta seria trabalho sem leitor
    rate VARCHAR(40),

    -- ISO 4217. VARCHAR e não CHAR(3) de propósito: o Hibernate valida o
    -- schema na subida (ddl-auto=validate) e espera varchar para String —
    -- CHAR aqui derrubaria o boot com "wrong column type". O tamanho fixo
    -- continua garantido pelo limite da coluna e pela validação da API
    currency VARCHAR(3) NOT NULL DEFAULT 'BRL',

    -- Quantidade com 8 casas porque cripto e cota de fundo fracionam assim;
    -- preço unitário com 6 porque cota de fundo é cotada com 6 casas
    quantity NUMERIC(19, 8),
    unit_price NUMERIC(19, 6),
    -- quanto entrou e quanto vale hoje. Os DOIS nuláveis: o provedor nem sempre
    -- informa o aplicado, e a posição manual sem cotação não tem valor atual —
    -- e a API NÃO inventa um: devolve nulo e declara que falta cotação
    -- (needsQuote), para o app completar com o indicador ao vivo
    invested_amount NUMERIC(19, 4),
    current_value NUMERIC(19, 4),

    maturity_date DATE,
    -- data a que o saldo se refere (a "posição em"). É ela que diz se a linha
    -- está desatualizada: uma posição que sumiu do provedor não é apagada, só
    -- para de receber esta data — e o app mostra "desatualizada"
    position_date DATE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Alvo do upsert de cada sync: (dono, origem, id no provedor). PARCIAL porque
-- a posição manual não tem id de provedor, e NULL não participa de UNIQUE
-- composto no Postgres de forma útil — duas manuais precisam poder coexistir.
-- Amarrado ao dono pela mesma razão de connector_accounts: um id reaproveitado
-- por outro item não pode atravessar a fronteira de conta do Economize.
CREATE UNIQUE INDEX uq_investment_positions_provider
    ON investment_positions (user_id, source, provider_position_id)
    WHERE provider_position_id IS NOT NULL;

-- Toda leitura é por dono: listagem, resumo, perfil. Unidades a dezenas de
-- linhas por usuário — um índice só, pelo dono, e nenhum pelos vínculos.
CREATE INDEX idx_investment_positions_user ON investment_positions (user_id);

-- INTERESSES: o que o usuário pediu para acompanhar além do que as posições
-- já implicam. "Personalização é a palavra": a posição em CDB deriva o CDI
-- sozinha, mas quem acompanha o dólar sem ter dólar precisa de um lugar para
-- dizer isso — e para desdizer depois (o derivado não se remove; o manual, sim).
--
--   kind    RATE (CDI, SELIC) | INDEX (IPCA, IGPM) | CURRENCY (USD, EUR) |
--           TICKER (VT, PETR4) | TOPIC (id do vocabulário fixo de notícias)
--   code    o identificador dentro do tipo; maiúsculo, exceto TOPIC (slug)
--   market  só faz sentido para TICKER (US, BR): o mesmo código existe em
--           duas bolsas e a cotação certa depende de saber qual
CREATE TABLE investment_interests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(12) NOT NULL,
    code VARCHAR(32) NOT NULL,
    market VARCHAR(8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- o mesmo interesse não entra duas vezes; o POST é idempotente por isto
    CONSTRAINT uq_investment_interests_user_kind_code UNIQUE (user_id, kind, code)
);

CREATE INDEX idx_investment_interests_user ON investment_interests (user_id);
