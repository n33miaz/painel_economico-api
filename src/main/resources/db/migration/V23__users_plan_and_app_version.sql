-- PLANO DA CONTA — gratuito com anúncios, ou pago sem anúncios (e o interesse
-- medido ANTES de existir cobrança).
--
-- A primeira monetização do Economize! é anúncio em todas as telas. A segunda,
-- oferecida de tempos em tempos, é um plano que tira os anúncios e soma outras
-- vantagens. Ainda NÃO há gateway de pagamento nem orçamento para um: esta
-- migration cria o lugar onde o plano vive e o registro de quem disse "eu
-- pagaria" — o dono mede a demanda antes de construir a cobrança.
--
-- A decisão é do SERVIDOR, não do app. Se "sem anúncios" fosse uma marca local,
-- bastaria limpar o armazenamento do celular (ou editar a resposta num proxy)
-- para ter o plano pago de graça. O app pergunta em GET /users/me (adsEnabled)
-- e obedece; a mesma resposta vale para qualquer cliente, em qualquer versão.
--
-- plan: FREE ou PLUS, como texto. Um tipo enum do Postgres exigiria ALTER TYPE
-- a cada plano novo; VARCHAR(16) com a validação no Hibernate (@Enumerated
-- STRING) é o que as demais colunas de estado deste schema já fazem.
-- DEFAULT 'FREE' porque é o estado de todo mundo que já existe.
--
-- plan_until: até quando o PLUS vale. NULL em FREE e num PLUS sem prazo — hoje
-- o único jeito de alguém ser PLUS é concessão manual no banco. Vencido, a
-- conta volta a ver anúncio SEM job agendado: a leitura compara com now(). O
-- valor fica na linha de propósito: "foi PLUS até tal dia" continua verdade, e
-- é o que a cobrança futura vai renovar em vez de recriar.
ALTER TABLE users ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'FREE';
ALTER TABLE users ADD COLUMN plan_until TIMESTAMPTZ NULL;

-- Quem se interessou por qual plano. UM registro por (usuário, plano): tocar o
-- botão dez vezes não é dez interessados, e o UNIQUE é o que torna o POST
-- idempotente de verdade — a checagem "já existe?" do serviço só poupa o
-- insert; quem decide a corrida entre dois toques é a constraint.
--
-- Sem coluna de "quando foi oferecido" ou "recusou": o que se quer saber agora
-- é quantas pessoas, e quem, para dimensionar o plano. Ao apagar a conta o
-- interesse vai junto (CASCADE) — não é dado que sobrevive ao usuário.
CREATE TABLE plan_interest (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_interest_user_plan UNIQUE (user_id, plan)
);

-- A pergunta do GET /plans é "este usuário já se interessou?", sempre por dono.
-- O índice que sustenta o UNIQUE acima já começa por user_id e atende essa
-- busca (prefixo de índice composto); um segundo índice só em user_id seria
-- cópia dele — custo em toda escrita e nenhuma consulta nova atendida. A
-- contagem de demanda para o dono é a leitura ocasional no SQL do Supabase:
--   SELECT plan, count(*) FROM plan_interest GROUP BY 1;
