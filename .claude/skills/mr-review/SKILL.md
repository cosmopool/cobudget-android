---
name: mr-review
description: >
  Review multi-agente de Merge Request do uList Flutter. Analisa a diff da branch atual contra a
  branch alvo em 7 dimensões (corretude, offline-first/sync, arquitetura, duplicação/DS,
  performance, estado/lifecycle, i18n/nomenclatura/hygiene), valida findings com um reviewer final
  e gera relatório em mr_review.html. Use when user says "revisar MR", "review de MR",
  "revisar essa branch", "review da branch", "MR review", or invokes /mr-review.
---

# MR Review (uList Flutter)

Review da branch atual contra uma branch alvo. Análise multi-agente da diff com busca de contexto no repositório inteiro. Saída: relatório html `mr_review.html` na raiz do repo.

**Princípio geral:** o objetivo do review é encontrar problemas **introduzidos ou expostos pela MR**, não produzir uma lista de melhorias possíveis. Todo finding precisa de evidência no código e uma ação concreta. Na dúvida, reduzir a severidade ou descartar.

## Regra de evidência — não especular

O objetivo do review é encontrar **problemas reais introduzidos ou expostos pela MR**, e não listar possibilidades, preferências ou cenários hipotéticos.

Um finding só deve ser reportado quando for possível demonstrar, usando a diff e o contexto real do repositório, uma relação causal:

`código alterado → comportamento executado → causa do problema → impacto`

O reviewer deve conseguir explicar **exatamente por que o problema acontece no código atual**.

### O que um finding precisa demonstrar

Antes de reportar um problema, o agente deve identificar:

1. **O código alterado** que causa o problema.
2. **O fluxo existente** que executa ou consome esse código.
3. **O estado/entrada** necessário para chegar ao problema.
4. **O comportamento incorreto** produzido.
5. **O impacto concreto** desse comportamento.
6. **A causa técnica** que conecta o código alterado ao impacto.
7. **Um fix concreto**, quando aplicável.

O finding deve ser baseado em código que realmente existe no repositório.

### Não especular

Não reportar como problema factual afirmações como:

- "Isso pode quebrar se..."
- "Talvez aconteça..."
- "Pode causar problemas..."
- "Existe risco de..."
- "Seria melhor..."
- "Possivelmente..."
- "Caso alguma outra parte faça..."
- "No futuro isso pode..."
- "Se houver concorrência..."
- "Se essa função for chamada duas vezes..."

quando o cenário não puder ser demonstrado no código atual.

Se o cenário hipotético não puder ser encontrado ou demonstrado no repositório, **não classificar como bug**.

Se houver uma suspeita tecnicamente relevante, mas não for possível comprovar o fluxo, classificar como 🔵 e declarar explicitamente a incerteza.

### Regra especial para 🔴

Um finding 🔴 precisa ter evidência concreta.

Não classificar como 🔴 problemas baseados apenas em:

- possibilidade teórica;
- preferência arquitetural;
- código que "poderia" ser mais seguro;
- ausência de teste sem evidência de regressão;
- possível race condition sem fluxo concorrente demonstrável;
- possível problema de performance sem caminho quente ou impacto demonstrável;
- comportamento futuro;
- suposições sobre como outra parte do sistema poderia usar o código.

Para 🔴, o reviewer deve conseguir explicar um fluxo concreto que reproduza o problema.

### Fato vs. inferência

O agente deve distinguir claramente entre fatos observáveis no código e inferências.

**Fato** — pode ser comprovado diretamente pelo repositório:

- uma função chama outra;
- uma tabela é alterada;
- um estado é atualizado;
- uma requisição é disparada;
- uma lista é filtrada;
- um registro é removido;
- um resultado é consumido;
- uma reação MobX executa determinada função;
- um widget lê determinado estado;
- um teste cobre determinado comportamento.

**Inferência** — não é diretamente demonstrável:

- comportamento futuro;
- cenário que depende de código inexistente;
- hipótese sobre intenção do desenvolvedor;
- hipótese sobre como outro sistema externo poderia se comportar;
- possibilidade de concorrência sem concorrência demonstrável;
- possibilidade de corrupção sem fluxo que produza a corrupção.

Findings 🔴 e 🟡 devem ser baseados em fatos e relações causais verificáveis.

Inferências só podem aparecer como 🔵, com a dúvida explicitamente indicada.

### Fluxo concreto obrigatório

Antes de reportar um finding, o agente deve conseguir descrever mentalmente o fluxo que reproduz o problema:

1. **Estado inicial** — qual é o estado relevante?
2. **Ação** — o que o usuário, sistema ou processo executa?
3. **Código alterado** — qual arquivo e linha são responsáveis?
4. **Execução** — o que o código realmente faz?
5. **Estado resultante** — o que fica incorreto?
6. **Impacto** — o que o usuário, sistema, persistência ou integração sofre?

Se o fluxo não puder ser completado com informações presentes no repositório, **não reportar o problema como bug**.

### Obrigação de buscar contexto

A diff não deve ser analisada isoladamente.

Antes de concluir que existe um problema, o agente deve procurar no repositório:

- quem chama o código alterado;
- quem consome seu resultado;
- implementações da mesma interface;
- interfaces/base classes;
- usecases relacionados;
- stores/controllers relacionados;
- repositories/data sources relacionados;
- testes existentes;
- fluxos de persistência;
- fluxos de sincronização;
- usos do símbolo alterado;
- implementações semelhantes;
- contratos existentes.

A diff mostra **onde a mudança ocorreu**. O restante do repositório deve ser usado para determinar **se essa mudança realmente produz um problema**.

Não concluir que um comportamento é incorreto apenas olhando a implementação isolada quando o contrato ou comportamento esperado está definido em outro ponto do projeto.

### Evidência obrigatória no finding

Cada agente deve produzir findings internamente com:

```text
arquivo:linha
severidade
confidence

problema:
<o que está incorreto>

evidence:
<evidência concreta encontrada no código>

fluxo:
<como o código chega ao problema>

impacto:
<efeito concreto>

causa:
<por que o código produz esse comportamento>

fix:
<correção sugerida>
```

## Process

### 1. Branch alvo

Obter a branch alvo do usuário (argumento da skill ou perguntar). Sugerir `main` como padrão. Nunca assumir sem confirmar quando não informada.

### 2. Coletar diff + objetivo da MR

```bash
git log <target>..HEAD --oneline
git log <target>..HEAD --format=%B
git diff <target>...HEAD --stat
git diff <target>...HEAD
```

Se a diff for muito grande para o contexto, usar `--stat` + diffs por arquivo conforme necessário.

A partir dos commits (e da descrição da MR, se disponível), inferir e registrar:

- **Objetivo da MR** (1-2 frases)
- **Comportamentos esperados** que a MR introduz
- **Áreas afetadas**

Isso vai no prompt de todos os agentes — review sem saber a intenção da MR reporta decisões intencionais como problemas.

### 3. Classificar arquivos alterados

Classificar cada arquivo por região do repo — isso vai no prompt dos agentes:

- `lib/src/` → código novo/canônico (Clean Architecture esperada: `features/<feature>/{presentation,domain,data}`)
- `lib/app/`, `lib/data/`, `lib/domain/`, `lib/infra/`, `lib/external/` → legado
- `lib/ds/`, `lib/tickenDS/`, `lib/src/core/widgets/`, `lib/app/widgets/` → design system / widgets compartilhados
- `packages/` → pacotes do monorepo Melos
- Ignorar gerados: `*.g.dart`, `*.freezed.dart`, `lib/generated/`, `.arb` regenerados, `Podfile.lock`, `pubspec.lock`

Para cada arquivo alterado, levantar também:

- **AGENTS.md aplicáveis:** o `AGENTS.md` raiz e todo `AGENTS.md` nos diretórios entre a raiz e o arquivo (ex.: `lib/offline_first/AGENTS.md` para qualquer arquivo em `lib/offline_first/**`). Buscar com `find . -name AGENTS.md -not -path '*/node_modules/*'`; não confiar só no índice do raiz. A lista deduplicada vai no prompt de todos os agentes.
- **Arquivo novo ou alterado:** usar `git show <target>:<arquivo>` (falha = arquivo novo) para saber o que já existia antes da MR.

### 4. Fan-out multi-agente

Lançar 7 agentes Explore **em paralelo** (uma única mensagem, múltiplas chamadas de Agent). Cada agente recebe:

- Objetivo da MR + comportamentos esperados (etapa 2)
- A lista de arquivos alterados com a classificação acima
- A diff (ou instrução de rodar `git diff <target>...HEAD` nos arquivos relevantes à sua dimensão)
- A lista de **AGENTS.md aplicáveis** (etapa 3), com instrução de ler cada um antes de analisar
- Instrução de ler `.devin/rules/ulist.md` como referência de convenções de UI do projeto
- A seção **Regra de evidência — não especular** (acima), na íntegra
- As **regras comuns** abaixo
- Instrução de retornar findings no formato estruturado da subseção
  **Evidência obrigatória no finding**

**Regras comuns a todos os agentes:**

- **Regra de evidência.** Seguir a seção "Regra de evidência — não especular": relação causal demonstrável (`código alterado → comportamento executado → causa → impacto`), fluxo concreto completável com o repositório, fato vs. inferência. "Possível problema de ordem nas operações" sem cenário não é finding.
- **Context search antes de concluir.** Nunca afirmar que algo é duplicado, incompatível ou "deveria estar em outro lugar" olhando só a diff: buscar referências, implementações semelhantes, interfaces/base classes, testes existentes e convenções do projeto primeiro (lista completa na subseção "Obrigação de buscar contexto").
- **Código pré-existente** só pode ser reportado quando: (1) a diff altera seu comportamento; (2) a diff passa a depender dele de maneira incorreta; (3) a diff expõe um bug antes inacessível; (4) ele é uma implementação existente que o código novo deveria reutilizar; (5) a diff cria um substituto e deixa a versão antiga sem uso ao lado (código morto que alguém pode reusar por engano). Problemas pré-existentes sem relação causal com a MR: fora. Antes de atribuir algo à MR, confirmar com `git show <target>:<arquivo>` que não existia na branch alvo.
- **AGENTS.md como checklist.** Confrontar a diff com cada regra dos AGENTS.md aplicáveis. Violação de regra escrita é finding e cita a regra como `AGENTS.md:linha` no campo `evidence`, junto com o código que a viola.
- **Pergunta ao autor em vez de acusação.** Quando houver indício de que a feature, a query ou o fluxo da MR já existe em outro lugar, mas não for possível provar que é a mesma coisa, não gerar finding. Gerar uma **pergunta ao autor**: "Essa funcionalidade já existe em `<caminho>`? Como ela foi feita lá? Dá para reutilizar?". Perguntas saem no formato `arquivo:linha — pergunta — o que foi encontrado (caminho)`.
- Cada agente segue seu princípio + perguntas-guia; as listas de exemplos são ilustrativas, não checklist exaustivo.

Dimensões:

**Agente 1 — Corretude & regressões**
Princípio: esse código faz o que deveria em **todos** os caminhos? Priorizar bugs observáveis, não código que "poderia ser melhor".
Para cada alteração de comportamento, traçar o fluxo completo (UI → controller/store → usecase → repository → datasource → API/DB) e responder:

1. Qual é a entrada? 2. Qual a transformação? 3. Qual a persistência? 4. O que acontece no erro? 5. E se executar duas vezes? 6. E se a resposta chegar fora de ordem? 7. O estado anterior é substituído/mesclado corretamente? 8. Existe caminho em que dados são perdidos, duplicados ou ficam órfãos?
   Também: estados loading/success/empty/error; usuário sai da tela durante operação; concorrência entre chamadas; regressão de fluxo existente.
   **Blast radius leve:** para cada API/assinatura/model alterado, buscar consumidores no repo e verificar se seguem funcionando.
   **Gap de teste:** não reportar "não há teste" isoladamente. Reportar só quando: a diff adiciona regra de negócio relevante sem cobertura; há risco concreto de regressão; teste existente deveria ter sido atualizado; ou um bug encontrado não tem nenhuma proteção automatizada.
   **Chave por identidade:** `Map`/`Set` chaveado por objeto (view model, entidade) exige que a classe da chave implemente `==`/`hashCode`. Abrir a classe fora da diff para conferir. Sem isso, o lookup só funciona com a mesma instância e falha em silêncio após `copyWith` ou reconstrução.
   **Teste que valida estado inexistente:** ler o setup dos testes novos. Se ele semeia tabelas ou entidades que nenhum código de produção grava (conferir quem escreve nelas, excluindo páginas de debug e testes), o teste valida código morto, não a feature. Comparar com testes do mesmo arquivo que reproduzem o cenário de produção.

**Agente 2 — Offline-first / sync / persistência**
Princípio: para qualquer alteração envolvendo persistência ou sincronização, identificar fonte de verdade, comportamento offline e comportamento pós-reconexão.
Perguntas-guia: a operação é idempotente? Retry pode duplicar dados? Delete/update pode deixar registros órfãos? Estado local pode divergir do remoto? Stream pode emitir estado antigo? Operação parcialmente concluída deixa o que para trás?
Exemplos de foco: ordem das operações no sync, conflitos, cache stale, atualização local antes/depois da confirmação remota, rollback.
**Contratos de payload:** campo nullable → non-nullable: todos os produtores/consumidores garantem o campo? Enum expandido: comportamento para valor desconhecido? Wire format mudou: buscar fixtures/mocks/desserializadores existentes no repo.

**Agente 3 — Arquitetura & dependências**

- Camadas Clean Architecture respeitadas por feature em `lib/src/features/` (presentation/domain/data)
- Regra de dependência: `domain` não importa `presentation` nem `data`
- DI e rotas via `flutter_modular` registradas no módulo correto; sem service locator estático novo
- Código novo indo para `lib/src/` — sinalizar código novo adicionado ao legado `lib/app/` sem justificativa
- Lógica de negócio vazando para widget/página quando pertence a usecase/controller
- Conhecimento de feature vazando para componentes genéricos (pacotes, reconcilers, menus compartilhados)
- Conformidade com as regras de camadas dos AGENTS.md aplicáveis, regra a regra. Em `lib/offline_first` (seção "Layering"): DAO que recebe ou chama outro DAO; DAO sem SQL próprio; `*Commands` com método público de leitura; `*Queries` que escrevem; widget ou página chamando `*Commands`/`*Queries` direto; typedef, record ou projeção paralela a `ItemAggregate`/`ListAggregate`; vocabulário de UI (tile, card, page) dentro do subsistema
- API que recebe função como parâmetro quando o serviço já tem acesso ao dado; API cujo retorno não é o que o chamador usa (abrir o chamador e conferir)

**Agente 4 — Duplicação & reutilização (+ design system)**

- Buscar no repo lógica igual ou similar à adicionada na diff: utils, extensions, formatters, validators, mappers; apontar caminho exato do reutilizável existente
- Sinalizar padrões copiados/colados dentro da própria diff
- **Query ou SQL novo:** procurar no DAO dono da tabela e no `*_queries.dart` irmão uma leitura equivalente, por nome (`listOverdue`, `InRange`, `ByDay`, `ByItemIds`) e por semântica (mesmos joins e filtros). Se existir, a MR deve evoluir a existente ou remover a antiga junto com seus testes
- **Controller, serviço ou feature nova:** procurar irmãos no mesmo domínio (ex.: agenda, calendário e overdue compartilham `DayItemsController`; modal de desempenho do datetime usa as mesmas contagens de ocorrência). Listar métodos duplicados e fluxos que os irmãos ainda têm como `TODO`: o fluxo novo deve nascer no lugar compartilhado. Sem certeza de equivalência, virar pergunta ao autor
- Widget compartilhado forçado num cenário que não atende (excesso de flags/params só para caber) → sugerir widget específico
- Widget novo quando já existe equivalente em `lib/ds/`, `lib/tickenDS/`, `lib/src/core/widgets/`, `lib/app/widgets/`
- Tokens do DS: cores via `context.color` (nunca `Color(0x...)`), tipografia via `UlistFonts`, ícones via `UlistIcons`/`UlistSVGAssets`

**Agente 5 — Performance Flutter**
Princípio: reportar o que tem impacto plausível no caminho de renderização ou de dados; **não** reportar "falta const" isoladamente.

- Trabalho pesado dentro de `build()`: sorting/filtering, `.map().toList()` repetido, mappers percorrendo coleções grandes
- `FutureBuilder`/`StreamBuilder` recriando Future/Stream no build
- `setState`/listener no topo rebuildando árvore grande; escopo de `Observer`/`ValueListenableBuilder` maior que o necessário
- `shrinkWrap: true` / lista sem `ListView.builder` quando a coleção pode ser grande; nested scrollables
- `GlobalKey` sem necessidade; `Opacity`/clipping em caminho quente; animações causando rebuilds desnecessários
- Imagens de rede sem cache; assets pesados
- N+1 em consultas/materialização: leitura que dispara varredura maior que o necessário
- **Stream até o consumidor:** para toda stream ou query nova, abrir quem consome e ver o que ele usa do resultado. Se usa só um booleano, uma contagem ou um conjunto de ids, questionar a carga completa (aggregate, embeds, rotina). Pesa mais quando a tela é `wantKeepAlive` (a assinatura vive o app inteiro) e quando a stream recarrega em `db.changes`, que dispara em qualquer escrita no SQLite
- **Número sem origem:** cache, lote, chunk, debounce e limites novos precisam de justificativa. Se a MR não traz medição, o finding pede a medição (harness `sqflite_ffi` em `test/offline_first/helpers`); o agente não afirma ganho ou perda sem número

**Agente 6 — Estado & lifecycle**

- Stores/controllers com `@observable`/`@action`/`@computed` corretos; mutação de observable fora de action
- `.g.dart` coerente com o store alterado (esquecer `build_runner`)
- `Observer` presente onde widget lê observable; ausente onde não lê
- `await` sem guard de `mounted` antes de usar controller/context; efeito colateral dentro de `build`
- Listeners, controllers, notifiers, reactions e subscriptions sem dispose
- Race conditions de estado: resposta antiga sobrescrevendo mais nova (falta de token/guard)
- Estado que pertence ao controller vivendo em `setState`/variável de widget; cópia local de estado que deveria ser reativa
- **Fontes misturadas:** filtro ou derivação que combina dados do SQLite offline-first com stores MobX/Firebase (ex.: listas arquivadas no `ListStore`) precisa reagir às duas fontes. Conferir o `listenable`/`Observer` do widget: escrita no Firebase não emite `db.changes`, e vice-versa
- **Tempo como gatilho:** valor que depende de "hoje" (atrasados, agenda do dia) e só recalcula em evento de dados fica velho na virada do dia. Conferir se há `refresh()` ou gatilho de tempo, como os irmãos do domínio fazem

**Agente 7 — i18n, nomenclatura & MR hygiene**
i18n:

- Strings de UI hardcoded que deveriam estar nos `.arb` (`lib/app/l10n/`); concatenação que assume ordem de frase; formatação manual sobre texto localizado; `DateFormat` sem locale
- Chaves novas presentes nos 4 locales
  Nomenclatura:
- Arquivos `snake_case`, classes `PascalCase`, membros `camelCase`
- Nomes coerentes com o vocabulário do domínio já existente (buscar termos equivalentes); casing consistente dentro da feature
  MR hygiene (nunca bloqueia sozinho; vira Quick win ou Follow-up):
- Mudanças sem relação aparente com o objetivo da MR; configs locais vazadas (portas, emuladores); debug prints/bordas de debug; TODOs temporários; lockfiles/gerados/dependências alterados sem necessidade
  Segurança (mini-check):
- API keys, tokens, segredos, credenciais, URLs privadas na diff; dados sensíveis em logs/analytics. Não reportar config pública intencional (ex.: Firebase público) sem credencial realmente secreta.

### 5. Reviewer final

Após os agentes, um passo de validação com papel estrito — **pode remover findings, não pode inventar findings**. Valida só os candidatos a 🔴 e 🟡 (🔵 passa direto para a síntese).

Para cada finding:

1. O problema é realmente causado/exposto pela diff?
2. Existe evidência no código (o cenário descrito acontece)?
3. Arquivo/linha corretos?
4. Outro agente encontrou o mesmo (dedup)?
5. O linter já detecta?
6. Está no escopo da MR?
7. Severidade correta? Aplicar a "Regra especial para 🔴": sem fluxo concreto reproduzível, não é 🔴. Nunca subir 🔵 → 🔴 porque "parece importante"; rebaixar quando o cenário não é demonstrável.
8. A ação sugerida é concreta?
9. Finding de arquitetura ou duplicação candidato a bloquear: cita a regra (`AGENTS.md:linha`) ou o caminho do código existente que deveria ter sido reutilizado? Sem uma das duas citações, não bloqueia: vai para follow-up.

Descartar: preferência pessoal, sem evidência, fora da diff, duplicado, coberto pelo linter, refatoração fora do escopo.

Perguntas ao autor não passam pela validação de findings: o reviewer só deduplica e descarta as que já foram respondidas por um finding confirmado.

### 6. Sintetizar

- Deduplicar findings entre dimensões (mesmo arquivo:linha + mesmo problema → manter o da dimensão mais específica)
- **Descartar o que o linter já cobre**: `analysis_options.yaml` tem dart_code_metrics com severity error (ex.: `avoid-returning-widgets`, `prefer-single-widget-per-file`, `prefer-const-border-radius`, `no-magic-number`, `avoid-non-null-assertion`, `prefer-intl-name`). Se o achado é exatamente uma dessas regras, não reportar.
- Classificar cada finding por **destino da ação** (eixo primário do relatório):
  - **Bloqueia merge** — só entra aqui: bug provável, dado corrompido/órfão, regressão de comportamento, config local/segredo vazado na diff, string quebrada visível na UI, **violação de regra escrita em AGENTS.md** (citando `AGENTS.md:linha`), **duplicação de query, loader, aggregate ou fluxo que já existe** (citando o caminho do existente) e **versão antiga deixada sem uso ao lado do substituto**. Design system, nomenclatura e hygiene **nunca** bloqueiam sozinhos.
  - **Quick win** — fix mecânico de < 5 min, sem decisão de design (reverter linha, remover resíduo de debug, corrigir typo em .arb).
  - **Follow-up por tema** — todo o resto, agrupado por tema (Performance, Arquitetura, Duplicação, i18n, Design system, Estado/async, Nomenclatura...).
  - **Perguntas ao autor** — seção própria, fora dos findings; não afeta o veredito.
- Severidade (🔴/🟡/🔵) vira **tag no item**, não seção. Definição dura de 🔴: bug ou perda de dados; nunca estilo/estrutura. Bloqueador de arquitetura ou duplicação entra com 🟡.
- **🔴 exige cenário.** Todo 🔴 explica o cenário que dispara o problema: "Quando [condição], ao executar [ação], o sistema [comportamento incorreto]." Se o cenário não puder ser demonstrado a partir da diff + contexto do repo, rebaixar para 🟡 ou 🔵.
- Atribuir **ID** a cada finding (`B1`, `QW2`, `P3`, `D4`... prefixo da seção/tema) para citação e divisão de trabalho.
- Estimar **esforço** por finding bloqueador: P (< 1h), M (< 1 dia), G (> 1 dia).
- **Cap de ~8 itens abertos por tema**; excedente vai para apêndice em `<details>`. Nunca omitir silenciosamente — o apêndice lista tudo.

### 7. Gravar relatório

Escrever `mr_review.html` na raiz do repo (avisar o usuário que o arquivo não deve ser commitado) e resumir no terminal só o veredito + achados que bloqueiam merge.

O arquivo é **HTML puro, self-contained**: um único documento com CSS inline no `<head>`, sem JavaScript, sem dependências externas (fontes, CDN, imagens). Deve abrir corretamente no browser direto do disco (`file://`). Não gerar Markdown nem misturar sintaxe Markdown dentro do HTML (nada de `**negrito**`, `` `código` `` ou `- item` — usar `<strong>`, `<code>` e `<li>`). Escapar `<`, `>` e `&` em trechos de código.

## Formato do relatório

Estrutura fixa abaixo. Manter ids, classes e ordem das seções; preencher só o conteúdo. Seções vazias
("Bloqueia merge" sem itens, por exemplo) permanecem no documento com o texto "Nenhum item.".

O relatório é lido em varredura: o leitor precisa achar severidade, ID, sintoma e arquivo sem ler
parágrafos. Por isso:

- **Todo item tem título curto** (`<h3>` nos bloqueadores, `.item-title` nos demais): ≤ 10 palavras,
  orientado a sintoma ("Badge não atualiza na virada do dia"), nunca a causa técnica.
- **Localização em chip próprio** (`<code class="loc">`), fora do texto corrido. Várias localizações
  viram vários chips.
- **Uma ideia por bloco:** problema em `.what`, correção em `.fix`. Nada de problema + evidência + fix no
  mesmo parágrafo.
- **Severidade na borda e no selo:** a classe `sev-red|sev-yellow|sev-blue` vai no `<section>`/`<li>`
  e no `.badge`. Quick wins usam `qw`; perguntas, `question`.
- **Veredito lista os bloqueadores** (`<ol>` com link para cada ID e esforço), sem prosa corrida.
- **Barra `nav.toc`** com a contagem de itens de cada seção; o sumário linka cada tema ao seu `<h3 id="tema-…">`.
- Texto longo (alternativas de fix, listas de arquivos, evidência extra) vai em
  `<details class="more">` dentro do item, recolhido.

```html
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Review MR — <branch> → <target></title>
<style>
  :root { --red: #d1242f; --yellow: #bf8700; --blue: #0969da; --green: #1a7f37;
          --red-bg: #fff5f5; --yellow-bg: #fff8e5; --blue-bg: #f0f6ff; --green-bg: #f0fff4;
          --muted: #57606a; --border: #d0d7de; --bg: #f6f8fa; --text: #1f2328; }
  * { box-sizing: border-box; }
  body { font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
         max-width: 1000px; margin: 0 auto; padding: 0 1rem 4rem; color: var(--text); background: #fff; }
  h1 { font-size: 1.45rem; margin: 1.5rem 0 .25rem; line-height: 1.3; }
  h2 { font-size: 1.2rem; border-bottom: 2px solid var(--border); padding-bottom: .35rem;
       margin: 2.75rem 0 1rem; scroll-margin-top: 4rem; }
  h3 { font-size: 1.02rem; margin: 1.75rem 0 .4rem; scroll-margin-top: 4rem; }
  a { color: var(--blue); text-decoration: none; }
  a:hover { text-decoration: underline; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .86em;
         background: var(--bg); padding: .1em .35em; border-radius: 4px; }
  .meta { color: var(--muted); margin: 0 0 .75rem; }
  .goal { background: var(--bg); border-radius: 8px; padding: .75rem 1rem; margin: 0; }

  /* Navegação fixa: pular direto para a seção. */
  nav.toc { position: sticky; top: 0; z-index: 10; background: #fff;
            border-bottom: 1px solid var(--border); padding: .55rem 0; margin: 0 0 1rem;
            display: flex; flex-wrap: wrap; gap: .4rem; }
  nav.toc a { font-size: .85em; font-weight: 600; color: var(--text); background: var(--bg);
              border: 1px solid var(--border); border-radius: 999px; padding: .15rem .7rem; }
  nav.toc a:hover { text-decoration: none; border-color: var(--blue); }
  nav.toc .n { color: var(--muted); font-weight: 400; margin-left: .25rem; }

  /* Veredito: status + lista linkada dos bloqueadores. */
  .verdict { padding: 1rem 1.2rem; border-radius: 8px; border-left: 6px solid; }
  .verdict.blocked { border-color: var(--red); background: var(--red-bg); }
  .verdict.approved { border-color: var(--green); background: var(--green-bg); }
  .verdict .status { font-size: 1.1rem; font-weight: 700; display: block; margin-bottom: .35rem; }
  .verdict ol { margin: .5rem 0 0; padding-left: 1.3rem; }
  .verdict li { margin: .25rem 0; }
  .verdict .rest { color: var(--muted); margin: .6rem 0 0; }

  /* Sumário. */
  table { border-collapse: collapse; width: 100%; font-size: .95em; }
  th, td { border: 1px solid var(--border); padding: .4rem .7rem; text-align: left; }
  th { background: var(--bg); }
  td.num, th.num { text-align: center; width: 4.5rem; font-variant-numeric: tabular-nums; }
  td.zero { color: #afb8c1; }
  tr.total td { font-weight: 700; background: var(--bg); }

  /* Selos. */
  .badge { display: inline-block; font-size: .72em; font-weight: 700; padding: .12em .55em;
           border-radius: 999px; color: #fff; white-space: nowrap; }
  /* Severidade: o emoji já carrega a cor; sem pílula. */
  .badge.sev-red, .badge.sev-yellow, .badge.sev-blue { background: none; padding: 0; font-size: .95em; }
  .badge.effort { background: var(--muted); }
  .id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 700;
        font-size: .85em; color: var(--muted); }
  code.loc { display: inline-block; background: #eaeef2; color: var(--text); font-size: .8em;
             overflow-wrap: anywhere; margin: .1rem .3rem .1rem 0; }

  /* Bloqueadores: card completo. */
  .finding { border: 1px solid var(--border); border-left: 6px solid var(--border);
             border-radius: 8px; padding: 1rem 1.2rem; margin: 1rem 0; scroll-margin-top: 4rem; }
  .finding.sev-red { border-left-color: var(--red); }
  .finding.sev-yellow { border-left-color: var(--yellow); }
  .finding.sev-blue { border-left-color: var(--blue); }
  .finding .head { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; }
  .finding h3 { margin: 0; font-size: 1.05rem; }
  .finding .locs { margin: .45rem 0 .6rem; }
  .finding dl { display: grid; grid-template-columns: 5.5rem 1fr; gap: .45rem .9rem; margin: 0; }
  .finding dt { font-weight: 700; color: var(--muted); font-size: .85em; text-transform: uppercase;
                letter-spacing: .03em; padding-top: .15rem; }
  .finding dd { margin: 0; }
  .finding dd.fix { background: var(--green-bg); border-radius: 6px; padding: .45rem .7rem; }

  /* Itens curtos (quick wins, perguntas, follow-ups, apêndice). */
  .theme-context { color: var(--muted); margin: 0 0 .6rem; font-size: .95em; }
  ul.items { list-style: none; padding: 0; margin: 0; }
  li.item { border: 1px solid var(--border); border-left: 4px solid var(--border); border-radius: 6px;
            padding: .6rem .9rem; margin: .5rem 0; scroll-margin-top: 4rem; }
  li.item.sev-red { border-left-color: var(--red); }
  li.item.sev-yellow { border-left-color: var(--yellow); }
  li.item.sev-blue { border-left-color: var(--blue); }
  li.item.qw { border-left-color: var(--green); }
  li.item.question { border-left-color: #8250df; }
  .item-head { display: flex; flex-wrap: wrap; align-items: center; gap: .45rem; }
  .item-title { font-weight: 700; }
  li.item p { margin: .3rem 0 0; }
  li.item .what { color: var(--text); }
  li.item .fix { color: var(--text); }
  li.item .fix strong, li.item .found strong { color: var(--green); }
  li.item .found { color: var(--muted); font-size: .93em; }
  li.item input[type=checkbox] { margin: 0; }
  details { margin: 1rem 0; }
  summary { cursor: pointer; font-weight: 700; }
  details.more summary { font-weight: 400; color: var(--blue); font-size: .9em; margin-top: .3rem; }
</style>
</head>
<body>

<h1>Review MR — <branch> → <target></h1>
<p class="meta"><data> · <N> arquivos · +X/−Y linhas</p>
<p class="goal"><strong>Objetivo da MR:</strong> <1-2 frases></p>

<nav class="toc">
  <a href="#veredito">Veredito</a>
  <a href="#bloqueia">🚫 Bloqueia<span class="n"><N></span></a>
  <a href="#quick-wins">⚡ Quick wins<span class="n"><N></span></a>
  <a href="#perguntas">❓ Perguntas<span class="n"><N></span></a>
  <a href="#follow-ups">Follow-ups<span class="n"><N></span></a>
  <a href="#apendice">Apêndice<span class="n"><N></span></a>
</nav>

<h2 id="veredito">Veredito</h2>
<div class="verdict blocked|approved">
  <span class="status">🚫 Bloqueado</span> | <span class="status">✅ Pode mergear</span>
  <!-- Se bloqueado: um <li> por bloqueador, na mesma ordem da seção "Bloqueia merge". -->
  <ol>
    <li><a href="#B1"><span class="id">B1</span></a> <título curto> <span class="badge effort">P</span></li>
  </ol>
  <p class="rest"><1 frase: o que vira follow-up.></p>
</div>

<h2 id="sumario">Sumário</h2>
<!-- Nome do tema linka para o <h3> do tema. Zero é "–" com class="num zero". -->
<table>
  <thead>
    <tr><th>Tema</th><th class="num">🔴</th><th class="num">🟡</th><th class="num">🔵</th>
        <th class="num">QW</th></tr>
  </thead>
  <tbody>
    <tr><td><a href="#tema-corretude">Corretude</a></td><td class="num">1</td><td class="num">2</td>
        <td class="num zero">–</td><td class="num zero">–</td></tr>
    <tr class="total"><td>Total</td><td class="num">…</td><td class="num">…</td><td class="num">…</td>
        <td class="num">…</td></tr>
  </tbody>
</table>

<h2 id="bloqueia">🚫 Bloqueia merge</h2>
<!-- Ordenado por impacto. Card completo por item. -->
<section class="finding sev-red" id="B1">
  <div class="head">
    <span class="badge sev-red">🔴</span><span class="id">B1</span>
    <h3><título curto orientado a sintoma, ≤ 10 palavras></h3>
    <span class="badge effort">Esforço: P|M|G</span>
  </div>
  <div class="locs"><code class="loc">arquivo.dart:123</code> <code class="loc">outro.dart:45</code></div>
  <dl>
    <dt>Impacto</dt><dd><o que o usuário/dev sofre, 1-2 frases>.</dd>
    <dt>Causa</dt><dd><1-2 frases técnicas; regra violada como <code>AGENTS.md:linha</code>>.</dd>
    <dt>Fix</dt><dd class="fix"><sugestão concreta, 1-2 frases>.</dd>
  </dl>
  <!-- Opcional: evidência longa (alternativas de fix, lista de arquivos) vai recolhida. -->
  <details class="more"><summary>Detalhes</summary><p>…</p></details>
</section>

<h2 id="quick-wins">⚡ Quick wins (&lt; 5 min cada)</h2>
<ul class="items">
  <li class="item qw" id="QW1">
    <div class="item-head"><input type="checkbox"><span class="id">QW1</span>
      <span class="item-title"><título curto></span><code class="loc">arquivo.dart:83</code></div>
    <p class="fix"><fix mecânico, 1 frase></p>
  </li>
</ul>

<h2 id="perguntas">❓ Perguntas ao autor</h2>
<ul class="items">
  <li class="item question" id="Q1">
    <div class="item-head"><span class="id">Q1</span>
      <span class="item-title"><pergunta direta, 1 frase></span><code class="loc">arquivo.dart:40</code></div>
    <p class="found"><strong>Encontrado:</strong> <code>caminho/existente.dart:12</code> — <o que parece equivalente></p>
  </li>
</ul>

<h2 id="follow-ups">Follow-ups por tema</h2>

<h3 id="tema-<slug>"><Tema> — <apelido do padrão, se houver> <span class="n">(<N>)</span></h3>
<p class="theme-context"><≤ 2 frases de contexto comum; os itens abaixo não repetem esse contexto.></p>
<ul class="items">
  <li class="item sev-yellow" id="P1">
    <div class="item-head"><span class="badge sev-yellow">🟡</span><span class="id">P1</span>
      <span class="item-title"><título curto orientado a sintoma></span><code class="loc">arquivo.dart:130</code></div>
    <p class="what"><problema, 1-2 frases></p>
    <p class="fix"><strong>Fix:</strong> <sugestão, 1 frase></p>
  </li>
</ul>

<h2 id="apendice">Apêndice — sugestões menores</h2>
<details>
  <summary>🔵 <N> itens (nomenclatura, polimento opcional)</summary>
  <ul class="items">
    <li class="item sev-blue" id="S1">
      <div class="item-head"><span class="badge sev-blue">🔵</span><span class="id">S1</span>
        <span class="item-title"><título curto></span><code class="loc">arquivo.dart:12</code></div>
      <p class="fix"><strong>Fix:</strong> <sugestão></p>
    </li>
  </ul>
</details>

<h2 id="sem-achados">Dimensões sem achados</h2>
<ul>
  <li><dimensão> — <1 frase do que foi verificado></li>
</ul>

</body>
</html>
```

## Rules

- **pt-BR**, direto, sem elogios — só achados acionáveis
- Todo finding cita `arquivo:linha` e tem ID único; o ID também é o atributo `id` do elemento HTML
  (`<section id="B1">`, `<li id="QW1">`) para permitir link direto (`mr_review.html#B1`)
- Tamanho máximo por campo: título ≤ 10 palavras; `Impacto`, `Causa`, `Fix` e `.what` ≤ 2 frases cada;
  `.fix` de item curto ≤ 1 frase. O que passar disso vai para `<details class="more">`
- Saída é HTML self-contained (CSS inline, sem JS, sem recursos externos); nunca Markdown
- `confidence` e `evidência` são internos ao pipeline (agentes → reviewer final); não aparecem no relatório
- Impacto antes de causa nos itens bloqueadores: escrever primeiro o sintoma que o usuário/dev vê, depois a explicação técnica
- Frases completas e curtas em todos os campos; estilo telegráfico só no apêndice
- Não reportar o que `flutter analyze`/dart_code_metrics já pega
- Não sugerir refatoração fora do escopo da diff: apontar duplicação existente é ok; pedir reescrita de legado que a diff só tocou de leve, não
- Regras de arquitetura do projeto: `AGENTS.md` raiz e os aninhados dos diretórios tocados (fonte de verdade)
- Referência de convenções de UI do projeto: `.devin/rules/ulist.md` (context.color, UlistFonts, UlistIcons, widgets decompostos em classes privadas — nunca métodos que retornam Widget —, funções < 20 linhas)
- Achado incerto: reportar como 🔵 com a dúvida explícita, não inflar severidade
