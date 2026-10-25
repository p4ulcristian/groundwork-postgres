# groundwork-postgres

Postgres from Clojure, in two halves. **Queries:** every query is a `.sql`
file; a param is `:name`, a column `entity__field` comes back as
`:entity/field`, with real types (UUID, BigDecimal, java.time).
**Tables:** declared as Clojure maps; groundwork-postgres compares them with Postgres and
plans what differs, in two parts (instant, before the app starts; slow, after
it, in the background). A difference that could lose data stops with a
message and runs nothing; what a map can't say (a rename, a drop, a backfill)
is a change, given as data and run once.

```clojure
io.github.p4ulcristian/groundwork-postgres {:git/tag "v0.4.8" :git/sha "322b4b1"}
```

Needs only next.jdbc, PgJDBC, HikariCP (with slf4j-jdk14), jsonista, malli,
[groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) (the app's metrics, handed in)
and [groundwork-settings](https://github.com/p4ulcristian/groundwork-settings) (the password is its secret). One public namespace,
`groundwork-postgres.api`; its parts are `groundwork-postgres.queries.*` and `groundwork-postgres.tables.*`.

## The api

| function | does |
|---|---|
| `start-pool!` `stop-pool!` `settings-shape` | a HikariCP pool; every setting required (TLS, the pool's waits and lifetimes, the network's timeouts, the session's limits and time zone, the histograms' buckets), the app's log, which HikariCP's and PgJDBC's own lines about the pool go to, and the app's meter, which the pool's metrics go to; the settings as a malli shape |
| `make-query-from-sql-file` `make-query-from-sql-text` | a query from a `.sql` file on the classpath (read when its `def` loads), or from SQL text code makes |
| `find-rows` `find-one-row` `run-statement!` | run a query on a pool or a transaction |
| `run-in-transaction!` | `(f transaction)` in one transaction, kept or undone; `{:read-only? true}` refuses writes, `false` written out otherwise; `:log`, the caller's log (a message's, with its request id), which the transaction carries for its after-commit work, or nil for one that keeps none |
| `run-after-commit!` | `(f)` kept to run once the transaction commits, in order, on the same thread; never after a rollback; a nested transaction's joins the outer's; a failure logged to the `:log` of the `run-in-transaction!` it was kept under, the rest still run; a pool, and a transaction opened with `:log nil`, refused ([why](decisions/2026-10-23-a-transaction-keeps-work-to-run-after-its-commit.md)) |
| `is-transaction?` | whether it was handed a transaction rather than a pool |
| `make-plan-from-tables` | what would bring Postgres to the tables, extensions, functions and views the app declares, and the changes, changing nothing: `{:instant :slow :stops :changes-to-mark}` |
| `run-instant-part!` `run-slow-part!` | run a plan's two parts (the deploy), each under its own statement and lock time limits |
| `migrate!` | plan, run the instant part now and the slow part on a thread, each planned statement written to the log |
| `does-database-match-tables?` | whether the app can start: no instant part, stop or change left |
| `grant-tables-to-role!` | let the app's own role, which owns nothing, use the tables through their gates |
| `turn-table-into-row-shape` | a table's rows as a malli shape |
| `turn-column-type-into-postgres-type` | a column's type as Postgres's: `[:string {:max 10}]` → `character varying(10)` |
| `turn-key-into-column-name` `turn-table-key-into-table-name` `turn-table-name-into-table-key` | names: `:order/shop-id` ↔ `order__shop_id`, `:billing/invoices` ↔ `billing.invoices` |
| `run-and-list-tables-read` | runs a function and returns what it returned with the tables its queries read, as Postgres's planner says; `{:log log}` first |
| `start-listening!` `stop-listening!` `is-listening?` | `LISTEN` on a channel, notifications in batches, a reconnect said and counted; its batch window, waits and reconnect delay written out; whether its connection is listening now |

```clojure
(require '[groundwork-postgres.api :as postgres])

(def pool (postgres/start-pool! {:host "localhost" :port 5432 :database "shop" :user "shop"
                               :password password  ; groundwork-settings's secret, #secret :groundwork-postgres/password; nil for none
                               :tls :off :tls-root-cert nil
                               :pool-size 10
                               :connection-wait-ms 30000 :connection-check-wait-ms 5000
                               :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                               :leak-warning-after-ms :none
                               :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                               :statement-timeout-ms 30000 :lock-timeout-ms :none
                               :idle-in-transaction-timeout-ms :none
                               :time-zone "UTC"
                               :metric-buckets {:query-seconds              [0.001 0.005 0.025 0.1 0.5 2.5 10]
                                                :connection-acquire-seconds [0.001 0.01 0.1 1 10]
                                                :connection-usage-seconds   [0.001 0.01 0.1 1 10 60]}}
                              {:log   log     ; the app's: HikariCP's and PgJDBC's lines about this pool
                               :meter meter}))  ; the app's: this pool's metrics, every query's time

;; a column is [key type] or [key type options], and says only the options it has:
;; an unsaid one is no or none (NOT NULL, not unique, not a primary key, no default, no reference);
;; a reference writes out what it does on delete and update, and when it is checked
(def shops
  {:table     :shops
   :columns   [[:shop/id   :uuid   {:primary-key? true :default :random-uuid}]
               [:shop/name :string]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change nil})

(def orders
  {:table     :orders
   :columns   [[:order/id            :uuid         {:primary-key? true :default :random-uuid}]
               [:order/shop-id       :uuid         {:references {:to :shop/id :on-delete :refuse :on-update :refuse
                                                                 :checked-at :statement}}]
               [:order/status        [:enum "draft" "paid" "coated"]]
               [:order/total         :decimal      {:default 0}]
               [:order/customer-note :string       {:optional? true}]
               [:order/created-at    :time/instant {:default :now}]]
   ;; an index writes out every key: its columns (or expressions, as SQL) with their order and nulls,
   ;; its method, operator classes, whether unique, and its rows (nil: every row)
   :indexes   [{:columns [[:order/shop-id :ascending :nulls-last] [:order/created-at :descending :nulls-first]]
                :method  :btree :ops {} :unique? false :where nil}
               {:columns [[:order/customer-note :unordered]]
                :method  :gin :ops {:order/customer-note :gin-trgm-ops} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change nil})

(def partner-payouts                     ; a namespaced key: the table billing.partner_payouts
  {:table     :billing/partner-payouts
   :columns   [[:partner-payout/id     :uuid    {:primary-key? true :default :random-uuid}]
               [:partner-payout/amount :decimal]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change nil})

;; beside the tables, every key written out: the extensions, the functions (a .sql file each, one
;; CREATE OR REPLACE FUNCTION, or {:name :sql} a library made) and the views (one CREATE VIEW each, in order)
(postgres/migrate! pool {:tables     [shops orders partner-payouts]
                       :extensions ["pg_trgm"]                          ; the gin index's gin_trgm_ops
                       :functions  ["app/db/next_order_number.sql"]
                       :views      ["app/db/shop_totals.sql"]}
                 [{:id "2026-10-23-rename-order-note" :rename-column [:orders :order/note :order/customer-note]}
                  {:id "2026-10-24-currency-to-text" :change-column-type :shop/currency :from "character(3)"}]
                 {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}   ; an ALTER waits 5 s for its lock, then fails
                  :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}   ; an index build takes what it takes
                  :log          log})                                                 ; the app's: each planned statement

;; app/orders/find_orders_of_shop.sql:
;;   SELECT order__id, order__total FROM orders WHERE order__shop_id = :order/shop-id
(def find-orders-of-shop (postgres/make-query-from-sql-file "app/orders/find_orders_of_shop.sql"))
(postgres/find-rows pool find-orders-of-shop {:order/shop-id shop-id})
;=> [#:order{:id #uuid "1d000000-…" :total 120.50M}]
```

An index is a map with every key written out
([why](decisions/2026-10-23-an-index-is-a-map-and-unique-columns-are-uniques.md)):
`:columns`, each a column's key or an expression (SQL text) with its order
and its nulls (`[:order/created-at :descending :nulls-first]`), or
`[:order/note :unordered]` for a `:gin`, `:gist`, `:hash` or `:brin`
index; `:method`; `:ops`, the operator classes that aren't the type's own
(`{:order/customer-note :gin-trgm-ops}`, pg_trgm's, in `:extensions`),
`{}` for none; `:unique?`; and `:where`, nil for every row. A table map's `:uniques` are unique keys over several plain columns
(`[[:conversation/kind :conversation/subject-id]]`, `[]` for none; one
column's is its `{:unique? true}`); one with a condition or an expression
is an index with `:unique? true`. An index on a table with rows is built
and dropped `CONCURRENTLY`, in the slow part; one no map names is dropped.

A table map's `:checks` are SQL conditions every row holds
(`["jsonb_typeof(order__photos) = 'array'"]`, `[]` for none), each named by
a hash of its condition; added to a table with rows `NOT VALID`, then
proven in the slow part. Beside the tables, `migrate!` (and
`make-plan-from-tables`, `does-database-match-tables?`) takes the
database's extensions and the `.sql` files of its functions and views,
every key written out, `[]` for none
([why](decisions/2026-10-23-checks-functions-views-and-extensions-are-declared-like-tables.md)).
A function may also be one a library makes from data, its name as its
SQL writes it and its one `CREATE OR REPLACE FUNCTION`:
`{:name "groundwork_access.denied_ab12" :sql "CREATE OR REPLACE FUNCTION groundwork_access.denied_ab12() …"}`,
compared, made and dropped exactly like a file's; one whose SQL makes
another name is refused, `:groundwork-postgres/bad-function`
([why](decisions/2026-10-23-functions-may-be-sql-text-a-library-makes.md)).
They are compared as Postgres prints them and made in the order
extensions, functions, tables, views, all in the instant part: a function
whose printed definition differs is replaced (`CREATE OR REPLACE`, its body
unchecked, so it may read a column the same plan adds); a view that
differs, or reads a column a pending change drops, is dropped before the
changes and made again after the tables, with every view over it. A
function or a view in the maps' folders that nothing declared makes is dropped; an
extension no list names is a stop (its types may hold data), plpgsql
aside. A view made again loses its grants until `grant-tables-to-role!`
runs again. A fresh database gets them all, from the files, never from the
changes' history.

What a map names that Postgres already has under another name, the same
shape, is adopted: renamed to the map's name (`ALTER … RENAME`, the catalog
only, no scan, no rebuild); a plain unique index becomes the map's unique
key from that index (`ADD CONSTRAINT … UNIQUE USING INDEX`). A key's shape
is its columns and target; a check's condition, an index's body, a
column's default, a function and a view are compared as Postgres prints
them, asked of Postgres itself: in a transaction that is rolled back, the
maps' pieces are made on empty temporary tables, the functions and views
under temporary names, and read back. So planning needs the TEMP right and
a transaction that isn't read-only; a piece Postgres refuses is a stop,
with its words
([why](decisions/2026-10-23-cellar-adopts-what-the-database-has-by-asking-postgres.md)).

Every setting, option and table key is required: a missing or unknown one
is refused, named, with every other problem at once, before anything runs
(`:groundwork-postgres/bad-settings`, `:groundwork-postgres/bad-options`, `:groundwork-postgres/bad-tables`). The
values above are HikariCP's and PgJDBC's own defaults, written out; what
stays fixed, and why, is in
[the decision](decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).
A column is the exception: it says only the options it has, since an unsaid
one is the safe answer
([why](decisions/2026-10-22-a-column-says-only-the-options-it-has.md));
an unknown option is still refused.

A column's type is in malli's words, as
[groundwork-shaped-json](https://github.com/p4ulcristian/groundwork-shaped-json) reads them, and states the limit
Postgres holds, so the row shape `turn-table-into-row-shape` makes refuses
what Postgres would
([why](decisions/2026-10-23-a-columns-type-states-postgres-limit-in-malli-words.md)):

| type | Postgres |
|---|---|
| `:string`, `[:string {:max 10}]` | `text`, `varchar(10)` |
| `[:enum "a" "b"]`, `[:enum {:max 10} "a" "b"]` | `text`, `varchar(10)`, with a check |
| `:int`, `[:int {:min -2147483648 :max 2147483647}]`, `[:int {:min -32768 :max 32767}]` | `bigint`, `integer`, `smallint` |
| `:decimal`, `[:decimal {:precision 10 :scale 2}]` | `numeric`, `numeric(10,2)` |
| `:uuid` `:double` `:boolean` `:time/instant` `:time/local-date` | `uuid` `double precision` `boolean` `timestamptz` `date` |
| `[:vector t]`, t one of the above but an enum | `t[]` |
| `[:json shape]` | `jsonb` |

Any other type, or other properties, is refused (`:groundwork-postgres/unknown-type`),
and a type Postgres has otherwise is a stop, until a change moves it:
`{:id "…" :change-column-type :shop/currency :from "character(3)"}`, `:from`
what Postgres has (else a stop, named). The plan compares the column as if
it had run, and runs it in the slow part, `ALTER COLUMN … TYPE … USING`
(it may rewrite the table: try its time on a copy), marking the change
done after it there; a declared view that reads the column is a stop
([why](decisions/2026-10-23-a-column-changes-type-by-a-change-the-plan-understands.md)). A `:default` is
`:random-uuid` (`gen_random_uuid()`), `:time-ordered-uuid` (Postgres 18's
`uuidv7()`), `:now`, `:today` (`CURRENT_DATE`), a number, true/false, text,
or `[]`/`{}` (empty JSON on a `:json` column; `[]` an empty array on a
`:vector` one).

groundwork-postgres writes no line itself: `start-pool!`, `migrate!`, `start-listening!`
and `run-and-list-tables-read` take the app's log function,
`(log level event data)` as [groundwork-logs](https://github.com/p4ulcristian/groundwork-logs)
makes it, under `:log` (`:groundwork-postgres/planned`, `:groundwork-postgres/listening-broke`,
`:groundwork-postgres/listener-function-failed`, `:groundwork-postgres/tables-read-unknown`, and
`:groundwork-postgres/after-commit-failed`, to the `:log` `run-in-transaction!` was
given (the caller's, a message's with its request id), for work
`run-after-commit!` kept that threw;
[why](decisions/2026-10-22-the-app-passes-its-log.md)). HikariCP's and
PgJDBC's own lines about a pool (its start and stop, a leak warning, a
connection that failed), info and up, go to the log `start-pool!` was given,
as `:groundwork-postgres/pool-said` with the pool's name, the logger's and the message;
each pool's to its own, so two pools, or two apps, in one JVM don't hear
each other's. groundwork-postgres brings slf4j-jdk14 for it: the app adds no SLF4J
backend, and one of its own takes HikariCP's lines away from the log, which
`:groundwork-postgres/pool-lines-elsewhere` says
([how](decisions/2026-10-23-the-drivers-lines-go-to-the-pools-log.md)).

`start-pool!` and `start-listening!` take the app's meter, as
[groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) makes it, under `:meter`,
and declare groundwork-postgres's metrics on it
([why](decisions/2026-10-23-the-pool-and-every-query-are-counted-on-the-apps-meter.md)):

| metric | type | what |
|---|---|---|
| `groundwork_postgres_pool_connections{state}` | gauge, read | `active`, `idle` |
| `groundwork_postgres_pool_waiting_threads` | gauge, read | threads waiting for a connection |
| `groundwork_postgres_connection_acquire_seconds` | histogram | the wait for a connection |
| `groundwork_postgres_connection_usage_seconds` | histogram | how long one was held |
| `groundwork_postgres_connection_timeouts_total` | counter | waits past `:connection-wait-ms` |
| `groundwork_postgres_query_seconds{query}` | histogram | every query run, by its name, failed or not |
| `groundwork_postgres_listening_reconnects_total` | counter | a listener listening again after a break |

A query's name is its file's path (or the name `make-query-from-sql-text`
was given); every query made is noted, and `start-pool!` declares those
made by then as the `query` label's set: a query made after it is counted
as `"other"`. A meter holds one pool's metrics: a second pool takes a
meter of its own.

A table map's `:gates`, nil or SQL conditions for all four operations
(`{:read "…" :insert "…" :update "…" :delete "false"}`), become row-level
security, and its `:on-change`, nil or the name of a Postgres function and its
arguments (`{:function "app.tell_change" :arguments ["order__id"]}`), called
by statement triggers after each insert, update and delete, with the changed
rows as `groundwork_postgres_new_rows` and `groundwork_postgres_old_rows`. groundwork-postgres makes neither: the
app, or a library on top, passes them in. groundwork-postgres keeps the changes it has run
in its own table, `groundwork_postgres.changes_done`. A reference no map names is dropped before the
changes run, so a table only it pointed at can be dropped in the same
deploy ([why](decisions/2026-10-23-references-no-map-names-go-before-the-changes.md)).

A reference writes out every key, a column's
(`{:to :shop/id :on-delete :cascade :on-update :refuse :checked-at :statement}`)
and a table's alike: `:on-delete` and `:on-update` are `:cascade` (the row
is deleted, or its columns changed, with the other row's, by Postgres, past
the gates), `:set-null` (only on `:optional?` columns), `:refuse` (NO
ACTION: refused while a row points at it) or `:restrict` (refused at once,
even checked at commit); `:checked-at` is `:statement` or `:commit`
(DEFERRABLE INITIALLY DEFERRED). groundwork-postgres reads all three from Postgres: a
key that acts otherwise is made again (NOT VALID, then proven in the slow
part); one checked otherwise is changed in place
([why](decisions/2026-10-23-a-reference-writes-out-what-it-does-on-delete-update-and-when-checked.md)).
A table map's `:references` are references over several columns, `[]` for
none (a one-column one is the column's `:references`):
`{:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id] :on-delete :refuse :on-update :cascade :checked-at :statement}`.
`:to` names the other table's columns, its primary key among them, which
get a unique key together.
Its `:triggers`, `[]` for none, are triggers a library on top (or the app)
declares, calling a function it makes:
`{:function "groundwork_access.fill_1a2b3c4d" :arguments [] :timing :before :events #{:insert :update} :update-of [:job/order-id] :for-each :row :when nil}`,
`:when` nil to call it always, or a condition
(`"OLD.package__workspace_id IS DISTINCT FROM NEW.package__workspace_id"`;
[why](decisions/2026-10-23-a-trigger-says-when-it-is-called.md));
like the change triggers, one no map names is dropped
([why](decisions/2026-10-23-a-table-map-says-references-over-several-columns-and-a-librarys-triggers.md)).

## Tests

```sh
clojure -X:test     # the Postgres tests are skipped without a database

# with a throwaway Postgres:
echo groundwork-postgres-test-password > /tmp/postgres-pg-password
initdb -D /tmp/postgres-pg -A scram-sha-256 --pwfile=/tmp/postgres-pg-password -U "$USER"
pg_ctl -D /tmp/postgres-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/postgres-pg.log start
POSTGRES_TEST_PORT=5499 POSTGRES_TEST_USER=$USER POSTGRES_TEST_PASSWORD=groundwork-postgres-test-password clojure -X:test
pg_ctl -D /tmp/postgres-pg stop
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
