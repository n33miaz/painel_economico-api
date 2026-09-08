# Como o trabalho anda neste repositório

Três branches, e o que separa uma da outra é **o quanto já foi provado**.

```
develop  ──►  homolog  ──►  main
   │            │             │
   │            │             └─ economize-api            (o que o dono usa)
   │            └─ economize-api-homolog                  (onde a migration estreia)
   └─ ninguém publica daqui; é onde o trabalho acontece
```

## develop

Onde o trabalho acontece. Aceita push direto e roda a esteira inteira: suíte,
migrations contra Postgres de verdade, varredura de segredo, CodeQL.

## homolog

Recebe merge de `develop`. O Render publica em `economize-api-homolog`, que tem
**banco próprio**, **segredo próprio** e o conector bancário desligado.

É aqui que uma migration roda pela primeira vez contra um banco que pode
quebrar sem consequência. Antes de existir este ambiente, toda migration
estreava contra a conta que o dono usa de verdade.

## main

Recebe merge de `homolog`, **por pull request e com a esteira verde** — é o que
a proteção de branch exige. O Render publica em `economize-api` no mesmo
instante, e é por isso que o portão está no GitHub e não no Render: o Render
publica o que chega, então o filtro precisa estar em quem deixa chegar.

Não há push direto em `main`.

## Migration

Arquivo de migration aplicado é **imutável** — o Flyway guarda o checksum do
arquivo inteiro. Corrigir é sempre com migration nova.

A esteira monta o schema do zero a cada push (`V1` até a última) e sobe a
aplicação com `ddl-auto=validate` contra ele. Os dois erros que derrubam um
deploy — SQL inválido e coluna que não bate com o mapeamento — morrem aí.

## Como o merge entra

**Merge commit**, e não rebase nem squash. A primeira versão da proteção exigia
histórico linear, e isso obriga o merge a reescrever os commits — o que faz `homolog`
divergir de `main` no instante seguinte ao merge. Como `homolog` também é protegido (sem
force-push, com razão), ele fica impossível de realinhar sem afrouxar a regra.

Com merge commit, `homolog` continua ancestral de `main`, tudo avança por fast-forward, e a
proteção que importa fica de pé: check verde obrigatório, sem push direto, sem force-push,
sem apagar branch.

## Commit

Conventional Commits em inglês, imperativo, minúsculas, sem ponto final.
Assunto curto (~35 caracteres, teto de 50), **só o assunto**: sem corpo e sem
rodapé. Um commit por parte lógica, com o teste no mesmo commit do código.
