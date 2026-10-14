# Margem — arquitetura

Status: **decidido** em 2026-10-09; revisado em 2026-10-14 (KMP + UI nativa, D26–D31; bloco 1 do `shared`, D32–D33; bloco 2 do `androidApp`, D1, D16, D23, D28, D33, §7). O que está em §11 espera resposta, uma por vez.
Produto: [spec.md](spec.md). Vocabulário: [glossary.md](glossary.md). Visual: [design_system.html](design_system.html).
Estilo: Codin' Dirty (CLAUDE.md): poucas camadas, funções grandes onde está o miolo, testes de integração.

## 1. Decidido

| # | Decisão | Por quê |
|---|---|---|
| D1 | **Projeto novo** no mesmo repo: os módulos `shared` e `androidApp` (D26) substituem `app/`, removido no bloco 2 (D33). applicationId `com.kaiodelphino.margem`. | App novo com id próprio. Sem comparação lado a lado com o cobudget, que não será mais usado; o histórico dele entra pela importação (D3, D4). |
| D2 | Do código atual, entram **captura, dedupe e extração** (Nubank + genérico, `parseBrl`) e os casos de teste deles, adaptados. O resto é refeito. | É o código mais testado contra notificações reais. |
| D3 | **Importação única** de um backup do cobudget (`.db`), em Ajustes. Traz recados, fontes, marcadores e apelidos. Lançamentos antigos não têm verba: os recados deles voltam à Pauta. | O histórico de recados não se perde. O importador é código descartável. |
| D4 | A importação passa cada recado pela **mesma regra de dedupe da captura**. | Os dois apps capturam ao mesmo tempo; a mesma regra evita recados duplicados. |
| D5 | **SQLite direto pela API KMP do `androidx.sqlite`** (`SQLiteDriver`, `SQLiteConnection`), SQL à mão, sem Room, SQLDelight ou KSP. | Sem código gerado: o que se lê é o que roda. Roda no `commonMain`. Os testes cobrem cada consulta. |
| D6 | **Reatividade por um contador global**: `Db.version: StateFlow<Long>`, incrementado ao fim de toda escrita por um único `write { }`, que em seguida chama `onWrite` (D21). Única exceção: `ensureMonths` (D30). | Uma regra só, impossível de esquecer por tabela. As consultas são pequenas. |
| D7 | **Uma linha por parcela**, cada uma com a sua **data** (compra + k−1 meses; se, com dia de início 29–31, essa data cair fora do k-ésimo mês, vai para o dia mais próximo dentro dele). À vista = 1 parcela; no máximo 400. | "Gasto do mês" vira um `SUM` sobre parcelas. A data permite mudar o dia de início do mês sem regravar nada. |
| D8 | **Cota: uma linha por verba e mês**, criada **sob demanda** ao carregar o mês (D30), copiando a anterior e preenchendo meses pulados. | Consulta trivial; sem job agendado; funciona com o app semanas fechado. |
| D9 | **Passo editado** gravado por verba + data de início da semana. Só as semanas editadas têm linha. | O passo padrão é calculado. |
| D10 | Mudar o dia de início da semana ou do mês **apaga as edições de passo do mês atual em diante** (o app avisa antes). Meses passados são recalculados com o dia novo; edição que não casar com uma semana nova é ignorada. | As semanas mudam; converter edições seria difícil de explicar e testar. |
| D11 | **Centavos dos passos**: todos são arredondados para baixo; a **última semana não editada** recebe a diferença. | A soma dos passos é sempre igual à cota. |
| D12 | **O SQL lê, uma função Kotlin calcula**: `budgetMonth(...)` produz gasto, margem, passos, ritmo ideal, estado e projeção. `budgetMonth` e a extração são `internal` no `shared`: telas, widgets e Leitura recebem `BudgetMonth` e `Parsed` prontos do `Db` (`budgetsOverview`, `budgetDetail`, `pending`, `transaction`). | Fácil de testar e de depurar com breakpoint. Uma API pública só (o `Db`), sem duas formas de chegar ao mesmo número. |
| D13 | **Ajustes e fontes em tabelas** do próprio SQLite. | Entram no backup e na mesma reatividade: mudar o início do mês recalcula tudo. |
| D14 | **Backup = cópia do arquivo do banco**, feita no `shared` com `kotlinx-io`: exportar = `VACUUM INTO` o caminho dado; importar fecha o escritor e os leitores, substitui o arquivo (apagando `-wal`/`-shm`), reabre, migra e incrementa `version`. A escolha do arquivo (SAF) fica no `androidApp`, que entrega um caminho local. | Sem formato próprio para manter; o mesmo código nos dois apps e nos testes da JVM. |
| D15 | **UI em Compose Foundation, sem Material 3.** Tokens do book num objeto `Margem` e cerca de 10 componentes próprios. | Fiel ao book, sem desligar padrões do Material. |
| D16 | **Fontes variáveis empacotadas** em `res/font`, 4 arquivos do `google/fonts` (OFL): Newsreader e Newsreader Italic (`opsz`, `wght`), Source Sans 3 e Source Sans 3 Italic (`wght`). Cada estilo do book vira um `Font` com `FontVariation` (`wght` e, na Newsreader, `opsz` = corpo em sp, limitado a 6–72). Pesos do book: Newsreader 300–600 e itálico 400/500; Source Sans 3 400–700. | Funciona offline desde o primeiro uso, inclusive nos widgets. Um arquivo por família e estilo cobre todos os pesos, e o eixo óptico faz o mesmo desenho ficar robusto pequeno e fino grande, como no book. |
| D17 | **Gráficos em Canvas próprio**, um Composable por gráfico. | São 4 gráficos simples, no estilo do book; sem dependência. |
| D18 | **Navegação sem biblioteca**: `sealed interface Screen`, pilha em `rememberSaveable`, um `when` e `BackHandler`. | Explícita; depura fácil. |
| D19 | **Sem ViewModel.** Cada tela observa `Db.version` e consulta o Db direto; formulários em `rememberSaveable`. | O banco já é o estado; menos classes. |
| D20 | **Widgets em Jetpack Glance**, redimensionáveis (`SizeMode.Responsive`). | Mesmo jeito de pensar da UI; layout por tamanho. |
| D21 | **Widgets atualizam** a cada escrita: o `Db` recebe `onWrite: () -> Unit` no construtor e o `MargemApp` passa um lambda que chama `updateAll`. Na virada do dia, pelo `updatePeriodMillis` do sistema. | O `shared` não conhece Glance; o elo escrita → widget fica explícito e segue no debugger. Aceito: o widget pode mostrar o ritmo do dia anterior por um tempo. |
| D22 | **Dois módulos (D26), poucos arquivos grandes** por assunto (§9). Sem camadas repository/usecase. | Codin' Dirty. |
| D23 | **Testes**: `commonTest`, rodando na JVM (`jvmTest`) com SQLite real em arquivo temporário, sem Robolectric. **Integração pela API pública** (`Db`, `capture`, `parseBrl`); teste unitário (enxergando `internal`) só em três contratos puros: `budgetMonth`, extração e SHA-256. Robolectric só no `androidApp`, para a conversão de `StatusBarNotification` e os testes de UI Compose (lançar, editar passos); nesses o `Db` usa `AndroidSQLiteDriver` (exceção à D28). | `.agents/rules/testing.md`. Testes do miolo rápidos e fora do emulador, e que sobrevivem a refatorações. |
| D24 | **minSdk 29** (Android 10). | Mesmo do cobudget; Glance sem restrições. |
| D25 | **Captura grava direto no Db**: o `NotificationListenerService` converte o `StatusBarNotification` em `RawNotice` (parse na fronteira) e chama `capture(db, raw)` do `shared`, onde ficam extração e dedupe. | Sem fila; a mesma escrita incrementa a versão e atualiza os widgets. |
| D26 | **Kotlin Multiplatform + UI nativa.** `shared` (KMP) tem dados e regras: tipos, Db, `budgetMonth`, extração, dedupe, `capture()`. `androidApp` tem Compose, Glance, o serviço de notificações, backup e importação. **Na v1 só o app Android é codado**; o iOS entra depois como `iosApp/` (Xcode + SwiftUI), consumindo o `shared`. | Android 100% nativo; a lógica fica pronta para o iOS sem custo de performance. |
| D27 | **Alvos do `shared`**: Android pelo plugin `com.android.kotlin.multiplatform.library` (`androidLibrary { }`, não `androidTarget`), `jvm`, `iosArm64`, `iosSimulatorArm64`. Os alvos iOS são declarados na v1 mesmo sem app. | Plugin feito para biblioteca KMP (uma variante só, sem build types). Com iOS declarado, o compilador barra `java.*` e `android.*` no `commonMain` desde o primeiro dia. `jvm` roda os testes sem emulador. |
| D28 | **`BundledSQLiteDriver` em tudo** (Android, JVM dos testes, iOS). A conversão UTF-16↔UTF-8 do SQLite perde U+FEFF no início, U+FFFE, U+FFFF e surrogates soltos; por isso todo texto é **normalizado na entrada** (esses viram U+FFFD) e o que se lê é sempre o que foi gravado. **Exceção**: os testes Robolectric do `androidApp` usam `AndroidSQLiteDriver` (`testImplementation` de `androidx.sqlite:sqlite-framework`). | A mesma versão do SQLite em teste e em produção; um caminho de código só. Aceito: ~1 MB de lib nativa por ABI no APK. A exceção existe porque o AAR Android do `sqlite-bundled` só traz `.so` de Android, e o Robolectric roda na JVM do Mac; a semântica do SQL continua coberta pelo `shared` com Bundled (D23), e o `androidApp` só testa a fronteira (`StatusBarNotification` → `capture`) e a UI. Descartados: testes instrumentados no emulador (lentos, fora do `./gradlew test`) e o Robolectric carregando a `.dylib` do artefato JVM (frágil: classpath e lib nativa à mão). |
| D29 | **Um escritor + 2 leitores.** Escrita numa conexão própria sob `Mutex`; leitura em 2 conexões com `PRAGMA query_only = 1` num `Channel`: `read { }` pega uma, usa e devolve. WAL ligado. | Com WAL, leitura não espera escrita: tela, widget e captura não se bloqueiam. 2 leitores bastam para esses três. |
| D30 | **`ensureMonths(month)`** roda no escritor **sem incrementar `version`**: `INSERT OR IGNORE` copiando a cota anterior e preenchendo meses pulados. Telas e widgets chamam antes de consultar o mês. | Leitores são `query_only`. As linhas criadas só materializam a cota que já valia: nada na tela muda, e incrementar `version` faria a tela reconsultar em loop. |
| D31 | **Datas com `kotlinx-datetime`** (`LocalDate`, `LocalTime`, `YearMonth`, `DayOfWeek`) no `shared` e no `androidApp`. Sem `java.time`. | `java.time` não existe no `commonMain`; um tipo de data só nos dois módulos. |
| D32 | **`content_hash` = SHA-256 escrito à mão no `commonMain`** (sem `java.security`), sobre título, texto, texto longo, subtexto e linhas, cada um como tamanho em bytes (4 bytes) + UTF-8. | Sem dependência de criptografia para um hash de dedupe. O prefixo de tamanho evita colisão entre campos (com o byte 0 do cobudget, `"\0\0"+"É"` = `"\0"+"\0É"`). Não precisa ser igual ao do cobudget: a importação (D4) passa cada recado pelo `capture()`, que recalcula o hash. |
| D33 | **Encerrada no bloco 2**: `app/` (cobudget) ficou no build até o bloco do `androidApp` e foi removido no começo dele; o build é `:shared` + `:androidApp`. O que ainda serve de base (listener, `loadLauncherApps`, casos do `CaptureTest`) é lido do histórico (`git show 7616cd2:app/...`). | O cobudget não será mais usado. A importação do backup `.db` dele (D3, D4) continua e não depende do código de `app/`. |

## 2. Componentes

```
androidApp                                          shared
──────────                                          ──────
NotificationListenerService ─RawNotice─► capture() ──┐
Import (backup cobudget) ──────────────► importOld ──┤
Telas (Compose) ──ações──────────────────────────────┼──► Db.write { } ──► SQLite (margem.db, WAL)
                                                     │       │   (escritor, Mutex)
                                                     │       ├─► version++ ──► telas reconsultam
MargemApp: onWrite = { Glance updateAll } ◄──────────┼───────┘
                                                     │
Telas / widgets / Leitura ◄── Db.read { } ───────────┘   (2 leitores, Channel)
        └── budgetMonth(linhas do mês, hoje, ajustes) ──► BudgetMonth
```

- Um único `Db`, criado no `MargemApp` com `BundledSQLiteDriver`, o caminho de `context.getDatabasePath("margem.db")` e o `onWrite` dos widgets. Usado pela Activity, pelo serviço e pelos widgets (mesmo processo).
- Leituras e escritas rodam em `Dispatchers.IO`.
- O `shared` não conhece `Context`, `StatusBarNotification` nem Glance: tudo que é Android entra por parâmetro (caminho, `RawNotice`, `onWrite`).

## 3. Tipos

Distintos por construção (CLAUDE.md), no `commonMain`:

```kotlin
@JvmInline value class Cents(val v: Long)
@JvmInline value class NoticeId(val v: Long)
@JvmInline value class TransactionId(val v: Long)
@JvmInline value class BudgetId(val v: Long)
@JvmInline value class TagId(val v: Long)

// Fronteira da captura: o androidApp preenche a partir do StatusBarNotification (D25).
class RawNotice(
    val packageName: String, val appLabel: String, val key: String, val postedAt: Long, val whenMs: Long,
    val title: String, val text: String, val bigText: String, val subText: String, val textLines: String,
    val category: String, val channelId: String, val extrasJson: String,
)
```

- Datas são `kotlinx.datetime.LocalDate`/`LocalTime` (D31), gravadas como texto ISO (`2026-10-09`, `11:43`): legíveis e ordenáveis em SQL.
- **Mês** é identificado por `YearMonth` do mês do calendário em que ele começa (`2026-10` = de D/out até a véspera de D/nov).
- Zero é inicialização: `0` em vez de `NULL` para "sem referência" (ex.: lançamento manual tem `notice_id = 0`).

## 4. Banco (esboço do schema)

Migrações: ao abrir, o `Db` lê `PRAGMA user_version` no escritor e aplica um `if (v < N)` por versão numa transação, gravando a versão nova no fim.

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

-- Cota de cada mês (D8, D30).
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
- **Apagar lançamento**: apaga parcelas, ligações de marcador e as linhas de `refunds` que apontam para ele; o recado dele e o recado de estorno voltam a ser pendentes.
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

Tipos de `kotlinx.datetime` (D31). Depois de `ensureMonths` (D30), uma consulta traz `limit`, os passos editados e o gasto por dia; a função faz o resto:

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

## 7. UI (`androidApp`)

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
- **Dados na tela** (D19): um helper `@Composable fun <T> query(db, block: (Db) -> T): T?` que observa `db.version` e roda `block` em IO. Telas de mês chamam `db.ensureMonths(month)` antes (D30). Ações chamam funções de escrita num `rememberCoroutineScope`.
- **Tema** (D15): `MargemTheme` fornece `Margem.colors`, `Margem.type`, `Margem.space` por `CompositionLocal`; claro/escuro conforme o ajuste (sistema · claro · escuro).
- **Componentes** (`widgets/Components.kt`): botão, campo de valor, chip de marcador, cartão de recado, régua, seletor de mês, editor de passos, barra de abas, diálogo, snackbar; mais seletor de opções, aviso, cabeçalho de página e linha de lista. **Nascem todos no bloco 2**, com o visual das seções 8.x do book.
- **Catálogo** (só no debug): `src/debug/.../CatalogActivity.kt`, segunda entrada no launcher ("Margem · catálogo"), com todos os componentes nos estados do book, alternando claro e escuro. A conferência dos componentes é visual, nele; não ganham teste unitário.
- **Gráficos** (`ui/Insights.kt`, D17): verbas por mês, ritmo do mês, ranking, calendário de gastos.

## 8. Widgets (`androidApp`)

- Dois `GlanceAppWidget`: **Verbas** e **Pauta**, redimensionáveis de 2×2 a 4×4.
  - Verbas: 2×2 = 1 verba, 4×2 = 3, 4×4 = 6, na ordem escolhida em Ajustes.
  - Pauta: 2×2 = contagem, 4×2 = contagem + 2 recados, 4×4 = contagem + 5.
- Leem o mesmo `Db` (`ensureMonths` + `read { }`) e usam `budgetMonth`. Tema segue o ajuste do app.
- Toque abre a aba correspondente. Atualização: D21.

## 9. Arquivos

Pacote Kotlin `com.kaiodelphino.margem` nos dois módulos. Namespace Android: `com.kaiodelphino.margem` no app (D1) e `com.kaiodelphino.margem.shared` no `shared`.

**`shared/src/commonMain/kotlin/com/kaiodelphino/margem/`**

| Arquivo | Conteúdo |
|---|---|
| `Db.kt` | Escritor + leitores (D29), schema, migrações, `write { }`, `read { }`, `version`, `onWrite`, `ensureMonths`, todas as consultas e escritas, exportar/importar (D14). |
| `Types.kt` | Value classes, `RawNotice`, `Parsed`, `PaceStatus`, ajustes e linhas de leitura. |
| `Capture.kt` | `capture(db, raw)`, dedupe (usado também pela importação, D4), SHA-256 (D32). |
| `Extract.kt` | Leitura de valor/estabelecimento/data (`internal`); `parseBrl` (público). |
| `Month.kt` | `budgetMonth()`, janela do mês, limite da edição de passo, ordem por risco (`internal`). |

**`androidApp/src/main/kotlin/com/kaiodelphino/margem/`**

| Arquivo | Conteúdo |
|---|---|
| `MargemApp.kt` | `Application`: cria o `Db` (driver, caminho, `onWrite` → Glance). |
| `NoticeListener.kt` | `NotificationListenerService`: `StatusBarNotification` → `RawNotice` → `capture()`. |
| `Backup.kt` | Exportar/importar o `.db` via SAF. |
| `Import.kt` | Importação única do cobudget (descartável): abre o `.db` antigo com o mesmo driver e passa cada recado pelo dedupe. |
| `ui/MainActivity.kt` | Activity, `Screen`, navegação, `query()`. |
| `ui/Budgets.kt` · `ui/Inbox.kt` · `ui/Transactions.kt` · `ui/Insights.kt` · `ui/Settings.kt` | Uma por aba, com suas sub-telas. |
| `ui/HomeWidgets.kt` | Os dois widgets de home screen (Glance). |
| `widgets/Theme.kt` · `widgets/Components.kt` | Tokens e componentes do book. |

**`iosApp/`**: não existe na v1 (D26).

Dependências:
- `shared`: `kotlinx-coroutines-core`, `kotlinx-datetime`, `kotlinx-io-core` (backup, D14), `androidx.sqlite:sqlite-bundled`; testes: `kotlin-test`, `kotlinx-coroutines-test`.
- `androidApp`: Compose (foundation, ui, activity-compose), Glance appwidget; testes: JUnit, Robolectric, androidx.test core, `sqlite-framework` (D28), compose ui-test.
- Sem Room, SQLDelight, Material 3, ViewModel, Navigation ou bibliotecas de gráfico.

## 10. Testes

- **Regra** (D23): integração pela API pública; unitário só em `budgetMonth`, extração e SHA-256.
- **Banco** (`shared/commonTest`, rodando em `jvmTest`, SQLite em arquivo temporário por teste, D23): captura → Pauta → lançar → mês da verba; riscar e trazer de volta; estorno de lançamento e de recado; parcelas atravessando meses; virada (`ensureMonths` cria a linha de cota, preenche meses pulados e não incrementa `version`); mudar dia de início (edições descartadas); apagar verba e marcador; leitura durante escrita (D29); backup e reabertura; importação do cobudget com dedupe.
- **`budgetMonth`** (`commonTest`): semanas parciais, passos editados e bloqueios, centavos, dia de início 29–31, verba criada no meio do mês, estados e projeção.
- **Extração** (`commonTest`): os casos atuais de `ExtractTest`/`CaptureTest`, portados para `RawNotice`.
- **Android** (`androidApp`, Robolectric, `Db` com `AndroidSQLiteDriver`, D28): conversão `StatusBarNotification` → `RawNotice` → `capture` → `pending()`; UI Compose: fluxo de lançar (verba + marcador obrigatórios, parcelas, prévia) e editor de passos.
- **Rodada de 4 agentes** sobre o contrato fechado (tipos + assinaturas com KDoc, corpos `TODO()`), em paralelo com a implementação, cada um num worktree e num arquivo: **borda** (`EdgeTest`), **fuzz** (`FuzzTest`, seed fixa e tempo limitado), **concorrência e ciclo de vida** (`ConcurrencyTest`), **invariantes** (`InvariantsTest`, sequências aleatórias de ações checando §5). Só veem spec, glossário, arquitetura e contrato. Cada falha é triada pela spec: bug no código → corrige o código; teste que leu a spec errado → corrige o teste, com o porquê. Teste nunca é afrouxado para passar.
- O alvo `iosSimulatorArm64Test` existe, mas fica fora do loop da v1.

## 11. Em aberto

| # | Questão |
|---|---|
| Q1 | (Depois da v1) O iOS não deixa um app ler notificações de outros apps: de onde vêm os recados no `iosApp`? |
