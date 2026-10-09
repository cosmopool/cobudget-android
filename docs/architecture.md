# Margem — arquitetura

Status: **decidido** em 2026-10-09. O que está em §11 espera resposta, uma por vez.
Produto: [spec.md](spec.md). Vocabulário: [glossary.md](glossary.md). Visual: [design_system.html](design_system.html).
Estilo: Codin' Dirty (CLAUDE.md): poucas camadas, funções grandes onde está o miolo, testes de integração.

## 1. Decidido

| # | Decisão | Por quê |
|---|---|---|
| D1 | **Projeto novo** no mesmo repo, substituindo `app/`. applicationId `com.kaiodelphino.margem`. | Instala ao lado do cobudget: dá para importar e comparar antes de desinstalar o antigo. |
| D2 | Do código atual, entram **captura, dedupe e extração** (Nubank + genérico, `parseBrl`) e os casos de teste deles, adaptados. O resto é refeito. | É o código mais testado contra notificações reais. |
| D3 | **Importação única** de um backup do cobudget (`.db`), em Ajustes. Traz recados, fontes, marcadores e apelidos. Lançamentos antigos não têm verba: os recados deles voltam à Pauta. | O histórico de recados não se perde. O importador é código descartável. |
| D4 | A importação passa cada recado pela **mesma regra de dedupe da captura**. | Os dois apps capturam ao mesmo tempo; a mesma regra evita recados duplicados. |
| D5 | **SQLite direto** (`SQLiteOpenHelper`, SQL à mão), sem Room, SQLDelight ou KSP. | Sem código gerado: o que se lê é o que roda. Os testes cobrem cada consulta. |
| D6 | **Reatividade por um contador global**: `Db.version: StateFlow<Long>`, incrementado ao fim de toda escrita, por um único `write { }`. | Uma regra só, impossível de esquecer por tabela. As consultas são pequenas. |
| D7 | **Uma linha por parcela**, cada uma com a sua **data** (compra + k−1 meses). À vista = 1 parcela. | "Gasto do mês" vira um `SUM` sobre parcelas. A data permite mudar o dia de início do mês sem regravar nada. |
| D8 | **Cota: uma linha por verba e mês**, criada **sob demanda** ao carregar o mês, copiando a anterior e preenchendo meses pulados. | Consulta trivial; sem job agendado; funciona com o app semanas fechado. |
| D9 | **Passo editado** gravado por verba + data de início da semana. Só as semanas editadas têm linha. | O passo padrão é calculado. |
| D10 | Mudar o dia de início da semana ou do mês **apaga as edições de passo do mês atual em diante** (o app avisa antes). Meses passados são recalculados com o dia novo; edição que não casar com uma semana nova é ignorada. | As semanas mudam; converter edições seria difícil de explicar e testar. |
| D11 | **Centavos dos passos**: todos são arredondados para baixo; a **última semana não editada** recebe a diferença. | A soma dos passos é sempre igual à cota. |
| D12 | **O SQL lê, uma função Kotlin calcula**: `budgetMonth(...)` produz gasto, margem, passos, ritmo ideal, estado e projeção. Telas, widgets e Leitura usam a mesma função. | Fácil de testar e de depurar com breakpoint. |
| D13 | **Ajustes e fontes em tabelas** do próprio SQLite. | Entram no backup e na mesma reatividade: mudar o início do mês recalcula tudo. |
| D14 | **Backup = cópia do arquivo do banco**: checkpoint do WAL + copiar o `.db`; importar substitui o arquivo e reabre. | Sem formato próprio para manter. |
| D15 | **UI em Compose Foundation, sem Material 3.** Tokens do book num objeto `Margem` e cerca de 10 componentes próprios. | Fiel ao book, sem desligar padrões do Material. |
| D16 | **Fontes empacotadas** em `res/font` (Newsreader, Source Sans 3; só os pesos usados). | Funciona offline desde o primeiro uso, inclusive nos widgets. |
| D17 | **Gráficos em Canvas próprio**, um Composable por gráfico. | São 4 gráficos simples, no estilo do book; sem dependência. |
| D18 | **Navegação sem biblioteca**: `sealed interface Screen`, pilha em `rememberSaveable`, um `when` e `BackHandler`. | Explícita; depura fácil. |
| D19 | **Sem ViewModel.** Cada tela observa `Db.version` e consulta o Db direto; formulários em `rememberSaveable`. | O banco já é o estado; menos classes. |
| D20 | **Widgets em Jetpack Glance**, redimensionáveis (`SizeMode.Responsive`). | Mesmo jeito de pensar da UI; layout por tamanho. |
| D21 | **Widgets atualizam** a cada escrita (`updateAll` dentro do `write { }`) e pelo `updatePeriodMillis` do sistema para a virada do dia. | Sem agendamento próprio. Aceito: o widget pode mostrar o ritmo do dia anterior por um tempo. |
| D22 | **Um módulo, poucos arquivos grandes** por assunto (§9): dados e regras na raiz, telas e widgets de home screen em `ui/`, tema e componentes em `widgets/`. Sem camadas repository/usecase. | Codin' Dirty. |
| D23 | **Testes**: integração com Robolectric + SQLite real em arquivo temporário; `budgetMonth` com cenários de calendário; testes de UI Compose para lançar e editar passos. | `.agents/rules/testing.md`. |
| D24 | **minSdk 29** (Android 10). | Mesmo do cobudget; Glance e `java.time` sem desugaring. |
| D25 | **Captura grava direto no Db**: o `NotificationListenerService` chama `capture(db, sbn)`. | Sem fila; a mesma escrita incrementa a versão e atualiza os widgets. |

## 2. Componentes

```
NotificationListenerService ──capture()──┐
Import (backup cobudget) ──importOld()───┤
Telas (Compose) ──ações──────────────────┼──► Db.write { } ──► SQLite (margem.db)
                                         │        │
                                         │        ├─► version++ ──► telas reconsultam
                                         │        └─► Glance updateAll ──► widgets
Telas / widgets / Leitura ◄── consultas ─┘
        └── budgetMonth(linhas do mês, hoje, ajustes) ──► BudgetMonth
```

- Um único `Db` (subclasse de `SQLiteOpenHelper`, WAL ligado), criado no `Application` e usado pela Activity, pelo serviço e pelos widgets (mesmo processo).
- Leituras e escritas rodam em `Dispatchers.IO`.

## 3. Tipos

Distintos por construção (CLAUDE.md):

```kotlin
@JvmInline value class Cents(val v: Long)
@JvmInline value class NoticeId(val v: Long)
@JvmInline value class TransactionId(val v: Long)
@JvmInline value class BudgetId(val v: Long)
@JvmInline value class TagId(val v: Long)
```

- Datas são `LocalDate`/`LocalTime`, gravadas como texto ISO (`2026-10-09`, `11:43`): legíveis e ordenáveis em SQL.
- **Mês** é identificado por `YearMonth` do mês do calendário em que ele começa (`2026-10` = de D/out até a véspera de D/nov).
- Zero é inicialização: `0` em vez de `NULL` para "sem referência" (ex.: lançamento manual tem `notice_id = 0`).

## 4. Banco (esboço do schema)

Migrações: `PRAGMA user_version`; `onUpgrade` com um `if (old < N)` por versão.

```sql
-- Ajustes: dia de início do mês e da semana, tema, ordem das verbas (aba e widget), pauta_seen_at.
CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- Fontes: apps cujas notificações viram recados.
CREATE TABLE sources(package TEXT PRIMARY KEY, label TEXT NOT NULL, added_at INTEGER NOT NULL);

-- Recados: a notificação como chegou. Pendente = não riscado e não referenciado (§5).
CREATE TABLE notices(
  id INTEGER PRIMARY KEY,
  package TEXT NOT NULL, app_label TEXT NOT NULL,
  key TEXT NOT NULL, posted_at INTEGER NOT NULL, when_ms INTEGER NOT NULL,
  title TEXT NOT NULL, text TEXT NOT NULL, big_text TEXT NOT NULL, sub_text TEXT NOT NULL, text_lines TEXT NOT NULL,
  category TEXT NOT NULL, channel_id TEXT NOT NULL, extras_json TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  dismissed INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE budgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, position INTEGER NOT NULL);

-- Cota de cada mês (D8).
CREATE TABLE budget_months(budget_id INTEGER NOT NULL, month TEXT NOT NULL, limit_cents INTEGER NOT NULL,
  PRIMARY KEY(budget_id, month));

-- Passos editados (D9).
CREATE TABLE week_steps(budget_id INTEGER NOT NULL, week_start TEXT NOT NULL, cents INTEGER NOT NULL,
  PRIMARY KEY(budget_id, week_start));

-- Lançamentos. notice_id = 0: manual. corrected = emendado.
CREATE TABLE transactions(
  id INTEGER PRIMARY KEY,
  notice_id INTEGER NOT NULL, budget_id INTEGER NOT NULL,
  merchant TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL,
  total_cents INTEGER NOT NULL, corrected INTEGER NOT NULL DEFAULT 0
);

-- Parcelas (D7): k = 1..n; a última absorve os centavos.
CREATE TABLE installments(transaction_id INTEGER NOT NULL, k INTEGER NOT NULL, date TEXT NOT NULL, cents INTEGER NOT NULL,
  PRIMARY KEY(transaction_id, k));

-- Estorno: só um registro de que a compra não conta. O alvo é um lançamento OU um recado ainda não lançado.
CREATE TABLE refunds(estorno_notice_id INTEGER PRIMARY KEY, transaction_id INTEGER NOT NULL, notice_id INTEGER NOT NULL);

CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE);
CREATE TABLE transaction_tags(transaction_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(transaction_id, tag_id));

CREATE TABLE merchant_aliases(merchant_key TEXT PRIMARY KEY, alias TEXT NOT NULL);
```

Índices conforme as consultas (ex.: `installments(date)`, `transactions(budget_id)`, `notices(key)`, `notices(posted_at)`).

## 5. Regras no banco

- **Recado pendente** = `dismissed = 0` e nenhuma destas referências: `transactions.notice_id`, `refunds.estorno_notice_id`, `refunds.notice_id`. Processado é derivado, nunca gravado.
- **Gasto do mês de uma verba** = `SUM(installments.cents)` das parcelas com data dentro do mês, de lançamentos da verba **não estornados** (sem linha em `refunds` apontando para eles). Estorno de parcelada tira todas as parcelas.
- **Estorno de recado não lançado**: os dois recados (compra e estorno) saem da Pauta e nada conta.
- **Desfazer estorno** = apagar a linha de `refunds`: a compra volta a contar e os recados voltam a ser pendentes.
- **Apagar verba**: apaga lançamentos, parcelas, ligações de marcador, cotas e passos dela; os recados voltam à Pauta.
- **Apagar marcador**: apaga os lançamentos que ficam sem marcador (recados voltam à Pauta).
- **Lançar**: exige verba e ≥1 marcador; grava lançamento + parcelas + marcadores numa transação; `corrected` quando difere da sugestão.
- **Estado "novo" do recado**: `posted_at > settings.pauta_seen_at`; abrir a Pauta atualiza `pauta_seen_at`.

## 6. Cálculo do mês

```kotlin
fun budgetMonth(
    month: YearMonth, monthStartDay: Int, weekStartDay: DayOfWeek, today: LocalDate,
    limit: Cents, editedSteps: Map<LocalDate, Cents>, spentByDay: Map<LocalDate, Cents>,
): BudgetMonth
```

Uma consulta traz `limit` (garantindo a linha do mês, D8), os passos editados e o gasto por dia; a função faz o resto:

1. **Janela do mês**: do dia D até a véspera de D no mês seguinte (D inexistente → último dia).
2. **Semanas**: de calendário, a partir de `weekStartDay`, cortadas na janela.
3. **Passos**: editados ficam; os demais dividem `limit − Σ editados` proporcionalmente aos dias, arredondados para baixo; a última semana não editada recebe a diferença (D11).
4. **Ritmo ideal** (hoje) = passos das semanas fechadas + passo da semana atual × dias corridos dela (com hoje) ÷ dias dela.
5. **Estado**: gasto > limit → Fora da margem; gasto > ritmo ideal → Acima do ritmo; senão No ritmo.
6. **Projeção** = gasto × (Σ passos do mês ÷ ritmo ideal de hoje); sem projeção quando o ritmo ideal de hoje é 0.

Saída:

```kotlin
class BudgetMonth(
    val start: LocalDate, val end: LocalDate, val limit: Cents, val spent: Cents, val remaining: Cents,
    val weeks: List<Week>, val ideal: Cents, val status: PaceStatus, val projection: Cents,
)
class Week(val start: LocalDate, val end: LocalDate, val step: Cents, val edited: Boolean, val spent: Cents)
enum class PaceStatus { ON_PACE, AHEAD, OVERRUN }
```

Validação de edição de passo (parse na fronteira): aceita no máximo `limit − Σ outras editadas` e nunca a última semana não editada (spec: uma semana fica sempre automática).

## 7. UI

- **Activity única**: `MainActivity` com `setContent { MargemTheme { App(db) } }`.
- **Navegação** (D18):

```kotlin
sealed interface Screen {
    data object Budgets : Screen; data object Inbox : Screen; data object Transactions : Screen
    data object Insights : Screen; data object Settings : Screen
    data class Budget(val id: BudgetId, val month: YearMonth) : Screen
    data class Post(val notice: NoticeId) : Screen        // lançar um recado
    data class Edit(val id: TransactionId) : Screen       // editar lançamento
    data object NewTransaction : Screen                   // lançamento manual
    data object Dismissed : Screen                        // Riscados
}
```

  Abrir pelo widget: Intent com extra que vira a aba inicial.
- **Dados na tela** (D19): um helper `@Composable fun <T> query(db, block: (Db) -> T): T?` que observa `db.version` e roda `block` em IO. Ações chamam funções de escrita num `rememberCoroutineScope`.
- **Tema** (D15): `MargemTheme` fornece `Margem.colors`, `Margem.type`, `Margem.space` por `CompositionLocal`; claro/escuro conforme o ajuste (sistema · claro · escuro).
- **Componentes** (`widgets/Components.kt`): botão, campo de valor, chip de marcador, cartão de recado, régua, seletor de mês, editor de passos, barra de abas, diálogo, snackbar.
- **Gráficos** (`ui/Insights.kt`, D17): verbas por mês, ritmo do mês, ranking, calendário de gastos.

## 8. Widgets

- Dois `GlanceAppWidget`: **Verbas** e **Pauta**, redimensionáveis de 2×2 a 4×4.
  - Verbas: 2×2 = 1 verba, 4×2 = 3, 4×4 = 6, na ordem escolhida em Ajustes.
  - Pauta: 2×2 = contagem, 4×2 = contagem + 2 recados, 4×4 = contagem + 5.
- Leem o mesmo `Db` e usam `budgetMonth`. Tema segue o ajuste do app.
- Toque abre a aba correspondente. Atualização: D21.

## 9. Arquivos

Pacote `com.kaiodelphino.margem`, com dois subpacotes:

| Arquivo | Conteúdo |
|---|---|
| `MargemApp.kt` | `Application` com o `Db`. |
| `Db.kt` | Schema, migrações, `write { }`, `version`, todas as consultas e escritas. |
| `Types.kt` | Value classes, `PaceStatus`. |
| `Capture.kt` | `NotificationListenerService`, `capture()`, dedupe. |
| `Extract.kt` | Leitura de valor/estabelecimento/data; `parseBrl`. |
| `Month.kt` | `budgetMonth()`. |
| `Backup.kt` | Exportar/importar o `.db`. |
| `Import.kt` | Importação única do cobudget (descartável). |
| `ui/MainActivity.kt` | Activity, `Screen`, navegação. |
| `ui/Budgets.kt` · `ui/Inbox.kt` · `ui/Transactions.kt` · `ui/Insights.kt` · `ui/Settings.kt` | Uma por aba, com suas sub-telas. |
| `ui/HomeWidgets.kt` | Os dois widgets de home screen (Glance). |
| `widgets/Theme.kt` · `widgets/Components.kt` | Tokens e componentes do book. |

Dependências: Compose (foundation, ui, activity-compose), Glance appwidget; testes: JUnit, Robolectric, androidx.test core, compose ui-test. Sem Room, Material 3, ViewModel, Navigation ou bibliotecas de gráfico.

## 10. Testes

- **Banco** (Robolectric + SQLite em arquivo temporário por teste): captura → Pauta → lançar → mês da verba; riscar e trazer de volta; estorno de lançamento e de recado; parcelas atravessando meses; virada (linha de cota criada sob demanda, meses pulados); mudar dia de início (edições descartadas); apagar verba e marcador; importação do cobudget com dedupe.
- **`budgetMonth`**: semanas parciais, passos editados e bloqueios, centavos, dia de início 29–31, verba criada no meio do mês, estados e projeção.
- **Extração**: os casos atuais de `ExtractTest`/`CaptureTest`, portados.
- **UI Compose**: fluxo de lançar (verba + marcador obrigatórios, parcelas, prévia) e editor de passos.

## 11. Em aberto

| # | Questão |
|---|---|
| — | Nenhuma no momento. |
