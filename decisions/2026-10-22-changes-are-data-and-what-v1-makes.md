# Changes are data, and v1 makes tables, columns, references, enums and indexes

> **Changed in part** by [Checks, functions, views and extensions are declared, compared and dropped like tables](2026-10-23-checks-functions-views-and-extensions-are-declared-like-tables.md) (2026-10-23, on tidy-up): `migrate!` takes `{:tables :extensions :functions :views}`; a table's `:checks`, and every check no map names is dropped; a fresh database gets the functions, views and extensions from their files.

> **Changed in part** by [A column's type states Postgres's limit, in malli's words](2026-10-23-a-columns-type-states-postgres-limit-in-malli-words.md) (2026-10-23, on tidy-up): a column's type is varchar(n), integer, smallint, numeric(p,s), an array or jsonb too, as its malli words say.

> **Changed in part** by [The references no map names go before the changes](2026-10-23-references-no-map-names-go-before-the-changes.md) (2026-10-23, on tidy-up): a reference no map names is dropped before the changes are checked and run, so a table only it pointed at can be dropped.

> **Changed in part** by [An index is a map with every key written out; unique plain columns are :uniques](2026-10-23-an-index-is-a-map-and-unique-columns-are-uniques.md) (2026-10-23, on tidy-up): an index is a map (columns or expressions with their order, method, operator classes, unique, condition), unique indexes are read and made, and a table's `:uniques` are unique keys over several columns.

> **Changed in part** by [cellar adopts what the database already has](2026-10-23-cellar-adopts-what-the-database-has-by-asking-postgres.md) (2026-10-23, on tidy-up): every check is read, and a key, check or index Postgres has under another name, the same shape, is renamed to the map's; checks, indexes and defaults are compared as Postgres prints them.

> **Changed in part** by [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22, v0.1.2): each part of a plan runs under its own time limits, which `run-instant-part!`, `run-slow-part!` and `migrate!` take, not the pool's; a table map writes out `:indexes`, `:gates` and `:on-change`, and a column its five options.

> **Changed in part** by [A drop with nothing to drop is only marked done](2026-11-12-a-drop-with-nothing-to-drop-is-only-marked-done.md) (2026-11-12, v0.5.4): a `:drop-column` or `:drop-table` whose column or table Postgres hasn't runs nothing and is marked done; it was a stop.

> Builds on Groundwork's decision "A table is one map".

**Decision:**

- **Changes are a vector of maps the app passes in**, each with an `:id`: `:rename-table`, `:rename-column`, `:drop-column` and `:drop-table` (both with a `:why`), and `:run-sql-file` for a backfill. Not files in a folder: a vector works the same from a jar, and is read like the tables.
- **A plan is made on paper first:** the pending changes are applied to what Postgres has, then compared with the maps. A stop means nothing runs. The instant part runs in one transaction with the changes it marks done.
- **v1 makes:** tables, columns (type, required or optional, default, primary key), references (`NOT VALID`, then `VALIDATE` in the slow part), enums (a check, the same way), indexes (`CONCURRENTLY`, in the slow part), and a column becoming required (a check `NOT VALID`, then `VALIDATE`, `SET NOT NULL` and the check dropped, in the slow part). After a rename it renames what it named after the old names.
- **v1 stops on:** a column or table in Postgres that no map has, a type change, a primary key change, a required column without a default on a table with rows, and a default computed per row (`:random-uuid`) added to a table with rows.
- **v1 reads only what it makes:** checks named `…_enum` or `…_not_null` and indexes that are neither unique nor a primary key; other constraints and indexes are left alone.

**Why:** what a map can say is made from the map; what it can't is a stop with the change file to add. Nothing is guessed, and nothing that blocks writes runs before the app starts.

**The price:** type changes, unique constraints, gates (`:access`) and live updates' triggers aren't in yet. A `:run-sql-file` backfill runs in the instant part, as written; batching it is up to its SQL.

**Instead of:**
- change files in a classpath folder: listing a folder inside a jar is unreliable
- running each change at once and comparing after: a stop would come after some changes had run
