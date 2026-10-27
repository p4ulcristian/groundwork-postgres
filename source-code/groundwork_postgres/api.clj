(ns groundwork-postgres.api
  "Talking to Postgres, and shaping its tables. The api of groundwork-postgres, in two
   halves.

   QUERIES. Every query is a .sql file (or text a library makes from data):

     (def find-orders-of-shop (database/make-query-from-sql-file \"app/orders/find_orders_of_shop.sql\"))
     (database/find-rows pool find-orders-of-shop {:order/shop-id 7})
     ;=> [{:order/id 42 :order/total 120.50M} …]

   A .sql file is SQL Postgres's parser accepts as it is:

     SELECT order__id, order__total FROM orders WHERE order__shop_id = param('order/shop-id')

   A param is param('name'), the params map's key the name as a keyword
   ({:order/shop-id 7}); a param missing from the map, or a key the query
   has no param for, is refused. A column entity__field comes back as
   :entity/field, with real types (UUID, BigDecimal, java.time). Every
   function that runs a query takes a pool, or the transaction that
   run-in-transaction! hands its function.

   TABLES. Declared as Clojure maps, and Postgres brought to what they say:

     (def orders
       {:table     :orders
        :columns   [[:order/id         :uuid         {:primary-key? true :default :random-uuid}]
                    [:order/shop-id    :uuid         {:references {:to :shop/id :on-delete :refuse
                                                                   :on-update :refuse :checked-at :statement}}]
                    [:order/status     [:enum \"draft\" \"done\"] {:default \"draft\"}]
                    [:order/note       :string       {:optional? true}]
                    [:order/created-at :time/instant {:default :now}]
                    [:order/total      :decimal]]
        :indexes   [{:columns [[:order/shop-id :ascending :nulls-last] [:order/created-at :descending :nulls-first]]
                     :method :btree :ops {} :unique? false :where nil}]
        :uniques   []
        :checks    []
        :references []
        :triggers  []
        :gates     nil
        :on-change nil})

     (database/migrate! pool {:tables [shops orders] :extensions [] :functions [] :views []} changes
                        {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                         :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                         :log          log})   ; the app's log function (groundwork-logs's)

   start-pool! and start-listening! take the app's meter under :meter and
   count on it (groundwork_postgres.queries.metrics): the pool, every query's time by
   its name, a listener listening again.

   groundwork-postgres writes no line itself: the functions that have something to say
   (start-pool!, migrate!, start-listening!, run-and-list-tables-read) take
   the app's log, (log level event data), under :log in their options.
   HikariCP's and PgJDBC's own lines go to the log of the pool they are
   about (groundwork_postgres.queries.pool-lines).

   A table map writes out every key groundwork-postgres reads (:indexes, :uniques,
   :checks, :references and :triggers [] for none, :gates and :on-change
   nil for none); it is open, so another library's keys ride along. An index is a
   map with every key written out: {:columns [[:order/shop-id :ascending
   :nulls-last] [\"lower(order__note)\" :descending :nulls-first]] :method
   :btree :ops {} :unique? false :where nil}; a :gin, :gist, :hash or :brin
   index's columns are [:order/note :unordered], and :ops names an operator
   class by column ({:order/note :gin-trgm-ops}). :uniques are unique keys
   over several plain columns ([[:order/shop-id :order/number]]); one with
   a condition or an expression is an index with :unique? true. A plain
   unique index Postgres has becomes the map's key from that index, with no
   rebuild. A column is [key type] or [key type options], and says
   only the options it has; an unsaid one is no or none, the safe side
   (NOT NULL, not unique, not a primary key, no default, no reference), and
   an unknown one is refused. The type is in malli's words, as groundwork-shaped-json
   (github.com/p4ulcristian/groundwork-shaped-json) reads them, and states the limit Postgres
   holds: :uuid :double :boolean :time/instant :time/local-date; :string
   or [:string {:max 10}] (text, varchar(10)); [:enum \"a\" …] or [:enum
   {:max 10} \"a\" …]; :int (bigint), [:int {:min -2147483648 :max
   2147483647}] (integer) or [:int {:min -32768 :max 32767}] (smallint);
   :decimal or [:decimal {:precision 10 :scale 2}]; [:vector t], an array
   of one of those but an enum; [:json shape] (jsonb). It is NOT
   NULL unless {:optional? true}; {:unique? true} makes a unique key;
   {:primary-key? true} puts it in the primary key; :default is
   :random-uuid, :time-ordered-uuid (uuidv7()), :now, :today
   (CURRENT_DATE), a number, true/false, text, or [] or {} (empty JSON; []
   an empty array on a :vector column); :references points at
   another table's one-column primary key, saying what it does:
   {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement},
   each action :cascade, :set-null (an :optional? column only), :refuse (NO
   ACTION) or :restrict, checked after each :statement or at :commit. A table key with a
   namespace is in that Postgres schema (folder): :billing/invoices is
   billing.invoices; one without is in public.

   Beside the tables, the app declares its extensions, its functions (a
   .sql file each, one CREATE OR REPLACE FUNCTION, or one a library made
   from data, {:name \"groundwork_access.denied_ab12\" :sql \"CREATE OR REPLACE
   FUNCTION groundwork_access.denied_ab12() …\"}) and the .sql files of its views
   (one CREATE VIEW each, in order), every key written out: {:tables [...]
   :extensions [\"pg_trgm\"] :functions [\"app/db/next_job_number.sql\"]
   :views [\"app/db/report_totals.sql\"]}. They are compared as Postgres
   prints them and made extensions first, then functions, tables, views; a
   function or view in the maps' folders that nothing declared makes is dropped, an
   extension no list names is a stop. A table's :checks are SQL
   conditions, [\"jsonb_typeof(order__photos) = 'array'\"].

   Changes are data, for what a map can't say; each runs once, remembered in
   groundwork_postgres.changes_done (groundwork-postgres's own table, in its own folder), and a fresh
   database marks them all done:

     [{:id \"2026-10-23-rename-order-note\" :rename-column [:orders :order/note :order/customer-note]}
      {:id \"2026-10-24-drop-order-fax\"    :drop-column [:orders :order/fax] :why \"no faxes since 2019\"}
      {:id \"2026-10-25-rename-jobs\"       :rename-table [:jobs :coatings]}
      {:id \"2026-10-26-drop-old-things\"   :drop-table :old-things :why \"…\"}
      {:id \"2026-10-27-fill-priority\"     :run-sql-file \"app/changes/fill_priority.sql\"}
      {:id \"2026-10-28-currency-to-text\"  :change-column-type :price-sheet/currency :from \"character(3)\"}]

   A type change moves a column from the type Postgres has (:from, as
   Postgres prints it; else a stop) to its map's: the column's one map
   names the table, the plan compares the column as if it had run, and it
   runs in the slow part (ALTER COLUMN … TYPE … USING, which may rewrite
   the table), marked done there after it; a declared view that reads the
   column is a stop.

   A plan has two parts: :instant (only Postgres's catalog changes; before
   the app starts) and :slow (reads or writes every row: indexes built
   CONCURRENTLY, constraints validated; after, in the background). A
   difference that could lose data, or that v1 can't make, is a stop.

   A table map with :gates ({:read sql :insert sql :update sql :delete sql}:
   SQL conditions the app, or a library on top, passes in) gets row-level
   security, on and forced, and one policy per operation;
   grant-tables-to-role! lets the app's own role, which owns nothing, use the
   tables through them.

   A table map with :on-change ({:function \"app.tell_change\" :arguments
   [\"order__id\" …]}: the name of a Postgres function the app, or a library
   on top, makes and passes in) gets three statement triggers calling that
   function after each insert, update and delete, the changed rows readable
   in it as groundwork_postgres_new_rows and groundwork_postgres_old_rows.
   A table map's :references are references over several columns
     {:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id]
      :on-delete :refuse :on-update :cascade :checked-at :statement}
   (a one-column one is the column's :references): :to names the other
   table's columns, its primary key among them, which get a unique key
   together (Postgres points a reference only at columns unique together;
   with the primary key among them it holds for every row); :on-update
   :cascade carries a change of the other row's columns to this row
   (Postgres's own, past the gates). Made NOT VALID and proven in the slow
   part, after the unique key when that is new; one that acts otherwise
   than its map is made again, one checked otherwise changed in place.
   A table map's :triggers are triggers a library on top (or the app)
   declares, calling a function it makes:
     {:function \"groundwork_access.fill_1a2b3c4d\" :arguments [] :timing :before
      :events #{:insert :update} :update-of [:job/order-id] :for-each :row :when nil}
   (:when nil to call it always, or a SQL condition on OLD and NEW), each
   named by a hash of its SQL, made and dropped like the change
   triggers: a trigger no map names is dropped."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.queries.listen :as listen]
   [groundwork-postgres.queries.names :as query-names]
   [groundwork-postgres.queries.postgres :as sql]
   [groundwork-postgres.queries.tables-read :as tables-read]
   [groundwork-postgres.settings :as settings]
   [groundwork-postgres.tables.catalog :as catalog]
   [groundwork-postgres.tables.desired :as desired]
   [groundwork-postgres.tables.names :as names]
   [groundwork-postgres.tables.plan :as plan]
   [groundwork-postgres.tables.probe :as probe]
   [groundwork-postgres.tables.quotes :as quotes]
   [groundwork-postgres.tables.types :as types]))

;; -----------------------------------------------------------------------------
;; Queries

(def settings-shape
  "What start-pool! takes, every key required (nil and :none are choices):
   where and who, TLS, the pool's HikariCP settings, PgJDBC's network
   timeouts and the session's limits and time zone. See
   groundwork-postgres.settings/settings-shape for each key."
  settings/settings-shape)

(defn start-pool!
  "Opens a HikariCP pool of connections to Postgres. Every setting is
   required (settings-shape), checked first: a missing or unknown one
   refuses, named, with every other problem at once.

     {:host \"localhost\" :port 5432 :database \"shop\" :user \"shop\" :password nil
      :tls :off :tls-root-cert nil
      :pool-size 10
      :connection-wait-ms 30000 :connection-check-wait-ms 5000
      :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
      :leak-warning-after-ms :none
      :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
      :statement-timeout-ms 30000 :lock-timeout-ms :none
      :idle-in-transaction-timeout-ms :none
      :time-zone \"UTC\"
      :metric-buckets {:query-seconds              [0.001 0.005 0.025 0.1 0.5 2.5 10]
                       :connection-acquire-seconds [0.001 0.01 0.1 1 10]
                       :connection-usage-seconds   [0.001 0.01 0.1 1 10 60]}}

   :password is a secret, groundwork-settings's (#secret :groundwork-postgres/password in the app's
   settings file), revealed only to the driver; nil for none. Written out
   as text, it is refused.

   Options, every key required: {:log log :meter meter}. :log, the app's
   log, which HikariCP's and PgJDBC's own lines about this pool are written
   to, as :groundwork-postgres/pool-said (its start and stop, a leak warning, a
   connection that failed), with the pool's name, the logger's and the
   message. :meter, the app's meter, holding no other pool's metrics:
   groundwork-postgres's are declared on it before anything connects (the pool's
   connections and waiting threads, read; the wait for a connection, its
   use and the timeouts; every query's time by its name, the names of the
   queries made by then as the set; groundwork-postgres.queries.metrics).

   Returns the pool."
  [db-settings options]
  (sql/start-pool! db-settings options))

(defn stop-pool!
  "Closes the pool's connections."
  [pool]
  (sql/stop-pool! pool))

(defn make-query-from-sql-file
  "A query from a .sql file on the classpath, read now: a missing file, or a
   param written other than param('name'), fails when the namespace that
   defs the query loads."
  [path]
  (sql/make-query-from-sql-file path))

(defn make-query-from-sql-text
  "A query from SQL text made by code, not read from a file (a table's
   statements, made from its map), its params param('name') as in a file.
   The name stands where a file's path would, in errors."
  [query-name sql-text]
  (sql/make-query-from-sql-text query-name sql-text))

(defn find-rows
  "Every row of a query that returns rows: a vector, empty when none."
  [pool-or-transaction query params]
  (sql/find-rows pool-or-transaction query params))

(defn find-one-row
  "The one row of a query, or nil; a second row throws."
  [pool-or-transaction query params]
  (sql/find-one-row pool-or-transaction query params))

(defn run-statement!
  "Runs a statement for what it changes. Returns how many rows it changed."
  [pool-or-transaction query params]
  (sql/run-statement! pool-or-transaction query params))

(defn is-transaction?
  "Whether this is a transaction run-in-transaction! handed its function,
   rather than a pool: for a function that must run on its own, after
   the caller's transaction is kept (a file deleted from a bucket can't
   be undone with a rollback)."
  [pool-or-transaction]
  (some? (:groundwork-postgres/connection pool-or-transaction)))

(defn run-in-transaction!
  "Runs (f transaction) in one transaction: kept when f returns, undone when
   it throws. Returns what f returns. Inside a transaction, it joins that one.
   Options, every key required: {:read-only? false :log log}; :read-only?
   true, Postgres refuses every write in it; :log, the caller's log (a
   message's, with its request id), which the transaction carries, or nil
   for a transaction that keeps no work (a library's own, never handed to
   app code). Once it commits, the work run-after-commit! gave it runs, and
   one that throws is written to that log."
  [pool-or-transaction options f]
  (sql/run-in-transaction! pool-or-transaction options f))

(defn run-after-commit!
  "Keeps (f) to run once the transaction commits, for work that may be
   lost (this server's cache, an outside call whose loss is harmless,
   groundwork-file-storage's file delete); work that must happen is a groundwork-background-jobs job queued
   in the same transaction. The list is per transaction: a nested
   run-in-transaction! joins the outer one's. It runs in order, on the
   same thread, once COMMIT has returned and the connection is back in the
   pool; never after a rollback. One that throws is written to the log of
   the run-in-transaction! it was kept under (:error
   :groundwork-postgres/after-commit-failed {:groundwork-postgres/pool … :error …}), the caller's,
   so a message's line has its request id; the commit stands and the rest
   still runs. A transaction opened with :log nil refuses,
   :groundwork-postgres/transaction-without-log. Handed a pool, it refuses,
   :groundwork-postgres/not-a-transaction. Lost if the process dies between the commit
   and the run.

     (postgres/run-in-transaction! pool {:read-only? false :log log}
       (fn [transaction]
         (postgres/run-statement! transaction delete-promotion {:promotion/id id})
         (postgres/run-after-commit! transaction #(promotions/clear-cache!))))"
  [transaction f]
  (sql/run-after-commit! transaction f))

;; -----------------------------------------------------------------------------
;; What a question read, and hearing changes

(defn run-and-list-tables-read
  "Runs (f) and returns [what f returned, the tables its queries read]: a
   set of table keys (#{:orders :shops}), or :unknown when Postgres's
   planner couldn't say for one of them (a function that reads tables
   inside it), which is written to the log (:groundwork-postgres/tables-read-unknown,
   once per query). Each query is planned once, without running it.
   Options, every key required: {:log log}, the app's log, the running
   request's when there is one."
  [options f]
  (settings/check-tables-read-options options)
  (tables-read/run-and-list-tables-read (:log options) f))

(defn turn-table-name-into-table-key
  "A table's key from its folder and name: \"public\" \"orders\" -> :orders,
   \"billing\" \"invoices\" -> :billing/invoices."
  [folder table-name]
  (query-names/find-key-for-table folder table-name))

(defn start-listening!
  "LISTENs on a Postgres channel, on one connection of the pool held for as
   long as it runs. Options, every key required:

     {:log                    log    ; the app's: a break, or a failure of either function, is written to it
      :meter                  meter  ; the app's: listening again after a break is counted on it
      :on-notifications       f      ; (f [{:channel :payload} …]) for each batch
      :on-reconnect           f      ; (f) after a break, listening again: what came in between was missed
      :batch-window-ms        50     ; after the first notification, how long more join its batch
      :check-stopped-every-ms 500    ; one wait for a notification, before looking whether it was stopped
      :reconnect-after-ms     1000   ; after a break, the wait before connecting again
      :stop-wait-ms           2000}  ; how long stop-listening! waits for the thread

   Returns the listener."
  [pool channel options]
  (listen/start-listening! pool channel options))

(defn stop-listening!
  "Stops a listener; its connection goes back to the pool. Waits at most
   its :stop-wait-ms; returns whether it had ended."
  [listener]
  (listen/stop-listening! listener))

(defn is-listening?
  "Whether a listener's connection is LISTENing now: false before it first
   is, while a break waits to connect again, and once stopped. For a
   gauge (groundwork-live-updates's) or a status page."
  [listener]
  (listen/is-listening? listener))

;; -----------------------------------------------------------------------------
;; Tables


(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork_postgres/tables/" file-name)))

(def ^:private create-changes-done (make-query "create_changes_done.sql"))
(def ^:private find-changes-done   (make-query "find_changes_done.sql"))
(def ^:private insert-change-done  (make-query "insert_change_done.sql"))

(def ^:private set-part-limits     (make-query "set_part_limits.sql"))
(def ^:private reset-part-limits   (make-query "reset_part_limits.sql"))

(defn- set-part-limits! [transaction-or-connection {:keys [statement-timeout-ms lock-timeout-ms]} local?]
  (let [as-text #(str (if (= :none %) 0 %))]
    (sql/find-one-row transaction-or-connection set-part-limits
                      {:statement-timeout (as-text statement-timeout-ms)
                       :lock-timeout      (as-text lock-timeout-ms)
                       :local             local?})))

(def ^:private does-changes-done-exist
  (sql/make-query-from-sql-text "changes_done is there" "SELECT to_regclass('groundwork_postgres.changes_done') IS NOT NULL AS exists"))

(defn- find-changes-done-if-any
  "The changes done, or none when the table isn't there yet: planning reads
   only, so the app's own role can check a database it can't change."
  [pool]
  (when (:exists (sql/find-one-row pool does-changes-done-exist {}))
    (sql/find-rows pool find-changes-done {})))

(defn- turn-statement-into-query [{:keys [sql why]}]
  (sql/make-query-from-sql-text why sql))

(defn- find-whether-table-has-rows [pool table-name]
  (:has-rows (sql/find-one-row pool (sql/make-query-from-sql-text
                                     (str "rows of " table-name)
                                     (str "SELECT EXISTS (SELECT 1 FROM " table-name ") AS has_rows"))
                               {})))

(defn make-plan-from-tables
  "What would make Postgres match what the app declares and the changes,
   without changing anything: {:instant [{:sql :why}] :slow [{:sql :why}]
   :stops [message] :changes-to-mark [id]}. The declared, every key
   written out:

     {:tables     [shops orders]                     ; the table maps
      :extensions [\"pg_trgm\"]                       ; [] for none
      :functions  [\"app/db/next_job_number.sql\"    ; one CREATE OR REPLACE FUNCTION per file,
                   {:name \"groundwork_access.denied_ab12\"  ; or one a library made, its name as its SQL writes it;
                    :sql  \"CREATE OR …\"}]       ; [] for none
      :views      [\"app/db/report_totals.sql\"]}     ; one CREATE VIEW per file, in order, [] for none

   How Postgres prints the maps' checks, indexes and defaults, the files'
   functions and views, is asked of it in a transaction that is rolled
   back (groundwork_postgres.tables.probe): the role needs the TEMP right, and a
   transaction it is given can't be read-only."
  [pool declared changes]
  (settings/check-declared declared)
  (let [desired      (desired/turn-table-maps-into-desired (:tables declared))
        files        (desired/turn-declared-into-desired declared)
        changes-done (set (map :change-done/id (find-changes-done-if-any pool)))
        folders      (-> (set (map names/find-folder-of-table (keys desired)))
                         (into (map :folder (:functions files)))
                         (into (map (comp names/find-folder-of-table :view-name) (:views files)))
                         (into (plan/list-folders-of-pending-changes changes changes-done)))
        catalog      (catalog/read-catalog pool folders)
        objects      (catalog/read-objects pool folders)
        {:keys [printed stops]} (probe/print-declared
                                 pool
                                 {:tables     (plan/list-tables-to-print catalog desired changes changes-done)
                                  :extensions (remove (:extensions objects) (:extensions files))
                                  :functions  (:functions files)
                                  :views      (filter #(contains? (:views objects) (:view-name %)) (:views files))})]
    (update (plan/make-plan {:catalog      catalog
                             :objects      objects
                             :desired      desired
                             :declared     files
                             :printed      printed
                             :changes      changes
                             :changes-done changes-done
                             :has-rows?    (memoize #(find-whether-table-has-rows pool %))})
            :stops into stops)))

(defn run-instant-part!
  "Runs the plan's instant part and marks its changes done, in one
   transaction: all of it, or none. A plan with stops throws, running
   nothing. Options, every key required (the part's own time limits, in
   place of the pool's, for this transaction only):

     {:statement-timeout-ms 30000 :lock-timeout-ms 5000}"
  [pool {:keys [instant stops changes-to-mark]} options]
  (settings/check-part-options options)
  (when (seq stops)
    (throw (ex-info (str "The database can't be brought to the maps:\n  " (str/join "\n  " stops))
                    {:type :groundwork-postgres/stops :stops stops})))
  ;; groundwork-postgres's own transaction, which keeps no work
  (sql/run-in-transaction! pool {:read-only? false :log nil}
    (fn [transaction]
      (set-part-limits! transaction options true)
      (sql/run-statement! transaction create-changes-done {})
      (doseq [statement instant]
        (sql/run-statement! transaction (turn-statement-into-query statement) {}))
      (doseq [id changes-to-mark]
        (sql/run-statement! transaction insert-change-done {:change-done/id id})))))

(defn run-slow-part!
  "Runs the plan's slow part, one statement at a time, outside a
   transaction (CONCURRENTLY needs that), on one connection. A failure
   throws; the next plan finds what is left and plans it again. Options,
   every key required (the part's own time limits, in place of the pool's;
   the connection gets the pool's back after):

     {:statement-timeout-ms :none :lock-timeout-ms 5000}"
  [pool {:keys [slow]} options]
  (settings/check-part-options options)
  (sql/call-with-connection
   pool
   (fn [connection]
     (let [on-connection {:groundwork-postgres/connection connection :groundwork-postgres/meter (:groundwork-postgres/meter pool)}]
       (try
         (set-part-limits! on-connection options false)
         (doseq [statement slow]
           (sql/run-statement! on-connection (turn-statement-into-query statement) {}))
         (finally
           (sql/run-statement! on-connection reset-part-limits {})))))))

(defn- log-plan [log {:keys [instant slow]}]
  (doseq [[part statements] [[:instant instant] [:slow slow]]
          {:keys [sql why]} statements]
    (log :info :groundwork-postgres/planned {:groundwork-postgres/part part :groundwork-postgres/why why :groundwork-postgres/sql sql})))

(defn migrate!
  "Brings Postgres to what the app declares (make-plan-from-tables: its
   tables, extensions, functions and views), for development and the
   deploy: plans,
   throws on a stop (nothing runs), runs the instant part now and the slow
   part on a background thread, each with its options, and writes each
   planned statement to the log, :info :groundwork-postgres/planned {:groundwork-postgres/part
   :instant-or-:slow :groundwork-postgres/why … :groundwork-postgres/sql …}. Options, every key
   required:

     {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
      :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
      :log          log}                                                ; the app's

   Returns the plan with :slow-part, a future of the slow part (deref it to
   wait)."
  [pool declared changes options]
  (settings/check-migrate-options options)
  (let [plan (make-plan-from-tables pool declared changes)]
    (run-instant-part! pool plan (:instant-part options))
    (log-plan (:log options) plan)
    (assoc plan :slow-part (future (run-slow-part! pool plan (:slow-part options))))))

(defn does-database-match-tables?
  "Whether Postgres has everything what the app declares (as
   make-plan-from-tables takes it) and the changes need to run: no
   instant part left, no stop, no change waiting. A slow part still to run
   is allowed."
  [pool declared changes]
  (let [{:keys [instant stops changes-to-mark]} (make-plan-from-tables pool declared changes)]
    (and (empty? instant) (empty? stops) (empty? changes-to-mark))))

(defn turn-table-into-row-shape
  "The malli shape of a table's rows, for groundwork-shaped-json: an optional column
   may be missing or nil."
  [table]
  (settings/check-tables [table])
  (desired/turn-table-into-row-shape table))

(defn turn-column-type-into-postgres-type
  "A column's type, in the words a table map says it, as Postgres's type,
   as format_type names it: [:string {:max 10}] -> \"character
   varying(10)\", [:json shape] -> \"jsonb\"; an enum is its text type. For
   a library writing SQL over a map's columns (a function's variables).
   A type groundwork-postgres has no word for throws :groundwork-postgres/unknown-type."
  [type]
  (types/turn-type-into-postgres-type type))

(defn turn-key-into-column-name
  "A column key's Postgres name: :order/shop-id -> \"order__shop_id\"."
  [k]
  (names/turn-key-into-column-name k))

(defn turn-table-key-into-table-name
  "A table key's Postgres name: :orders -> \"orders\", :billing/invoices -> \"billing.invoices\"."
  [k]
  (names/turn-table-key-into-table-name k))

(defn quote-text
  "Text as a SQL text literal, for SQL a library makes (a gate's condition,
   a function's body): \"it's\" -> \"'it''s'\", by PgJDBC's
   Utils/escapeLiteral. Quoted for standard_conforming_strings on,
   Postgres's default: a backslash stays one. Text with a zero byte, which
   Postgres can't hold, throws :groundwork-postgres/not-quotable."
  [text]
  (quotes/quote-text text))

(defn quote-name
  "One name in double quotes, for SQL a library makes: may orders/edit
   is \"may orders/edit\", a double quote in it doubled, by PgJDBC's
   Utils/escapeIdentifier. A name with a zero byte throws
   :groundwork-postgres/not-quotable."
  [name-text]
  (quotes/quote-name name-text))

(defn grant-tables-to-role!
  "Lets the app's own database role use the tables of these maps: their
   folders, their rows (select, insert, update, delete), and the functions
   in them. The role owns nothing, so the gates hold for it. It may also
   read groundwork_postgres.changes_done, so it can check the database with
   does-database-match-tables?. Run by migrate as the owner; safe to run
   again."
  [pool tables role]
  (settings/check-tables tables)
  (sql/run-statement! pool create-changes-done {})
  (sql/run-statement! pool (sql/make-query-from-sql-text
                            (str "grant groundwork_postgres.changes_done to " role)
                            (str "GRANT USAGE ON SCHEMA groundwork_postgres TO " role "; "
                                 "GRANT SELECT ON groundwork_postgres.changes_done TO " role))
                      {})
  (doseq [folder (set (map (comp names/find-folder-of-table names/turn-table-key-into-table-name :table) tables))]
    (sql/run-statement! pool (sql/make-query-from-sql-text
                              (str "grant " folder " to " role)
                              (str "GRANT USAGE ON SCHEMA " folder " TO " role "; "
                                   "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " folder " TO " role "; "
                                   "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " folder " TO " role))
                        {})))
