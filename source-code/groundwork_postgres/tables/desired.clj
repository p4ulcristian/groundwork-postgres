(ns groundwork-postgres.tables.desired
  "The table maps turned into the form catalog.clj reads from Postgres, so
   the two can be compared, and a table map turned into its row shape.

     {:table     :orders
      :columns   [[:order/id      :uuid   {:primary-key? true :default :random-uuid}]
                  [:order/shop-id :uuid   {:references {:to :shop/id :on-delete :refuse :on-update :refuse
                                                         :checked-at :statement}}]
                  [:order/note    :string {:optional? true}]]
      :indexes   [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
      :uniques   []
      :checks    []
      :references []
      :triggers  []
      :gates     nil
      :on-change nil}

   A check is its SQL (:sql) and an index the SQL after its table
   (:sql-body), as groundwork-postgres writes them; a column's default is :default-sql.
   How Postgres prints them comes from groundwork-postgres.tables.probe, for the
   comparing. The maps are checked by groundwork-postgres.settings/tables-shape before
   they come here."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork-postgres.queries.params :as params]
   [groundwork-postgres.tables.gates :as gates]
   [groundwork-postgres.tables.names :as names]
   [groundwork-postgres.tables.quotes :as quotes]
   [groundwork-postgres.tables.triggers :as triggers]
   [groundwork-postgres.tables.types :as types]))

(defn- find-table-of-primary-key
  "{:shop/id \"shops\"}: which table each one-column primary key is in."
  [table-maps]
  (into {}
        (for [{:keys [table columns]} table-maps
              :let [primary-key (for [[k _ options] columns :when (:primary-key? options)] k)]
              :when (= 1 (count primary-key))]
          [(first primary-key) (names/turn-table-key-into-table-name table)])))

(defn- throw-bad-reference [message references]
  (throw (ex-info message {:type :groundwork-postgres/bad-reference :references references})))

(defn- find-target-of-reference
  "The table map a reference over several columns points at: the one that
   has every :to column, its primary key among them. Throws when there is
   none, or when the columns don't pair up."
  [table-maps table-name {:keys [columns to] :as reference}]
  (let [has-column? (fn [table-map k] (some #(= k (first %)) (:columns table-map)))
        target      (first (filter (fn [table-map] (every? #(has-column? table-map %) to)) table-maps))
        primary-key (when target (for [[k _ options] (:columns target) :when (:primary-key? options)] k))
        own         (first (filter #(= table-name (names/turn-table-key-into-table-name (:table %))) table-maps))]
    (cond
      (not= (count columns) (count to))
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " pairs " (count columns)
                                " columns with " (count to)) reference)
      (some #(not (has-column? own %)) columns)
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " names a column it doesn't have") reference)
      (nil? target)
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " points at no table that has every :to column")
                           reference)
      (not (and (seq primary-key) (every? (set to) primary-key)))
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " must name its target's primary key among :to")
                           reference)
      :else target)))

(defn- check-set-null
  "A reference that sets columns to null on a delete or update names only
   :optional? columns: all of its own on :set-null, those it lists on
   {:set-null [key …]}, which must be some of its :columns, each once, not
   every one. Throws otherwise."
  [table-name table-map column-keys {:keys [on-delete on-update] :as reference}]
  (let [listed      (when (map? on-delete) (:set-null on-delete))
        set-to-null (distinct (concat (when (some #{:set-null} [on-delete on-update]) column-keys) listed))
        optional?   (into {} (for [[k _ options] (:columns table-map)] [k (boolean (:optional? options))]))]
    (when listed
      (when-let [foreign (seq (remove (set column-keys) listed))]
        (throw-bad-reference (str table-name "'s reference " (pr-str reference) " sets " (pr-str (vec foreign))
                                  " to null on delete, which isn't among its :columns " (pr-str (vec column-keys)))
                             reference))
      (when (not= (count listed) (count (distinct listed)))
        (throw-bad-reference (str table-name "'s reference " (pr-str reference) " names a column twice in :set-null")
                             reference))
      (when (= (set listed) (set column-keys))
        (throw-bad-reference (str table-name "'s reference " (pr-str reference) " sets every one of its :columns to null"
                                  " on delete: say :on-delete :set-null")
                             reference)))
    (when-let [required (seq (remove optional? set-to-null))]
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " sets " (pr-str (vec required))
                                " to null, which isn't {:optional? true}")
                           reference))))

(defn- turn-delete-action-into-desired
  "A reference's :on-delete as the catalog reads it: a keyword, or
   {:set-null [column-name …]} in the order of the reference's columns."
  [column-keys on-delete]
  (if (map? on-delete)
    {:set-null (mapv names/turn-key-into-column-name (filter (set (:set-null on-delete)) column-keys))}
    on-delete))

(defn- make-foreign-key [foreign-table column-keys foreign-column-names {:keys [on-delete on-update checked-at]}]
  {:columns (mapv names/turn-key-into-column-name column-keys) :foreign-table foreign-table :foreign-columns foreign-column-names
   :on-delete (turn-delete-action-into-desired column-keys on-delete) :on-update on-update :checked-at checked-at :match :simple})

(defn- turn-references-into-foreign-keys
  "{name {:columns :foreign-table :foreign-columns :on-delete :on-update
   :checked-at :match}}: a column's :references and the table's
   references over several columns, each saying what it does."
  [table-maps tables-by-primary-key table-name {:keys [columns references] :as table-map}]
  (merge
   (into {}
         (for [[k _ {reference :references}] columns
               :when reference
               :let [target        (:to reference)
                     column-name   (names/turn-key-into-column-name k)
                     foreign-table (or (get tables-by-primary-key target)
                                       (throw-bad-reference (str table-name "." column-name " references "
                                                                 target ", which is no table's one-column primary key")
                                                            reference))
                     _             (check-set-null table-name table-map [k] reference)]]
           [(names/make-foreign-key-name table-name [column-name])
            (make-foreign-key foreign-table [k] [(names/turn-key-into-column-name target)] reference)]))
   (into {}
         (for [{:keys [to] :as reference} references
               :let [target       (find-target-of-reference table-maps table-name reference)
                     column-names (mapv names/turn-key-into-column-name (:columns reference))
                     _            (check-set-null table-name table-map (:columns reference) reference)]]
           [(names/make-foreign-key-name table-name column-names)
            (make-foreign-key (names/turn-table-key-into-table-name (:table target)) (:columns reference)
                              (mapv names/turn-key-into-column-name to) reference)]))))

(defn- check-triggers
  "A trigger's :update-of names columns of its table, and only with
   :update among its events; one on :truncate is :for-each :statement.
   Throws otherwise."
  [table-name {:keys [columns triggers]}]
  (doseq [{:keys [events update-of for-each] :as trigger} triggers]
    (when (and (contains? events :truncate) (not= :statement for-each))
      (throw (ex-info (str table-name "'s trigger " (pr-str trigger) " is on :truncate, so :for-each :statement:"
                           " Postgres calls no row trigger on TRUNCATE")
                      {:type :groundwork-postgres/bad-trigger :trigger trigger})))
    (when (and update-of (not (contains? events :update)))
      (throw (ex-info (str table-name "'s trigger " (pr-str trigger) " has :update-of without :update among its events")
                      {:type :groundwork-postgres/bad-trigger :trigger trigger})))
    (when-let [unknown (seq (remove (set (map first columns)) update-of))]
      (throw (ex-info (str table-name "'s trigger " (pr-str trigger) " names " (pr-str (vec unknown)) ", no column of it")
                      {:type :groundwork-postgres/bad-trigger :trigger trigger})))))

(defn- throw-bad-index [message index]
  (throw (ex-info message {:type :groundwork-postgres/bad-index :index index})))

(defn- write-index-part [part]
  (if (string? part) (str "(" part ")") (names/turn-key-into-column-name part)))

(defn- is-plain-index?
  "A btree index over plain columns, each ascending with nulls last, its
   types' own operator classes, every row, not unique: named after its
   columns alone, as groundwork-postgres named every index before index maps."
  [{:keys [columns method ops unique? where]}]
  (and (= :btree method) (empty? ops) (nil? where) (not unique?)
       (every? (fn [[part order nulls]] (and (keyword? part) (= :ascending order) (= :nulls-last nulls))) columns)))

(defn- turn-index-into-desired
  "[name {:columns :unique? :sql-body}] of an index map: :sql-body the SQL
   after CREATE INDEX name ON table. A plain index is named after its
   columns; any other after its columns and a hash of its SQL, so a
   changed one is a new name. Throws when the index can't be made."
  [table-name {own-columns :columns} {:keys [columns method ops unique? where] :as index}]
  (let [parts      (map first columns)
        has-column (set (map first own-columns))
        ordered?   (= :btree method)]
    (when-let [unknown (seq (remove has-column (filter keyword? (concat parts (keys ops)))))]
      (throw-bad-index (str table-name "'s index " (pr-str index) " names " (pr-str (vec unknown)) ", no column of it") index))
    (when-let [unknown (seq (remove (set parts) (keys ops)))]
      (throw-bad-index (str table-name "'s index " (pr-str index) " has :ops for " (pr-str (vec unknown)) ", none of its :columns") index))
    (when-not (every? #(= (if ordered? 3 2) (count %)) columns)
      (throw-bad-index (str table-name "'s index " (pr-str index) ": a :btree index's columns say their order and nulls,"
                            " [:column :ascending :nulls-last]; another method's are [:column :unordered]")
                       index))
    (when (and unique? (not ordered?))
      (throw-bad-index (str table-name "'s index " (pr-str index) " is :unique?, which only a :btree index can be") index))
    (let [sql-body     (str "USING " (name method) " ("
                            (str/join ", " (for [[part order nulls] columns]
                                             (str (write-index-part part)
                                                  (when-let [op (get ops part)] (str " " (str/replace (name op) "-" "_")))
                                                  (when ordered?
                                                    (str (if (= :descending order) " DESC" " ASC")
                                                         (if (= :nulls-first nulls) " NULLS FIRST" " NULLS LAST"))))))
                            ")"
                            (when where (str " WHERE " where)))
          column-names (mapv names/turn-key-into-column-name (filter keyword? parts))]
      [(if (is-plain-index? index)
         (names/make-index-name table-name column-names)
         (apply names/make-identifier table-name
                (concat column-names [(format "%08x" (bit-and (hash [unique? sql-body]) 0xffffffff)) "idx"])))
       {:columns column-names :unique? unique? :sql-body sql-body}])))

(defn- check-uniques
  "A table's :uniques name its columns; throws otherwise."
  [table-name {:keys [columns uniques]}]
  (doseq [unique uniques
          :let [unknown (seq (remove (set (map first columns)) unique))]
          :when unknown]
    (throw (ex-info (str table-name "'s unique key " (pr-str unique) " names " (pr-str (vec unknown)) ", no column of it")
                    {:type :groundwork-postgres/bad-unique :unique unique}))))

(defn- turn-table-map-into-desired [table-maps tables-by-primary-key {:keys [table columns indexes uniques checks gates on-change triggers]}]
  (let [table-name (names/turn-table-key-into-table-name table)
        column     (fn [[k type {:keys [optional? default]}]]
                     (let [postgres-type (types/turn-type-into-postgres-type type)]
                       {:name              (names/turn-key-into-column-name k)
                        :type              postgres-type
                        :not-null?         (not optional?)
                        :default-sql       (when (some? default) (types/turn-default-into-sql default postgres-type))
                        :volatile-default? (types/is-default-volatile? default)}))
        table-map  (first (filter #(= table (:table %)) table-maps))]
    (check-triggers table-name table-map)
    (check-uniques table-name table-map)
    [table-name
     {:columns      (mapv column columns)
      :primary-key  (vec (for [[k _ options] columns :when (:primary-key? options)]
                           (names/turn-key-into-column-name k)))
      :foreign-keys (turn-references-into-foreign-keys table-maps tables-by-primary-key table-name table-map)
      :uniques      (into {}
                          (concat
                           (for [[k _ {:keys [unique?]}] columns
                                 :when unique?
                                 :let [column-name (names/turn-key-into-column-name k)]]
                             [(names/make-unique-name table-name [column-name]) {:columns [column-name]}])
                           (for [unique uniques
                                 :let [column-names (mapv names/turn-key-into-column-name unique)]]
                             [(names/make-unique-name table-name column-names) {:columns column-names}])))
      :checks       (into {}
                          (concat
                           (for [[k type] columns
                                 :let [values (types/find-enum-values type)]
                                 :when values
                                 :let [column-name (names/turn-key-into-column-name k)]]
                             [(names/make-enum-check-name table-name column-name)
                              {:sql (str column-name " IN (" (str/join ", " (map quotes/quote-text values)) ")")}])
                           (for [condition checks]
                             [(names/make-check-name table-name condition) {:sql condition}])))
      :gated?       (some? gates)
      :policies     (if gates (gates/turn-gates-into-policies (names/turn-table-key-into-table-name table) gates) {})
      :triggers     (merge (if on-change (triggers/turn-on-change-into-triggers table-name on-change) {})
                           (triggers/turn-declared-triggers-into-triggers table-name triggers))
      :indexes      (into {} (map #(turn-index-into-desired table-name table-map %)) indexes)}]))

(defn- add-unique-keys-of-references
  "Postgres points a reference only at columns unique together: every
   reference over several columns gives its target a unique key on them.
   Its target's primary key is among them, so it holds for every row."
  [desired]
  (reduce (fn [desired {:keys [foreign-table foreign-columns]}]
            (if (and (< 1 (count foreign-columns)) (contains? desired foreign-table))
              (assoc-in desired [foreign-table :uniques (names/make-unique-name foreign-table foreign-columns)]
                        {:columns foreign-columns})
              desired))
          desired
          (mapcat (comp vals :foreign-keys) (vals desired))))

(defn turn-table-maps-into-desired
  "Every table map in the catalog's form, by table name. A table named
   twice, or a reference to no table's primary key, throws."
  [table-maps]
  (let [table-names (map (comp names/turn-table-key-into-table-name :table) table-maps)]
    (when (not= (count table-names) (count (set table-names)))
      (throw (ex-info "A table is declared twice" {:type :groundwork-postgres/table-twice :tables table-names}))))
  (let [tables-by-primary-key (find-table-of-primary-key table-maps)]
    (add-unique-keys-of-references
     (into {} (map #(turn-table-map-into-desired table-maps tables-by-primary-key %)) table-maps))))

(defn turn-table-into-row-shape
  "The malli shape of a table's rows, for groundwork-shaped-json: an optional column may be
   missing or nil."
  [{:keys [columns]}]
  (into [:map]
        (for [[k type {:keys [optional?]}] columns]
          (if optional?
            [k {:optional true} [:maybe type]]
            [k type]))))

;; -----------------------------------------------------------------------------
;; Extensions, functions and views

(def ^:private leading-comments
  "(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*")

(def ^:private identifier
  ;; a plain name, or a quoted one ("may orders/edit on orders", "" for a quote inside)
  "(?:[a-z_][a-z0-9_]*|\"(?:[^\"]|\"\")+\")")

(def ^:private function-header
  (re-pattern (str "(?is)^(" leading-comments "CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+)"
                   "((?:(" identifier ")\\.)?(" identifier "))(?=\\s*\\()")))

(defn- turn-identifier-into-name
  "A name as Postgres keeps it: a quoted one without its quotes, a plain
   one in lower case, as Postgres folds it."
  [identifier]
  (if (str/starts-with? identifier "\"")
    (str/replace (subs identifier 1 (dec (count identifier))) "\"\"" "\"")
    (str/lower-case identifier)))

(def ^:private view-header
  (re-pattern (str "(?is)^(" leading-comments "CREATE\\s+(?:OR\\s+REPLACE\\s+)?VIEW\\s+)"
                   "(?:([a-z_][a-z0-9_]*)\\.)?([a-z_][a-z0-9_]*)(?=[\\s(])")))

(defn- read-sql-file [path]
  (let [url (or (io/resource path)
                (throw (ex-info (str "No SQL file " path) {:type :groundwork-postgres/missing-file :path path})))]
    (slurp url)))

(defn- throw-bad-file [path message]
  (throw (ex-info (str path ": " message) {:type :groundwork-postgres/bad-file :path path})))

(defn- check-no-params [path sql-text]
  (when (seq (:param-names (params/turn-sql-text-into-statement sql-text)))
    (throw-bad-file path "has params; a function's or a view's SQL runs without any")))

(defn- throw-bad-function [function-name message]
  (throw (ex-info (str "the made function " function-name ": " message) {:type :groundwork-postgres/bad-function :name function-name})))

(defn- turn-function-into-desired
  "One entry of :functions, a file's path or a made function {:name :sql},
   as {:key :source :from :folder :name :sql :header :rest}: :key its place
   in the list, :source what a message names it by."
  [i entry]
  (let [made?    (map? entry)
        source   (if made? (str "the made function " (:name entry)) entry)
        sql-text (if made? (:sql entry) (read-sql-file entry))
        refuse   (fn [message] (if made? (throw-bad-function (:name entry) message) (throw-bad-file entry message)))
        [header-and-name header written-name folder function-name]
        (or (re-find function-header sql-text)
            (refuse (str (if made? "its SQL" "a function's file") " starts with CREATE OR REPLACE FUNCTION and a name")))]
    (when (and made? (not= written-name (:name entry)))
      (refuse (str "its SQL makes " written-name ", not the :name it says")))
    (when (seq (:param-names (params/turn-sql-text-into-statement sql-text)))
      (refuse "has params; a function's or a view's SQL runs without any"))
    {:key    i
     :source source
     :from   (if made? "made by a library" (str "from " entry))
     :folder (if folder (turn-identifier-into-name folder) "public")
     :name   (turn-identifier-into-name function-name)
     :sql    sql-text
     :header header
     :rest   (subs sql-text (count header-and-name))}))

(defn turn-declared-into-desired
  "The extensions, functions and views the app declares, their files read:
   {:extensions [name] :functions [{:key :source :from :folder :name :sql
   :header :rest}] :views [{:file :view-name :sql :header :rest}]}, :header
   and :rest what comes before and after the name, so the probe can make
   it under another. A function is a .sql file's path, or a function a
   library made, {:name \"groundwork_access.denied_ab12\" :sql \"CREATE OR REPLACE
   FUNCTION groundwork_access.denied_ab12() …\"}, its :name as its SQL writes it. A
   file that isn't one CREATE OR REPLACE FUNCTION (or CREATE VIEW) with a
   plain name throws :groundwork-postgres/bad-file; a made function that isn't one, or
   makes another name than its :name, :groundwork-postgres/bad-function."
  [{:keys [extensions functions views]}]
  {:extensions (vec extensions)
   :functions  (vec (map-indexed turn-function-into-desired functions))
   :views      (vec (for [path views
                          :let [sql-text (read-sql-file path)
                                [header-and-name header folder view-name]
                                (or (re-find view-header sql-text)
                                    (throw-bad-file path "a view's file starts with CREATE VIEW and a plain name"))]]
                      (do (check-no-params path sql-text)
                          {:file      path
                           :view-name (if (and folder (not= "public" folder)) (str folder "." view-name) view-name)
                           :header    header
                           :rest      (subs sql-text (count header-and-name))
                           :sql       sql-text})))})
