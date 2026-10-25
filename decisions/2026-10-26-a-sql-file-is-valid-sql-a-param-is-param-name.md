# A .sql file is valid SQL: columns by their real names, a param is param('name')

> The user's decision, 2026-10-26, planning Iron Rainbow's database step (option C of five). Changes [A query is made by a function from its .sql file](2026-10-22-a-query-is-made-by-a-function-from-its-sql-file.md) in part: a param is no longer `:name`. Decided, not built yet.

**Decision:** every query file is SQL Postgres's parser accepts as it is.

```sql
-- One invoice of the workspace.
SELECT invoice__id, invoice__number, invoice__total_cents
FROM invoices
WHERE invoice__id = param('invoice-id')::uuid
  AND invoice__workspace_id = param('workspace-id')::uuid;
```

- **A column is written by its real name**, `invoice__total_cents`; it comes back as `:invoice/total-cents`, as today.
- **A param is a call, `param('name')`**, cast to its type where it is used: `param('job-ids')::uuid[]`, `(param('status')::text IS NULL OR …)`. `make-query-from-sql-file` turns each call into a `?` as it reads the file (not inside string literals, comments or dollar-quoted bodies), and the params map is checked against the names the file has: one missing, or one the file doesn't have, is refused.
- **In psql the file runs unchanged** where the database has a `param` function of its own (a development database's): `SET param.invoice_id = '…'`, then `\i get_invoice.sql`; an unset param is null.

**Why:** a file that is SQL is read by everything that reads SQL (editors, linters, formatters, psql, EXPLAIN), and a sign means one thing: there is no colon with two meanings (`:invoice/id` a column, `:invoice-id` a param) and no step between the file and Postgres that changes columns. The names and the types of the params stay, which Postgres's own `$1`, `$2` lose. sqlc (Go) makes its params a call too, `sqlc.arg(name)`, for the same reason.

**Instead of:**
- `:name` params (yesql, HugSQL, this library until now): short, but the file is not SQL
- `:entity/field` columns (Iron Rainbow's rewrite): one spelling in code and SQL, but a colon means two things and the file is not SQL
- Postgres's positional `$1` in a `PREPARE`: real SQL, but a number says nothing in a long query
- one SQL function per query, params by name: real SQL, but every query change becomes a migration and the app's code moves into the database
- session settings (`current_setting`): every value travels as text and the planner sees no param
