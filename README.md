# UZEX deals agent

A web service that answers questions about the exchange's 500,000 deals in Russian and Uzbek, using only the published data. Each answer shows the SQL behind it and the result table, and hovering over a figure highlights its source cell.

## Run

You need Java 25, Docker, and a filled-in `.env` (start from `.env.example`). Set `LLM_API_KEY` there.

Start the database. The first start loads the CSVs in about 30 seconds:

```bash
docker compose up -d --wait db
```

Start the app. The landing page is at http://localhost:8080 and the chat at http://localhost:8080/chat:

```bash
./gradlew bootRun
```

Any OpenAI-compatible API works through `LLM_BASE_URL` and `LLM_MODEL`. If that provider rejects DeepSeek's `thinking` field, set `LLM_DISABLE_THINKING=false`.

To rebuild the database from scratch:

```bash
docker compose down -v
```

## Public URL

Tailscale Funnel gives the app a public `https://` address. Run this in a second terminal and leave it running:

```bash
tailscale funnel 8080
```

The machine and the app must stay running for as long as the judges test.

## Users and chats

Nobody has to register. Each browser gets a guest user behind an HttpOnly cookie. A guest's chats are removed when the page is reloaded. All chats sit in one list, and each chat has one language. Answers still follow the language of each question.

Accounts with a username and password are optional. Chats in an account are kept and can be renamed or deleted. Registering before reloading keeps the guest's chats.

The agent's read-only database role has no access to the `app` schema, so SQL written by the LLM cannot read anyone's chats. The app reaches that schema through its own role, which cannot read the deals.

An existing database from before this schema needs a rebuild, because init scripts only run on an empty volume:

```bash
docker compose down -v
```

```bash
docker compose up -d --wait db
```

## How an answer is made

See [docs/architecture.md](docs/architecture.md) for the system design: the request flow, packages, data, identity and known limits.

1. The LLM reads the question and calls tools: `find_company`, `find_broker` and `run_sql`.
2. `SqlGuard` accepts one `SELECT` over the published tables. The database role is read-only and cannot see anything else, so it blocks writes even if the guard is bypassed.
3. The LLM words the answer from the rows it received.
4. `DraftReviewer` checks every number, date and quoted or all-caps name against the tool results. A failed draft gets one repair attempt. If it fails again, the page shows the raw query result instead of the text.
5. An answer with no query rows behind it becomes "no data".

The data rules live in `db/init/02_model.sql` as precomputed columns, so the LLM selects correct values instead of deriving them:

| Trap in the data | Column or table |
|---|---|
| USD and soum amounts | `amount_uzs`, `amount_usd` |
| Annulled deals never took place | `is_annulled` |
| Quantity counts lots | `volume`, `volume_tonnes` |
| 230 seller spellings of 41 products | `product`, `grade`, `class`, `brand`, `size`, `ptype` |
| Regions named differently across files | `regions` |
| Near-identical company names | `spelled_key` keeps apostrophes, `loose_key` drops them |
| A broker on both sides of a deal | `broker_deals` |

## Tests

Unit tests for the SQL guard, the verifier and language detection:

```bash
./gradlew test
```

The `evals/` folder is kept locally and is not in git. `evals/run_eval.py` holds questions in both languages, with expected answers computed from a separate SQLite copy of the CSVs (`evals/load_oracle.py`) that shares no code with the app. Build that copy once:

```bash
python3 evals/load_oracle.py
```

Compare the database model with those answers, without using the LLM:

```bash
python3 evals/run_eval.py --check-model
```

With the app running, ask it every question. Results go to `evals/results/latest.json`:

```bash
python3 evals/run_eval.py
```
