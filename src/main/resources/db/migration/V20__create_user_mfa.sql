-- SEGUNDO FATOR (TOTP) — a senha deixa de ser a única coisa entre um vazamento
-- e o extrato bancário de alguém.
--
-- POR QUE TOTP, E NÃO SMS OU E-MAIL. SMS custa por mensagem, depende de
-- operadora e é o fator que o SIM swap derruba; e-mail está DESLIGADO neste
-- deploy (MAIL_ENABLED ausente), então um segundo fator por e-mail não existiria
-- na prática. TOTP (RFC 6238) roda no aparelho do usuário, offline, com qualquer
-- app de autenticação, e não custa nada — nem dinheiro nem dependência nova: o
-- algoritmo é HMAC-SHA1 sobre o contador de tempo, que o JDK já tem.
--
-- DUAS TABELAS, E NÃO COLUNAS EM users. O segredo e os códigos de recuperação
-- têm ciclo de vida próprio (nascem no cadastro do fator, morrem quando ele é
-- desligado) e regime de acesso próprio — nenhuma consulta de perfil pode
-- trazê-los de carona num SELECT *.

CREATE TABLE user_mfa (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- UNIQUE: um fator por conta. ON DELETE CASCADE porque segredo não pode
    -- sobreviver ao dono.
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,

    -- O SEGREDO TOTP, CIFRADO. Nunca em claro, aqui nem em log nem em resposta
    -- de API depois da tela de cadastro. Mesmo envelope autodescritivo do
    -- EC-107 ("v1:<idDaChave>:<iv>:<cifra>", AES-256-GCM), com o UUID do dono
    -- como dado autenticado: mover a linha para outro user_id quebra a
    -- decifragem em vez de entregar o fator de um usuário a outro.
    --
    -- A chave-mestra aqui é DERIVADA de jwt.secret (ver MfaSecretCipher) e não
    -- da SECRET_ENCRYPTION_KEY do EC-107 — esta última é opcional no deploy
    -- (byokAvailable=false hoje em produção) e um segundo fator que só funciona
    -- quando uma variável opcional existe não é um segundo fator. O preço está
    -- documentado: TROCAR O JWT_SECRET torna os segredos ilegíveis e obriga
    -- cada usuário a recadastrar o fator — os códigos de recuperação abaixo
    -- continuam valendo, porque são hash e não dependem de chave nenhuma.
    secret_cipher VARCHAR(512) NOT NULL,

    -- Só depois do primeiro código correto o fator passa a valer. Sem isto,
    -- errar a leitura do QR trancava a conta para fora: o servidor exigiria um
    -- código que o app do usuário nunca geraria.
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_at TIMESTAMPTZ,

    -- Último passo de tempo aceito. É o que impede REPLAY: um código roubado da
    -- tela (ombro, captura, phishing em tempo real) vale 30 segundos, e dentro
    -- deles só pode ser usado UMA vez.
    last_used_step BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CÓDIGOS DE RECUPERAÇÃO — a saída para quem perdeu o aparelho.
--
-- Guardados como HASH bcrypt, pelo mesmo motivo das senhas: um dump do banco
-- não pode virar uma lista de chaves de entrada. São de uso único (used_at) e
-- vêm em lote; gerar um lote novo apaga o anterior.
CREATE TABLE mfa_recovery_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(100) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A única consulta é "os códigos DESTE usuário": um código chega sem dono
-- declarado e precisa ser conferido contra os hashes da conta que está entrando.
CREATE INDEX idx_mfa_recovery_user ON mfa_recovery_codes (user_id);
