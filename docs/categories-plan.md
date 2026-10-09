# Categories & multi-tag classification — plan

Status: **draft** — decided items are fixed; open decisions (§6) wait for an answer, one at a time.

## 1. Goal

A page in the app to create **categories** and **sub-categories**
(e.g. `financial → credit card, services`, `obligation → explain, send, presentation`).
genda tags every input (notification or email) with **every** sub-category it fits,
each with its own probability, so the client can keep only `p >= threshold` (e.g. 0.9).

## 2. Decided

| # | Decision | Why |
|---|---|---|
| D1 | Two-level tree: category → sub-category, each node has `name` + `description`. | The description is the text Jev classifies against; the tree keeps tags coherent. |
| D2 | Multi-tag: one Jev **noul** (yes/no) per **leaf**, all in the same request. | `choice` probabilities compete (sum to 1), so only one label could pass 0.9; nouls are independent. One HTTP call per input still. |
| D3 | genda owns the list (SQLite + msgpack endpoints); the Android page is an editor. | IMAP email is classified without the phone; one source of truth. |

Consequences of D1+D2:

- A **leaf** is any node without children: a sub-category, or a category that has no sub-categories yet. Only leaves are asked.
- The parent tag is implied by its leaf — never stored, never inconsistent (`credit card` always means `financial/credit card`).
- An input can land in several branches (`financial/credit card` **and** `obligation/send`).
- An input matching a category but no sub-category gets nothing; the user can add an `other` sub-category if they want a catch-all.

## 3. genda (C) — current shape it must fit

- `classify.c` builds one JSON request from `CLASSIFY_REQ_FMT` into a static `classify_req` (`sizeof classify_esc + 8192`), reply into `classify_resp` (16 KiB) parsed with jsmn (`CLASSIFY_TOK_CAP 256`). It stores nothing.
- `db.c::dbIngest` is the one ingest path (`/ingest` + IMAP thread): store raw → classify → store event once per `raw_id`.
- `server.c` is single-threaded, GET/POST only, msgpack bodies, Bearer auth.
- Tests: integration only (`test/integration/*`), real SQLite temp file, `zen_fake.h` as the one mock at the network boundary.

## 4. genda changes

### 4.1 Schema (new tables, `CREATE TABLE IF NOT EXISTS` like the rest)

```sql
CREATE TABLE IF NOT EXISTS categories(
  id INTEGER PRIMARY KEY,
  parent_id INTEGER,            -- NULL = top level; parent must itself be top level (max depth 2)
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  created_at TEXT);
CREATE TABLE IF NOT EXISTS raw_tags(
  raw_id INTEGER NOT NULL,
  category_id INTEGER NOT NULL, -- always a leaf at classification time
  p REAL NOT NULL,
  PRIMARY KEY(raw_id, category_id));
```

Tags hang off `raw_inputs`, not `events`: most tagged inputs (a card purchase) are not calendar events.

### 4.2 Types (`common.h`)

```c
#define CAT_CAP 64          // leaves asked per request
#define CAT_NAME_CAP 64
#define CAT_DESC_CAP 512

typedef struct { i64 v; } CategoryId;   // categories.id; {0} = none / top level

typedef struct {
  CategoryId id, parent;
  char name[CAT_NAME_CAP], description[CAT_DESC_CAP];
} Category;

typedef struct {            // leaves only, loaded once per ingest; {0} = no categories
  Category leaf[CAT_CAP];
  i32 n;
} Leaves;
```

`Classified` gains `f64 tag_p[CAT_CAP]` (index-aligned with `Leaves.leaf`).

### 4.3 Classifier (`classify.c`)

- Signature: `Classified classifyInput(const Input *in, const Leaves *leaves)`. Still stores nothing.
- `dbIngest` loads `Leaves` from SQLite and passes it in (db stays the only SQLite user).
- Per leaf, append one question keyed by the id (`"t12"`: ASCII, no escaping, stable across renames):

  ```json
  "t12":{"type":"noul",
         "instructions":{"question":"Is this message about <category>: <name>? <description>"},
         "criteria":{"true":"The message is about <name>: <description>",
                     "false":"The message is not about <name>."}}
  ```

  Names/descriptions go through `classifyJsonEsc`.
- Buffers grow by fixed sizing, still static: `classify_req += CAT_CAP * (6 * (CAT_NAME_CAP*3 + CAT_DESC_CAP*2) + 256)` (~0.7 MB), `CLASSIFY_TOK_CAP` and `CLASSIFY_RESP_CAP` scaled by `CAT_CAP`.
- `classifyParseReply` reads each `answers.t<id>.noul` with `classifyJsonProb`. Any missing/bad tag answer fails the whole reply (`ok = 0`, retried) — same rule as `kind` today.
- `kind` / `is_deadline` / `is_event` logic unchanged (see O1).

### 4.4 Storage (`db.c`)

- `dbIngest`: after classify, `INSERT OR IGNORE INTO raw_tags` for every leaf (see O3), same idempotency as events: re-ingest of an `ext_id` never duplicates.
- Boundary parser `dbParseCategory(body, len, &cat)`: name non-empty, sizes within caps, parent exists and is top level, no self-parent, leaf count stays `<= CAT_CAP`. Only place returning -1 → handler answers 400.

### 4.5 Wire API (msgpack; see O2 for the shape)

- `GET /categories` → array of `{id, parent_id, name, description}` (`parent_id` 0 = top level).
- `POST /categories` → map `{id, parent_id, name, description}`; `id` 0 creates, otherwise updates. Replies `{id}`.
- `POST /categories/delete` → `{id}`; deletes the node, its children and their `raw_tags`.
- `POST /ingest` reply and `GET /events` items gain `tags`: array of `{id, p}`.

### 4.6 Tests (integration, per `.agents/rules/testing.md`)

- `server_test`: create/list/update/delete categories over HTTP; depth-3 and over-`CAT_CAP` writes answer 400; ingest returns one `p` per leaf; re-ingest keeps one row per `(raw_id, category)`; deleting a category drops its tags; Jev missing a tag answer → 500 then retry succeeds.
- `zen_fake.h`: answer every `t<id>` noul by keyword in state (e.g. "fatura"/"cartão" → credit card high); raise `ZEN_FAKE_BODY_CAP` above the new worst-case request.
- `make test` green.

## 5. Android changes

- **Client**: one small `gendaCall(context, method, path, body): Pair<Int, ByteArray>` reusing the url/token/port prefs and the connection code now inline in `flushGenda`; a minimal msgpack reader for the replies (see O5).
- **Page**: a Categories screen (see O4): tree list (category rows with their sub-categories indented), add category, add sub-category under a category, edit name/description, delete with confirm. Description field is required — it's what Jev reads; hint text says so.
- **States**: genda URL unset → "Set genda in Settings"; unreachable / non-2xx → error with retry. No local copy (D3), so nothing to back up.
- **ViewModel**: `CategoriesViewModel` like the existing ones (agreed exception to codin'-dirty).
- **Tests**: Robolectric test of the msgpack reader + client against a local fake HTTP server (free port per run), following `.agents/rules/testing.md`.

## 6. Open decisions (asked one at a time; recommendation first)

| # | Question | Recommendation |
|---|---|---|
| O1 | Keep genda's `kind` (appointment / obligation / none, drives calendar events) separate from user categories, or replace it with categories? | **Keep separate.** Events keep working; categories are a new axis. Can merge later. |
| O2 | Category API shape: granular (`GET`, `POST` upsert, `POST /delete`) or replace-whole-list? | **Granular.** Ids must stay stable for `raw_tags`; whole-list replace would need id matching anyway. |
| O3 | Store every leaf's `p`, or only `p >= threshold`? | **Every `p`.** Threshold can change on the client with no reclassify; ~64 small rows per input max. |
| O4 | Where does the page live: 4th bottom tab, or a screen under Settings? | **4th tab "Categories".** It's a primary editing surface, not a setting. |
| O5 | Kotlin msgpack decoding: hand-rolled reader or `org.msgpack:msgpack-core`? | **Hand-rolled** (~60 lines: map/array/str/uint/f64/nil), matching the hand-rolled writer already in the app. |
| O6 | Where is the threshold set, and default? | **Client setting, default 0.9**, applied when displaying. genda returns all `p`. |
| O7 | Editing/adding a category: reclassify old inputs? | **No** in v1; new/edited leaves apply to new inputs only. A `POST /reclassify` can come later. |
| O8 | Show tags on the Messages list in this feature? | **No — follow-up.** Needs mapping local rows to genda `raw_id`s (outbox is async, genda also gets non-monitored apps). |
| O9 | `CAT_CAP` (max leaves per request)? | **64.** OpenJev allows 256 questions; Jev's limit is undocumented; 64 keeps request size and cost bounded. |

## 7. Order of work

1. genda: schema + `/categories` endpoints + parser + tests.
2. genda: noul questions, `raw_tags`, `tags` in `/ingest` and `/events`, fake Zen + tests.
3. Android: client + msgpack reader + tests.
4. Android: Categories page.
5. Follow-ups: tags on Messages (O8), reclassify (O7).

## 8. Risks

- **Jev cost/latency** grows with question count; pricing per question unknown — measure with ~20 leaves before raising `CAT_CAP`.
- **Description quality** drives accuracy; vague descriptions → noisy `p`.
- **Model swap** (Laya/OpenJev): same request shape, but Laya base checkpoints need fine-tuning and temperature scaling before a 0.9 threshold means anything.
