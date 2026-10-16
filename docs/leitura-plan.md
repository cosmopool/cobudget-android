# Bloco 6 · Leitura — plan

Status: **decided** 2026-10-16 (L1–L12 answered; recorded in spec §7.5 and architecture D44–D45).

## 1. Goal

Replace the empty `ui/Insights.kt` with the four charts of §7.5: Ritmo do mês, Verbas por mês, ranking (marcadores e estabelecimentos) and Calendário de gastos, in Canvas (D17), following the book's chart rules (9.3).

## 2. What already exists

- `Db.history(from, to)`: spent × limit per budget and month (each month with its own limit). Covered by `InvariantsTest`, `EdgeTest`.
- `Db.tagRanking(from, to)`, `Db.merchantRanking(from, to)`: by value, count = transactions with an installment in the period, refunds out, merchants by alias.
- `Db.spentByDay(from, to)`: per-day sums, installments on their own date, refunds out.
- `Db.budgetDetail(id, month, today)`: `BudgetMonth` (weeks, steps, ideal, status, projection) and the month's lines (installment date + cents, refunded flagged): enough for the cumulative spent line.
- Theme: `Margem.colors.series` (c1..c6 + Outras), `hatch`, `SeriesMark`, `MonthPicker`/`pnav`, `StateMark`.

## 3. Screen (book 10.6)

Month header (`pnav`) → Ritmo do mês (one budget, selector in the section line) → Verbas por mês → ranking → calendar, scrolling. Each chart: rubric with the cut and measure, a serif sentence with the conclusion, the chart, TalkBack description.

## 4. Changes

### 4.1 shared
Depends on L10. Otherwise none: the reads exist.

### 4.2 androidApp
- `ui/Insights.kt`: the screen, one Composable per chart (D17), `InsightsTags` (D35).
- Catalog entries for the four charts in light and dark.

### 4.3 Tests
- UI (Robolectric): each section shows the expected numbers and sentence for a seeded month; month navigation; budget switch; ranking switch; empty states.
- shared: whatever L10 adds.

## 5. Open decisions (one at a time; recommendation first)

| # | Question | Recommendation |
|---|---|---|
| L1 | Period of the page | One month header for the whole page (as 10.6), from the earliest budget month to the current one; ranking and calendar use that month; Verbas por mês ends at it. |
| L2 | Which budget in Ritmo / Verbas por mês | One selector in the Ritmo section line, shared by both charts; default = most at risk in the month (order of §8.1). |
| L3 | Shape of Verbas por mês | One budget, last 6 months ending at the chosen month (Fig. 9.6, 10.6). |
| L4 | Ranking: tags and merchants | Segmented "Marcadores · Estabelecimentos" in one section; 6 rows, then "Ver todos (n)" expands in place. |
| L5 | Calendar bands | Level 0 = no spending; 4 equal bands from 0 to the month's highest day, step rounded up to R$ 10; legend "cada degrau ≈ R$ X". |
| L6 | Series color of a budget | By budget id order among existing budgets (first created = Índigo); 7th onwards = Outras. |
| L7 | Conclusion sentences and annotations | Generated sentences from fixed templates per chart; no free-text annotations in v1. |
| L8 | Ritmo of a past month | Whole month drawn, no "hoje" shading, no projection; sentence states how it closed. |
| L9 | Accessibility | `contentDescription` with the conclusion and main numbers; no equivalent table in v1. |
| L10 | Ideal line per day | Shared computes it (`BudgetDetail.idealByDay`), from the same rule as `ideal` (§5), so the UI doesn't redo pace math (D12). |
| L11 | Empty states | No budget: current empty state. Budgets but nothing in the month: charts at zero with "Nada lançado em outubro." |

## 6. Rodada de testes por agentes (contrato fechado)

- **When:** right after the contract commit (types and public signatures with KDoc, bodies `TODO()`, `InsightsTags`, screen stub `TODO()`), in parallel with the implementation.
- **Agents:** 4 background agents, `isolation: worktree`, one file and one area each: Borda (`LeituraEdgeTest`), Fuzz (`LeituraFuzzTest`), Concorrência e ciclo de vida (`LeituraConcurrencyTest` / `LeituraLifecycleTest` for UI), Invariantes (`LeituraInvariantsTest`). Compile, don't run until green.
- **Prompt:** pin the contract commit (`git merge --ff-only {COMMIT}`, verify `git log -1`, verify bodies `TODO()`), never read from the main checkout, don't read `app/`; read spec §3, §5, §6, §7.5, glossary, architecture (D7, D12, D17, D35, §5, §10), AGENTS.md, `.agents/rules/testing.md`, contract files.
- **Triage when green:** code bug → fix code; misread rule → fix test and say why; uncovered rule → user's decision, recorded in docs. Never loosen a test.

## 7. Order of work

1. Ask L1–L11; write the answers into spec §7.5 / architecture (D-rows).
2. Contract commit.
3. Contract test round (§6) in parallel with 4.
4. Implementation + integration tests (§4.3).
5. Triage; `make test` green.
6. Check on the emulator, light and dark, against book 10.6 and Fig. 9.2–9.6.
