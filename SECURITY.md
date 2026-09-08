# Política de segurança

## Como reportar

Use o **relatório privado de vulnerabilidade** do GitHub (aba *Security* →
*Report a vulnerability*). Ele abre um canal privado com quem mantém o
repositório — nada fica público enquanto a correção não sai.

Não abra issue pública para falha de segurança: a issue é indexada no instante
em que você clica em enviar.

## O que este repositório já verifica sozinho

| Verificação | Quando roda | O que ela responde |
| --- | --- | --- |
| `gitleaks` | todo push e PR, e semanalmente | entrou segredo no histórico? |
| `dependency-review` | todo PR | a dependência que está ENTRANDO tem CVE? |
| CodeQL (`security-extended`) | todo push e PR, e semanalmente | o código tem padrão inseguro? |
| Migrations contra Postgres | todo push e PR | o schema sobe do zero e bate com o mapeamento? |

A varredura semanal existe porque CVE nova aparece sem ninguém mexer no
código — uma esteira que só roda em push nunca descobre isso.

## Segredo neste repositório

Nenhum. As credenciais vivem no painel do Render (`sync: false` no
`render.yaml`) e no `.env` local, que é ignorado pelo git.

Se um segredo vazar num commit, **trocar o segredo é o conserto**; remover o
commit não é. Qualquer pessoa que tenha clonado o repositório antes da remoção
continua com ele.

## Chave-mestra de cifra

`SECRET_ENCRYPTION_KEY` cifra, no banco, a chave de IA que cada usuário
cadastra. Ela é rotacionável sem downtime: `SECRET_ENCRYPTION_KEY_ID` nomeia a
ativa e `SECRET_ENCRYPTION_PREVIOUS_KEYS` guarda as antigas só para leitura,
até o último segredo ter sido reescrito. Tirar a chave antiga antes da hora
torna ilegíveis as linhas que ainda estavam nela — o `render.yaml` explica o
passo a passo.
