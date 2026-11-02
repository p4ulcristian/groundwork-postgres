# Checks, functions, views and extensions are declared, compared and dropped like tables

> **Added to** by [The database's provider's extensions are declared, and let be](2026-11-02-the-providers-extensions-are-declared-and-let-be.md) (2026-11-02, v0.5.2): beside `:extensions`, `:provider-extensions`, never made, never dropped, no stop.

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 191 checks, 21 functions, 15 views, 3 extensions; defaults `uuidv7()` 95, jsonb/array literals 44, `CURRENT_DATE` 1). Builds on [cellar adopts what the database already has](2026-10-23-cellar-adopts-what-the-database-has-by-asking-postgres.md). Built on tidy-up, not released.

**What was wrong:** cellar read only checks named `_enum`/`_not_null`; functions, views and extensions lived only in change files, and on a fresh database (tests, previews) a change file is marked done, never run, so a fresh database had none of them. A default outside cellar's few words (`uuidv7()`) would be turned into `gen_random_uuid()`.

**Decision:**

- **A table's `:checks`**, each a condition: `:checks ["jsonb_typeof(design_rim__photos) = 'array'"]`, `[]` for none. Added NOT VALID, then validated in the slow part; compared by asking Postgres.
- **`migrate!` takes, beside the tables, every key written out:**
  ```clojure
  {:extensions ["pg_trgm" "cube" "earthdistance"]
   :functions  ["app/db/next_job_number.sql"]      ; one CREATE FUNCTION per file
   :views      ["app/db/report_totals.sql"]}       ; one CREATE VIEW per file
  ```
  Made in the order extensions, functions, tables, views; one no longer named is dropped; a view over a column that changes is dropped and made again around the change. Compared by asking Postgres.
- **New default words:** `:time-ordered-uuid` (uuidv7), `:today`, and `[]`/`{}` on json and array columns.
- **Text enums under other names** (about 63 checks) are matched by shape into `[:enum …]` and renamed.

**Why:** a fresh database is the same as production, and each object is said in one place.

**The price:** the biggest cellar change of Iron Rainbow's port (an estimate).

**Instead of:**
- **change files also run on a fresh database**: a fresh database is history replayed; the current function lives in whichever change last touched it; drift goes unseen
- **the app installs them at every start**: a view whose columns change can't be replaced in place, a removed function stays, nothing is compared

**Settled while building:**

1. **What the app declares is `migrate!`'s second argument**, `{:tables … :extensions … :functions … :views …}`, every key written out, in place of the vector of tables; `make-plan-from-tables` and `does-database-match-tables?` take it the same way. A missing or unknown key is `:cellar/bad-tables`.
2. **A check is named `<table>_check_<hash of its condition>`**: a changed condition is another check, the old one dropped. Every check no map names is dropped (the catalog only), cellar's own `…_not_null` step aside.
3. **A function's file starts with `CREATE OR REPLACE FUNCTION` and a plain name** (`public` when it names no folder), makes one function, and has no params; otherwise `:cellar/bad-file`. Overloads are a function each, by their arguments.
4. **The probe makes each function under a temporary name**, `pg_temp.cellar_probe_function_<n>`, and reads back its arguments and its definition, compared without the name. Anyone with the TEMP right may, so the app's own role, which may make nothing in its folders, can still check the database; no real function is touched.
5. **Functions are made with `check_function_bodies` off** (`SET LOCAL`, in the instant part, as `pg_dump` does), so a SQL function may read a table or a column the same plan makes after it.
6. **A function Postgres won't replace** (another return type) is a stop with its words; dropping it is a change file's.
7. **What cellar compares and drops**: functions (not procedures or aggregates) and plain views (not materialized ones) in the folders of the maps' tables and of the declared files, none of an extension's. A missing folder of a function or view is made first.
8. **A function no file makes is dropped after the tables**, by folder, name and arguments, when the triggers and checks that used it are gone; one something else still uses makes the instant part fail, and it keeps nothing.
9. **A view's file starts with `CREATE VIEW` (or `CREATE OR REPLACE VIEW`) and a plain name**; the probe makes it as `pg_temp.cellar_probe_view_<n>`. One Postgres refuses there (it reads what this plan makes) counts as changed.
10. **The views that go are dropped in one `DROP VIEW`**, which Postgres orders, with every view that reads one of them; the declared ones are made again after the tables, in the list's order. A view made again has lost its grants until `grant-tables-to-role!` runs again.
11. **"A view over a column that changes" is one over a column or table a pending change drops**; a rename Postgres follows by itself (the view, printed otherwise on paper, is made again, which is the catalog only).
12. **An extension no list names is a stop, not dropped** (`plpgsql`, in every database, aside): its types may hold data, as a table no map names is a stop. The probe makes the declared extensions missing in its rolled-back transaction, so an index or check that needs one prints.
13. **`[]` is `'[]'` on a `:json` column and `'{}'` on a `:vector` one; `{}` only on `:json`**; elsewhere `:cellar/bad-default`. `:time-ordered-uuid` is computed per row, as `:random-uuid`: adding such a column to a table with rows is a stop.
14. **A text enum under another name is adopted by its printed condition** (cellar adopts what the database has); one whose values Postgres prints in another order is another check, added `NOT VALID` and proven.
