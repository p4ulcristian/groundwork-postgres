# A query is made by a function from its .sql file

> **Changed in part** by [A .sql file is valid SQL: columns by their real names, a param is param('name')](2026-10-26-a-sql-file-is-valid-sql-a-param-is-param-name.md) (2026-10-26): a param is `param('name')`, not `:name`.

> **Added to** on 2026-10-22: `make-query-from-sql-text` makes a query from SQL text made by code, for groundwork.schema's statements (a table's statements are made from data, per Groundwork's decision "A table is one map").

> Changes in part Groundwork's decision "SQL lives only in files": a query is `(def q (make-query-from-sql-file "path.sql"))`, not `(pg/defsql q)`.

**Decision:** groundwork.sql has seven functions. `start-pool!` opens a HikariCP pool as the db settings say (TLS, time limit, time zone); `stop-pool!` closes it. `make-query-from-sql-file` reads a `.sql` file from the classpath and turns its `:name` params into `?`s, once, when the namespace that defs it loads. `find-rows`, `find-one-row` and `run-statement!` run a query on a pool or a transaction; `run-in-transaction!` runs a function in one transaction and hands it the transaction.

**Why:** a plain function and a `def` say everything in the open: the path is written where it's used, nothing is made up from the namespace's name, and there is no macro to learn. A missing file still fails when the namespace loads.

**The price:** the path is written in full. A changed `.sql` file is read again only when its `def` is evaluated again.

**Not in yet:** a file's leading comment as the docstring; lists and maps as params (SQL arrays, jsonb); Postgres errors as named types (unique, foreign key, canceled); a transaction's own options (read-only, isolation, a longer time limit); hooks on each query and transaction; a connection outside the pool; the settings' schema.

**Instead of:**
- the `defsql` macro: shorter, the file found from the var's name, but a macro hides where the file is
