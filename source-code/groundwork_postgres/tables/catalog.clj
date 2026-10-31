(ns groundwork-postgres.tables.catalog
  "What Postgres has, read from its catalog, in the same form desired.clj
   makes from the maps:

     {\"orders\" {:columns      [{:name \"order__id\" :type \"uuid\" :not-null? true :default \"gen_random_uuid()\"} …]
                 :primary-key  [\"order__id\"]
                 :foreign-keys {\"orders_order__shop_id_fkey\" {:columns [\"order__shop_id\"] :foreign-table \"shops\"
                                                              :foreign-columns [\"shop__id\"] :on-delete :refuse ; or {:set-null [\"…\"]}
                                                              :on-update :refuse :checked-at :statement
                                                              :match :simple :validated? true}}
                 :uniques      {\"orders_order__id_order__shop_id_key\" {:columns [\"order__id\" \"order__shop_id\"]}}
                 :checks       {\"orders_order__status_enum\" {:condition \"(order__status = ANY (ARRAY['draft'::text, 'done'::text]))\"
                                                           :validated? true}}
                 :indexes      {\"orders_order__shop_id_idx\" {:columns [\"order__shop_id\"] :unique? false
                                                           :body \"USING btree (order__shop_id)\" :valid? true}}}}

   A check's condition and an index's body are as Postgres prints them, so
   the map's, printed by Postgres too (groundwork-postgres.tables.probe), compare with
   them exactly. Read: unique keys, every check (those whose names end in
   _not_null, groundwork-postgres's step to a required column, apart), the indexes no
   primary or unique key is built on, row security and policies, and
   triggers (but Postgres's own). A table outside the public folder is
   named folder.table."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.queries.postgres :as sql]))

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork_postgres/tables/" file-name)))

(def ^:private find-tables      (make-query "find_tables.sql"))
(def ^:private find-columns     (make-query "find_columns.sql"))
(def ^:private find-constraints (make-query "find_constraints.sql"))
(def ^:private find-indexes     (make-query "find_indexes.sql"))
(def ^:private find-row-security (make-query "find_row_security.sql"))
(def ^:private find-policies    (make-query "find_policies.sql"))
(def ^:private find-triggers    (make-query "find_triggers.sql"))
(def ^:private find-extensions  (make-query "find_extensions.sql"))
(def ^:private find-functions   (make-query "find_functions.sql"))
(def ^:private find-views       (make-query "find_views.sql"))
(def ^:private find-view-reads  (make-query "find_view_reads.sql"))
(def ^:private find-folders     (make-query "find_folders.sql"))

(defn- add-gates [tables params pool]
  (as-> tables tables
    (reduce (fn [tables {:keys [table-name row-security forced-row-security]}]
              (if (contains? tables table-name)
                (assoc-in tables [table-name :gated?] (boolean (and row-security forced-row-security)))
                tables))
            tables (sql/find-rows pool find-row-security params))
    (reduce (fn [tables {:keys [table-name policy-name]}]
              (if (contains? tables table-name)
                (assoc-in tables [table-name :policies policy-name] true)
                tables))
            tables (sql/find-rows pool find-policies params))))

(defn- add-triggers [tables params pool]
  (reduce (fn [tables {:keys [table-name trigger-name]}]
            (if (contains? tables table-name)
              (assoc-in tables [table-name :triggers trigger-name] true)
              tables))
          tables (sql/find-rows pool find-triggers params)))

(def ^:private sql-name
  ;; a name as Postgres prints it: plain, or in double quotes
  "(?:\"(?:[^\"]|\"\")*\"|[^\\s\"])+")

(def ^:private index-definition
  (re-pattern (str "(?s)^CREATE (?:UNIQUE )?INDEX " sql-name " ON (?:ONLY )?" sql-name " (USING .*)$")))

(defn find-body-of-index
  "What follows the table in an index's definition as pg_get_indexdef
   prints it: \"USING btree (order__shop_id) WHERE (…)\". Its name and its
   table are left out, so two indexes on two tables compare."
  [definition]
  (or (second (re-find index-definition definition))
      (throw (ex-info (str "An index definition groundwork-postgres can't read: " definition)
                      {:type :groundwork-postgres/unread-index :definition definition}))))

(def ^:private reference-actions
  "pg_constraint's confdeltype and confupdtype: no action is groundwork-postgres's
   :refuse; set default groundwork-postgres never makes, so a map never says it and it
   differs."
  {"a" :refuse "c" :cascade "r" :restrict "n" :set-null "d" :set-default})

(def ^:private reference-matches
  "pg_constraint's confmatchtype: groundwork-postgres makes MATCH SIMPLE."
  {"s" :simple "f" :full "p" :partial})

(defn- find-checked-at
  "When a reference is checked: :statement (not deferrable), :commit
   (deferrable, initially deferred), or :deferrable-immediate, which no map
   says."
  [deferrable deferred]
  (cond (not deferrable) :statement
        deferred         :commit
        :else            :deferrable-immediate))

(defn- find-delete-action
  "What a reference does on delete: an action, or {:set-null [column …]}
   when it sets only some of its columns to null (Postgres 15's ON DELETE
   SET NULL (…), its confdelsetcols); a list of every column is the
   same as none, :set-null."
  [on-delete column-names delete-column-names]
  (let [action (get reference-actions on-delete)]
    (if (and (seq delete-column-names) (not= (set delete-column-names) (set column-names)))
      {action (vec delete-column-names)}
      action)))

(defn- add-constraint [tables {:keys [table-name constraint-name constraint-type validated condition on-update on-delete
                                      deferrable deferred match column-names delete-column-names foreign-table
                                      foreign-column-names]}]
  (case constraint-type
    "p" (assoc-in tables [table-name :primary-key] column-names)
    "f" (assoc-in tables [table-name :foreign-keys constraint-name]
                  {:columns         (vec column-names)
                   :foreign-table   foreign-table
                   :foreign-columns (vec foreign-column-names)
                   :on-delete       (find-delete-action on-delete column-names delete-column-names)
                   :on-update       (get reference-actions on-update)
                   :checked-at      (find-checked-at deferrable deferred)
                   :match           (get reference-matches match)
                   :validated?      validated})
    "u" (assoc-in tables [table-name :uniques constraint-name] {:columns (vec column-names)})
    "c" (if (str/ends-with? constraint-name "_not_null")
          (assoc-in tables [table-name :not-null-checks constraint-name]
                    {:column (first column-names) :validated? validated})
          (assoc-in tables [table-name :checks constraint-name]
                    {:condition condition :validated? validated}))
    tables))

(defn read-catalog
  "The tables in these folders (Postgres schemas) as Postgres has them."
  [pool folders]
  (let [params       {:folders (str/join "," folders)}
        empty-tables (into {} (map (fn [{:keys [table-name]}]
                                     [table-name {:columns [] :primary-key [] :foreign-keys {} :uniques {}
                                                  :checks {} :not-null-checks {} :indexes {}
                                                  :gated? false :policies {} :triggers {}}]))
                           (sql/find-rows pool find-tables params))
        with-columns (reduce (fn [tables {:keys [table-name column-name column-type not-null default-text]}]
                               (update-in tables [table-name :columns] conj
                                          {:name column-name :type column-type
                                           :not-null? not-null :default default-text}))
                             empty-tables
                             (sql/find-rows pool find-columns params))
        with-constraints (reduce add-constraint with-columns (sql/find-rows pool find-constraints params))]
    (-> (reduce (fn [tables {:keys [table-name index-name valid is-primary backs-constraint is-unique definition column-names]}]
                  (if (or is-primary backs-constraint)
                    tables
                    (assoc-in tables [table-name :indexes index-name]
                              {:columns (vec column-names) :unique? is-unique :body (find-body-of-index definition) :valid? valid})))
                with-constraints
                (sql/find-rows pool find-indexes params))
        (add-gates params pool)
        (add-triggers params pool))))

(def ^:private function-name-in-definition
  ;; the name pg_get_functiondef prints after CREATE OR REPLACE FUNCTION
  #"^CREATE OR REPLACE FUNCTION [^(]+\(")

(defn leave-out-function-name
  "A function's definition as pg_get_functiondef prints it, its folder and
   name left out, so a function made under another name compares."
  [definition]
  (str/replace-first definition function-name-in-definition "CREATE OR REPLACE FUNCTION ("))

(defn read-objects
  "The folders and extensions in the database, and the functions and views in these
   folders (but an extension's), as Postgres has them:

     {:folders    #{\"public\" \"billing\" …}
      :extensions #{\"plpgsql\" \"pg_trgm\"}
      :functions  {[\"public\" \"next_job_number\" \"workspace uuid\"] \"CREATE OR REPLACE FUNCTION (workspace uuid) …\"}
      :views      {\"report_totals\" {:definition \" SELECT …\" :options \"security_invoker=true\"
                                   :reads #{[\"orders\" \"order__total\"]} :views-read #{}}}}

   A function is its folder, name and arguments, with pg_get_functiondef's
   text, its name left out (leave-out-function-name); a view its pg_get_viewdef's text and options, and the columns and
   views it reads."
  [pool folders]
  (let [params {:folders (str/join "," folders)}
        reads  (group-by :view-name (sql/find-rows pool find-view-reads params))]
    {:folders    (set (map :folder (sql/find-rows pool find-folders {})))
     :extensions (set (map :extension-name (sql/find-rows pool find-extensions {})))
     :functions  (into {} (for [{:keys [folder function-name arguments definition]} (sql/find-rows pool find-functions params)]
                            [[folder function-name arguments] (leave-out-function-name definition)]))
     :views      (into {} (for [{:keys [view-name definition options]} (sql/find-rows pool find-views params)
                                :let [read (get reads view-name)]]
                            [view-name {:definition definition :options options
                                        :reads      (set (for [{:keys [read-name read-is-view column-name]} read
                                                               :when (not read-is-view)]
                                                           [read-name column-name]))
                                        :views-read (set (for [{:keys [read-name read-is-view]} read
                                                               :when read-is-view]
                                                           read-name))}]))}))
