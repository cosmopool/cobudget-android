---
name: simplification-review
description: >
  Analisa se a MR está mais complexa do que o problema exige e propõe simplificações
  concretas, calibrado para as convenções do uList Flutter (flutter_modular, mocktail,
  offline_first, design system). Não procura bugs nem violações de estilo — esses são
  do /mr-review. Gera relatório em simplification_review.md.
  Use when user says "revisar complexidade", "simplification review", "isso está
  overengineering", "dá pra simplificar essa MR", or invokes /simplification-review.
---

# Simplification Review (uList Flutter)

Analisa a diff da branch atual contra uma branch alvo e responde **uma** pergunta:

> Existe uma solução significativamente mais simples que mantém o mesmo comportamento e
> respeita a arquitetura e as convenções atuais do projeto?

Saída: `simplification_review.md` na raiz do repo.

## Escopo

- Só **código introduzido ou reestruturado pela MR**. Legado que a diff apenas tocou de
  leve não é alvo. Sem isso a skill vira auditoria dos 1490 arquivos de `lib/src/`.
- **Não** procurar bugs, regressões, problemas de sync, performance, i18n ou estilo.
  Esses são as 7 dimensões do `/mr-review`. Se aparecer um bug no caminho, mencionar em
  uma linha no fim do relatório e seguir — não virar review de corretude.
- Nenhum finding desta skill bloqueia merge. Simplificação é sempre negociável.

## Regra de evidência

Todo finding precisa demonstrar a cadeia completa:

`complexidade adicionada → ausência de necessidade atual (provada por comando) →
alternativa mais simples → impacto da simplificação`

Se qualquer elo faltar, não reportar.

### Fato vs. inferência

**Fato** — comprovável rodando um comando no repo: quantas classes implementam uma
interface; quantos call sites um método tem; quais valores um parâmetro recebe; se um
símbolo aparece em `test/`; se existe `Bind<Interface>` em algum módulo.

**Inferência** — não comprovável: intenção do desenvolvedor; se a abstração "vai" ser
usada; se outro dev acharia confuso; se existe um design "melhor".

Findings são baseados em fatos. Inferência não vira finding.

### Não especular

Não reportar, em nenhuma severidade:

- "parece overengineering";
- "poderia ser mais simples";
- "eu faria diferente";
- "talvez não precise dessa classe";
- "essa camada provavelmente é desnecessária";
- "adiciona complexidade sem motivo claro".

Também **não** aceitar como justificativa para manter complexidade:

- "podemos precisar disso depois";
- "quando tivermos outro provider";
- "quando houver mais implementações";
- "para facilitar uma futura migração";
- "para deixar extensível".

Se a necessidade futura não existe no código atual, ela não conta. Mas o inverso também
vale: a ausência de necessidade precisa ser **provada**, não presumida.

### Tamanho não é complexidade

Quantidade de linhas, arquivos ou classes **não é evidência**. Contar conceitos,
indireções e decisões que um dev precisa entender para modificar o fluxo. Uma classe de
200 linhas com um fluxo linear é mais simples que 5 classes de 20 linhas encadeadas.

---

## Lista de exclusão — ler ANTES de analisar

Padrões abaixo são obrigatórios ou justificados neste repo. **Nunca** reportar:

### Interfaces `i_*.dart` com uma única implementação

Existem 161 no repo. Não são gratuitas: os testes as consomem via `mocktail: 1.0.4`
(139 arquivos de teste), em dois estilos — `class MockX extends Mock implements IX {}`
e fake manual `class _FakeX implements IX`. Exemplo:
`test/src/features/new_embeds/datetime/controllers/datetime_embed_controller_test.dart:14`.

Só reportável se o gate de três comandos (abaixo) provar que **não há** mock/fake nem
`Bind<IX>`.

### Qualquer coisa em `lib/offline_first/`

95 arquivos, 12.539 LOC, complexidade justificada por sync multi-device, atomicidade e
idempotência — documentada nas docstrings do próprio código. Não sugerir:

- fundir `*_commands.dart` + `*_queries.dart` (CQRS explícito);
- remover `row_mappers/` ("é só um fromJson") ou `projections/` (`*_aggregate`, `*_view`);
- colapsar as 7 `sync/merge/*_merge_strategy.dart` num `if`;
- trocar `watch*` (Stream) por leitura one-shot — cobre escritas de outros devices
  aplicadas pelo sync;
- remover os typedefs injetáveis `OfflineFirstClock`, `OfflineFirstIdGenerator`,
  `OfflineFirstDeviceIdProvider` (`lib/offline_first/outbox/outbox_writer.dart:14-24`)
  ou `OccurrenceExpandFn` (`lib/offline_first/occurrences/occurrence_materializer.dart:14`)
  — existem para testes determinísticos;
- remover `AppScope.internal` / `overrideForTest`, ou `app_sentinel*`.

Referências de necessidade real: `outbox_writer.dart:29-34` (fila e write na mesma
transaction), `occurrences/occurrence_materializer.dart:33-44` (reconciliação
idempotente e self-healing).

### Widgets privados `_X` granulares

289 no repo, e `.claude/CLAUDE.md` proíbe explicitamente método privado que retorna
`Widget`. "Junta esses 6 widgets num `_buildX()`" é sugestão ilegal aqui.

### Outros padrões estabelecidos

- `*_view_data.dart` + `*_view_data_mapper.dart` separados do model;
- controller `ChangeNotifier`/`ValueNotifier` por feature — inclusive a fila serial
  `_enqueue`, que existe por bug real de ordem
  (`lib/src/features/new_embeds/datetime/controllers/datetime_embed_controller.dart:20-22`);
- `EmbedModule` por embed em `lib/src/features/new_embeds/` com DI estática e
  `@visibleForTesting overrideDatasourceForTest`;
- separação `domain`/`infra`/`external`/`presenter` quando a feature já a adota — a
  skill respeita a arquitetura estabelecida, não a questiona.

Remover complexidade **acidental**, nunca responsabilidade arquitetural.

---

## Categorias e gate obrigatório

Cada categoria só libera finding com o output do comando **colado na evidência**. Sem
output, o passo de validação descarta.

| # | Categoria | Comando(s) | Libera o finding quando |
|---|---|---|---|
| 1 | Interface/abstração prematura | os 3 greps abaixo | 1 impl **e** 0 mocks/fakes em `test/` **e** 0 `Bind<I…>` |
| 2 | Factory/strategy/resolver de um caso | ler o corpo + `grep -rn "XFactory" lib/` | corpo sem branch real **e** 1 call site |
| 3 | Camada que só delega | comparar assinaturas método a método | repassa 1:1 sem transformação, tratamento de erro ou contrato próprio |
| 4 | Generalização não usada | `grep -rn "<nome do param>:" lib/` | param/flag/callback recebe o mesmo valor em todos os call sites |
| 5 | Duplicação de estrutura entre gerações | `ls`/`grep` no equivalente existente | a MR adiciona à geração antiga tendo equivalente na nova |
| 6 | Múltiplas fontes de verdade | rastrear escritas do mesmo dado | o mesmo dado é escrito em ≥2 holders introduzidos pela MR |
| 7 | Indireção desnecessária | rastrear a cadeia de chamadas | ≥3 saltos sem nenhuma mudança de comportamento |

### Gate da categoria 1 (interface prematura)

Rodar os três, colar os três:

```bash
grep -rn "implements IFoo\|extends .*IFoo" lib/ test/
grep -rn "IFoo" lib/app/app_module.dart lib/src --include=*_module.dart
grep -rn "IFoo" lib/ --include=*.dart | wc -l
```

Interpretação: qualquer hit em `test/` **ou** qualquer `Bind…<IFoo>` mata o finding.

### Notas por categoria

**3 — Camada que só delega.** Restrito a cadeias **criadas pela MR**. A cadeia
`Controller → UseCase → Repository → Datasource` é o desenho padrão do legado
(`lib/domain/`, 282 arquivos); apontá-la não é finding.

**5 — Duplicação de estrutura entre gerações.** Este é o problema estrutural nº 1 do
repo. Sinais concretos:

- `lib/src/features/embeds/` (161 arquivos, 4 factories de card/button/builder) vs
  `lib/src/features/new_embeds/` (um `EmbedModule` por embed) — mesmo domínio
  reimplementado. Código novo deve ir para `new_embeds`;
- `presenter` vs `presentation`, ou `data` vs `infra`, **dentro da mesma feature**;
- contrato numa pasta e impl noutra invertendo a convenção — ver
  `lib/src/core/theme/infra/datasource/theme_datasource.dart` (contrato) vs
  `lib/src/core/theme/data/theme_datasource.dart` (impl);
- código novo em `lib/app/`, `lib/data/`, `lib/domain/`, `lib/infra/`, `lib/external/`
  em vez de `lib/src/`.

Reportável só quando a **MR** adiciona à geração errada — não como auditoria do legado.

**6 — Múltiplas fontes de verdade.** Controller + `ValueNotifier` local + Stream do
datasource guardando o mesmo dado. Complexidade real: o dev precisa saber qual dos três
está certo. Não confundir com um controller que expõe estado derivado.

---

## Processo

### 1. Branch alvo

Obter do usuário (argumento da skill ou perguntar). Sugerir `main` como padrão. Nunca
assumir sem confirmar quando não informada.

### 2. Coletar a diff

```bash
git log <target>..HEAD --oneline
git diff <target>...HEAD --stat
git diff <target>...HEAD
```

Derivar em 1-2 frases: **qual problema a MR resolve**. Sem isso não dá para julgar se a
complexidade é proporcional. Separar arquivos **novos** (alvo primário) de arquivos
**modificados** (alvo só onde a modificação introduz estrutura).

### 3. Ler as convenções

`.devin/rules/ulist.md` e `.claude/CLAUDE.md`.

### 4. Fan-out — 3 agentes Explore em paralelo

Uma única mensagem, três chamadas de Agent. Cada agente recebe: o problema que a MR
resolve, a lista de arquivos novos/modificados, a **Regra de evidência** e a **Lista de
exclusão** na íntegra, e o formato de finding.

- **Agente A — abstrações e camadas:** categorias 1, 2, 3.
- **Agente B — generalização e indireção:** categorias 4, 7.
- **Agente C — estrutura e estado:** categorias 5, 6.

Cada agente roda os comandos do gate da sua categoria e cola o output. Agente que
reporta sem output tem o finding descartado na etapa 5.

### 5. Validação

Papel estrito: **pode remover findings, não pode inventar findings**. Para cada um:

1. O output do comando está colado e sustenta a conclusão?
2. O padrão está na lista de exclusão?
3. A complexidade foi introduzida pela MR, ou é legado que a diff tocou?
4. A alternativa proposta mantém o mesmo comportamento?
5. A alternativa respeita a arquitetura da feature (não só "tem menos linhas")?
6. Dois agentes acharam o mesmo? (dedup)
7. Removê-la causaria acoplamento inadequado ou pioraria testabilidade?

Descartar: preferência arquitetural, finding sem output de comando, item da lista de
exclusão, legado fora do escopo, duplicado, simplificação que só reduz linhas.

### 6. Escrever o relatório

`simplification_review.md` na raiz. Avisar o usuário que o arquivo **não** deve ser
commitado. No terminal, resumir só o veredito + contagem por categoria.

---

## Escala

- 🟢 **Simples** — não há abstração ou camada desnecessária identificável.
- 🟡 **Pode simplificar** — existe complexidade desnecessária, sem risco relevante.
- 🟠 **Complexidade desproporcional** — impacto concreto na compreensão ou manutenção.

Não usar 🟠 por preferência arquitetural. 🟠 exige demonstrar o custo concreto: quantos
arquivos um dev precisa abrir para mudar um comportamento, ou qual conceito ele precisa
entender que não corresponde a nenhuma regra de negócio.

**Nenhuma severidade bloqueia merge.**

---

## Formato do finding

```text
[S<n>] arquivo:linha — <severidade>

complexidade:
<o que a MR adicionou>

evidence:
<output do comando do gate, colado>

por que não é necessária:
<leitura do output — quantas impls, quantos call sites, quais valores>

simplificação:
<alternativa concreta, no ponto exato do código>

impacto:
<o que sai; o que continua exatamente igual>
```

---

## Template do relatório

````markdown
# Simplification Review — <branch> → <target>

<N> arquivos novos · <M> modificados · <K> findings

## Veredito

🟢 | 🟡 | 🟠 — <uma frase>

## Sumário

| Categoria | 🟠 | 🟡 |
|---|---|---|
| Abstração prematura | 0 | 1 |
| ... | | |

## Findings

### [S1] arquivo:linha — 🟡 Abstração prematura

**Complexidade:** ...

**Evidence:**
```
<output do grep>
```

**Por que não é necessária:** ...

**Simplificação:** ...

**Impacto:** ...

## Verificado e considerado justificado

- `IDateTimeRecurrenceDatasource` — 1 impl, mas há fake em
  `test/.../datetime_embed_controller_test.dart:14`. Contrato necessário.
- ...

## Categorias sem achados

Generalização não usada · Múltiplas fontes de verdade
````

A seção **Verificado e considerado justificado** é obrigatória quando a análise
descartou findings. Ela evita que a mesma discussão se repita a cada MR.

---

## Exemplo calibrado

`lib/src/features/new_embeds/datetime/datasource/offline_first_datetime_recurrence_datasource.dart:23-45`
— 🟡

**Complexidade:** 6 dependências nullable no construtor mais 6 getters
`_effectiveX => _x ?? XCommands()`, ou seja 12 membros para 6 dependências.

**Evidence:** os únicos call sites que passam as deps são de teste; produção sempre
instancia sem argumentos.

**Por que não é totalmente necessária:** o boilerplate serve à injeção em teste sem
passar pelo `AppScope`. A necessidade é real — a **forma** é que se repete.

**Simplificação:** um helper de escopo de teste, ou um único parâmetro de deps
agrupadas, reduziria de 10 membros para 1 mantendo a injetabilidade.

**Impacto:** menos boilerplate por datasource; a injeção em teste continua funcionando
igual. **Não** é candidato a "remover a injeção".

Este exemplo é também um exercício de honestidade: a leitura preguiçosa classificaria
como 🟠 "remover os nullables". A leitura correta identifica a necessidade real e
propõe simplificar a forma.

---

## Estilo

- **pt-BR**, direto, sem elogios. Só achados acionáveis.
- Impacto antes de causa.
- `arquivo:linha` + ID `S<n>` em todo finding.
- Não usar a escala 🔴/🔵 do `/mr-review` — as escalas são distintas de propósito.
- Não sugerir "considerar refatorar" — a simplificação proposta precisa ser executável
  como está escrita.
