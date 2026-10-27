(ns groundwork-postgres.tables.plan
  "The plan: what to run so the database matches the maps.

   First the change files not yet run are applied, on paper, to what
   Postgres has (a rename renames, a drop drops). Then that is compared with
   the maps. Every difference becomes statements, each in one of two parts:

     :instant  changes only Postgres's catalog; runs before the app starts
     :slow     reads or writes every row; runs after, in the background

   What the map names that Postgres has under another name, the same
   shape, is adopted: renamed to the map's name (catalog only, no scan, no
   rebuild). A key's shape is its columns and target; a check's and an
   index's is how Postgres prints them (groundwork-postgres.tables.probe).

   A difference that could lose data or that v1 can't make is a stop: a
   message, and nothing runs."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [groundwork-postgres.tables.names :as names]
   [groundwork-postgres.tables.quotes :as quotes]
   [groundwork-postgres.queries.postgres :as sql]))

(defn- statement [sql-text why] {:sql sql-text :why why})

(defn- add-instant [state sql-text why] (update state :instant conj (statement sql-text why)))
(defn- add-slow [state sql-text why] (update state :slow conj (statement sql-text why)))
(defn- add-stop [state message] (update state :stops conj message))

;; -----------------------------------------------------------------------------
;; Change files, applied on paper

(defn- rename-derived-names
  "After a table or column rename, the keys groundwork-postgres named after the old
   names get the new ones (Postgres keeps the old names). Checks and
   indexes are found by their shape instead, and adopted (adopt-pieces)."
  [state old-table new-table column-renames]
  (let [old-column (fn [c] (get (set/map-invert column-renames) c c))
        table      (get-in state [:catalog new-table])
        renames    (concat
                    (for [[n {:keys [columns]}] (:foreign-keys table)
                          :when (= n (names/make-foreign-key-name old-table (map old-column columns)))]
                      [:foreign-keys n (names/make-foreign-key-name new-table columns) :constraint])
                    (for [[n {:keys [columns]}] (:uniques table)
                          :when (= n (names/make-unique-name old-table (map old-column columns)))]
                      [:uniques n (names/make-unique-name new-table columns) :constraint])
                    (for [[n {:keys [column]}] (:not-null-checks table)
                          :when (= n (names/make-not-null-check-name old-table (old-column column)))]
                      [:not-null-checks n (names/make-not-null-check-name new-table column) :constraint]))]
    (reduce (fn [state [kind old-name new-name what]]
              (if (= old-name new-name)
                state
                (-> state
                    (update-in [:catalog new-table kind] set/rename-keys {old-name new-name})
                    (add-instant (str "ALTER TABLE " new-table " RENAME CONSTRAINT " old-name " TO " new-name)
                                 (str "keep the name of " old-name " after a rename")))))
            state
            renames)))

(defn- point-references-at [catalog old-table new-table column-renames]
  (update-vals catalog
               (fn [table]
                 (update table :foreign-keys update-vals
                         (fn [{:keys [foreign-table foreign-columns] :as fk}]
                           (if (= foreign-table old-table)
                             (assoc fk :foreign-table new-table
                                    :foreign-columns (mapv #(get column-renames % %) foreign-columns))
                             fk))))))

(defn rename-column-in-sql
  "SQL as Postgres prints it, with the column from named to, as Postgres
   will print it once the rename has run: the whole name only, never
   inside a quoted text or name."
  [sql-text from to]
  (when sql-text
    (str/replace sql-text
                 (re-pattern (str "'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|\\b" (java.util.regex.Pattern/quote from) "\\b"))
                 #(if (= % from) to %))))

(defn- rename-column-in-table [table from to]
  (let [rename #(if (= % from) to %)]
    (-> table
        (update :checks update-vals #(update % :condition rename-column-in-sql from to))
        (update :indexes update-vals #(update % :body rename-column-in-sql from to))
        (update :columns (partial mapv #(update % :name rename)))
        (update :primary-key (partial mapv rename))
        (update :foreign-keys update-vals #(update % :columns (partial mapv rename)))
        (update :uniques update-vals #(update % :columns (partial mapv rename)))
        (update :not-null-checks update-vals #(update % :column rename))
        (update :indexes update-vals #(update % :columns (partial mapv rename))))))

(defn- find-references-to [catalog table-name column-name]
  (for [[from-table {:keys [foreign-keys]}] catalog
        [_ {:keys [foreign-table foreign-columns]}] foreign-keys
        :when (and (= foreign-table table-name) (or (nil? column-name) (some #{column-name} foreign-columns)))
        :when (not= from-table table-name)]
    from-table))

(defn- change-column-type
  "A column's type changed to its map's, on paper, and in the slow part
   (it may rewrite the table): ALTER COLUMN … TYPE, then the change marked
   done, so a failed ALTER leaves it to run again. The table is the one map
   with the column; :from must be what Postgres has. Already of the map's
   type, it is only marked done."
  [state {:keys [id from] column-key :change-column-type} stop]
  (let [column-name (names/turn-key-into-column-name column-key)
        has-column? (fn [columns] (some #(when (= column-name (:name %)) %) columns))
        tables      (for [[table-name {:keys [columns]}] (:desired state) :when (has-column? columns)] table-name)
        table-name  (first tables)
        to          (:type (has-column? (get-in state [:desired table-name :columns])))
        existing    (has-column? (get-in state [:catalog table-name :columns]))
        where       (str table-name "." column-name)]
    (cond
      (empty? tables)              (stop (str "no map has a column " column-name))
      (next tables)                (stop (str "several maps have a column " column-name ": " (str/join ", " (sort tables))))
      (nil? existing)              (stop (str "there is no column " where " in Postgres to change"))
      (= to (:type existing))      state
      (not= from (:type existing)) (stop (str where " is " (:type existing) " in Postgres, not " from " as its :from says"))
      :else (-> state
                (add-slow (str "ALTER TABLE " table-name " ALTER COLUMN " column-name " TYPE " to
                               " USING " column-name "::" to)
                          (str "change " id ": " where " from " from " to " to))
                (add-slow (str "INSERT INTO groundwork_postgres.changes_done (change_done__id) VALUES (" (quotes/quote-text id) ")"
                               " ON CONFLICT (change_done__id) DO NOTHING")
                          (str "change " id " done, after its type change"))
                (update :marked-in-slow-part (fnil conj #{}) id)
                (update :type-changes (fnil conj []) [table-name column-name id])
                (update-in [:catalog table-name :columns]
                           (partial mapv #(if (= column-name (:name %)) (assoc % :type to) %)))))))

(defn- apply-change
  [state {:keys [id rename-table rename-column drop-column drop-table run-sql-file why] :as change}]
  (let [catalog (:catalog state)
        stop    #(add-stop state (str "change " id ": " %))]
    (cond
      (contains? change :change-column-type)
      (if (and (keyword? (:change-column-type change)) (string? (:from change)))
        (change-column-type state change stop)
        (stop "a type change names its column's key and says the type Postgres has, {:change-column-type :order/note :from \"character(3)\"}"))

      rename-table
      (let [[from to] (map names/turn-table-key-into-table-name rename-table)]
        (cond
          (not (contains? catalog from)) (stop (str "there is no table " from " to rename"))
          (contains? catalog to)         (stop (str "there is already a table " to))
          :else (-> state
                    (add-instant (str "ALTER TABLE " from " RENAME TO " (names/find-table-part to)) (str "change " id))
                    (assoc :catalog (-> catalog
                                        (set/rename-keys {from to})
                                        (point-references-at from to {})))
                    (rename-derived-names from to {}))))

      rename-column
      (let [[table-key from-key to-key] rename-column
            table-name (names/turn-table-key-into-table-name table-key)
            from       (names/turn-key-into-column-name from-key)
            to         (names/turn-key-into-column-name to-key)
            columns    (set (map :name (get-in catalog [table-name :columns])))]
        (cond
          (not (contains? columns from)) (stop (str "there is no column " table-name "." from " to rename"))
          (contains? columns to)         (stop (str "there is already a column " table-name "." to))
          :else (-> state
                    (add-instant (str "ALTER TABLE " table-name " RENAME COLUMN " from " TO " to) (str "change " id))
                    (update-in [:catalog table-name] rename-column-in-table from to)
                    (update :catalog point-references-at table-name table-name {from to})
                    (rename-derived-names table-name table-name {from to}))))

      drop-column
      (let [[table-key column-key] drop-column
            table-name (names/turn-table-key-into-table-name table-key)
            column     (names/turn-key-into-column-name column-key)]
        (cond
          (not why) (stop "a drop needs a :why")
          (not (some #{column} (map :name (get-in catalog [table-name :columns]))))
          (stop (str "there is no column " table-name "." column " to drop"))
          (seq (find-references-to catalog table-name column))
          (stop (str table-name "." column " is referenced by " (str/join ", " (find-references-to catalog table-name column))
                     "; remove those references from their maps first"))
          :else (-> state
                    (add-instant (str "ALTER TABLE " table-name " DROP COLUMN " column) (str "change " id ": " why))
                    (update-in [:catalog table-name]
                               (fn [table]
                                 (let [uses? #(= column %)]
                                   (-> table
                                       (update :columns (partial filterv #(not (uses? (:name %)))))
                                       (update :foreign-keys #(into {} (remove (fn [[_ {:keys [columns]}]] (some uses? columns))) %))
                                       (update :uniques #(into {} (remove (fn [[_ {:keys [columns]}]] (some uses? columns))) %))
                                       (update :not-null-checks #(into {} (remove (comp uses? :column val)) %))
                                       (update :indexes #(into {} (remove (fn [[_ {:keys [columns]}]] (some uses? columns))) %)))))))))

      drop-table
      (let [table-name (names/turn-table-key-into-table-name drop-table)]
        (cond
          (not why) (stop "a drop needs a :why")
          (not (contains? catalog table-name)) (stop (str "there is no table " table-name " to drop"))
          (seq (find-references-to catalog table-name nil))
          (stop (str table-name " is referenced by " (str/join ", " (find-references-to catalog table-name nil))
                     "; remove those references from their maps first"))
          :else (-> state
                    (add-instant (str "DROP TABLE " table-name) (str "change " id ": " why))
                    (update :catalog dissoc table-name))))

      run-sql-file
      (let [query (sql/make-query-from-sql-file run-sql-file)]
        (if (seq (:param-names query))
          (stop (str run-sql-file " has params; a change file's SQL runs without any"))
          (add-instant state (:text-with-question-marks query) (str "change " id ": " run-sql-file))))

      :else (stop (str "not a change schema knows: " (pr-str (dissoc change :id)))))))

;; -----------------------------------------------------------------------------
;; Adopting what Postgres has under another name

(defn- find-adoptions
  "[Postgres's name, the map's name] for each piece the map names that
   Postgres hasn't under that name but has under another one no map
   names, of the same shape (shape-of; nil is no shape, never the same)."
  [existing desired shape-of]
  (loop [wanted (remove #(contains? existing (key %)) desired)
         free   (vec (remove #(contains? desired (key %)) existing))
         found  []]
    (if-let [[desired-name piece] (first wanted)]
      (let [shape (shape-of piece)
            match (when (some? shape) (first (filter #(= shape (shape-of (val %))) free)))]
        (if match
          (recur (rest wanted) (vec (remove #(= (key match) (key %)) free)) (conj found [(key match) desired-name]))
          (recur (rest wanted) free found)))
      found)))

(defn- reference-shape [fk] (select-keys fk [:columns :foreign-table :foreign-columns]))

(def ^:private adopted-kinds
  ;; kind, its shape, and how Postgres renames it
  [[:foreign-keys reference-shape :constraint]
   [:uniques :columns :constraint]
   [:checks :condition :constraint]
   [:indexes #(when (:body %) [(:unique? %) (:body %)]) :index]])

(defn- adopt-pieces
  "A table's keys, checks and indexes Postgres has under another name, the
   same shape as the map's: renamed to the map's name, in the instant part
   (ALTER … RENAME changes only the catalog)."
  [state table-name desired]
  (reduce (fn [state [kind shape-of what]]
            (reduce (fn [state [old-name new-name]]
                      (-> state
                          (update-in [:catalog table-name kind] set/rename-keys {old-name new-name})
                          (add-instant (if (= :index what)
                                         (str "ALTER INDEX " (names/qualify-index-name table-name old-name) " RENAME TO " new-name)
                                         (str "ALTER TABLE " table-name " RENAME CONSTRAINT " old-name " TO " new-name))
                                       (str "adopt " old-name " as " new-name ": the same as its map's"))))
                    state
                    (find-adoptions (get-in state [:catalog table-name kind]) (get desired kind) shape-of)))
          state
          adopted-kinds))

(defn- promote-unique-indexes
  "A unique key the map names that Postgres has as a plain unique index no
   map names, on the same columns: made a key from that index (ADD
   CONSTRAINT … UNIQUE USING INDEX, the catalog only: no scan, no
   rebuild; Postgres renames the index to the key's name)."
  [state table-name desired]
  (reduce (fn [state [n {:keys [columns]}]]
            (let [plain (str "USING btree (" (str/join ", " columns) ")")
                  match (first (for [[index-name index] (get-in state [:catalog table-name :indexes])
                                     :when (and (not (contains? (:indexes desired) index-name))
                                                (:unique? index) (:valid? index) (= plain (:body index)))]
                                 index-name))]
              (if-not match
                state
                (-> state
                    (update-in [:catalog table-name :indexes] dissoc match)
                    (assoc-in [:catalog table-name :uniques n] {:columns columns})
                    (add-instant (str "ALTER TABLE " table-name " ADD CONSTRAINT " n " UNIQUE USING INDEX " match)
                                 (str "unique key " n " from the unique index " match " Postgres has"))))))
          state
          (remove #(contains? (get-in state [:catalog table-name :uniques]) (key %)) (:uniques desired))))

(defn- add-printed
  "A table's desired pieces with how Postgres prints them (the probe's):
   a column's :default, a check's :condition, an index's :body."
  [desired {:keys [defaults checks indexes]}]
  (-> desired
      (update :columns (partial mapv #(assoc % :default (get defaults (:name %)))))
      (update :checks #(into {} (map (fn [[n check]] [n (assoc check :condition (get checks n))])) %))
      (update :indexes #(into {} (map (fn [[n index]] [n (assoc index :body (get indexes n))])) %))))

;; -----------------------------------------------------------------------------
;; Comparing a table

(defn- write-column [{:keys [name type not-null? default-sql]}]
  (str name " " type (when not-null? " NOT NULL") (when default-sql (str " DEFAULT " default-sql))))

(defn- write-check [table-name check-name {:keys [sql]}]
  (str "ALTER TABLE " table-name " ADD CONSTRAINT " check-name " CHECK (" sql ")"))

(defn- write-index [table-name index-name {:keys [unique? sql-body]} concurrently?]
  (str "CREATE " (when unique? "UNIQUE ") "INDEX " (when concurrently? "CONCURRENTLY IF NOT EXISTS ")
       index-name " ON " table-name " " sql-body))

(def ^:private action-sql
  {:cascade "CASCADE" :set-null "SET NULL" :refuse "NO ACTION" :restrict "RESTRICT"})

(def ^:private checked-at-sql
  {:statement "NOT DEFERRABLE" :commit "DEFERRABLE INITIALLY DEFERRED"})

(defn- write-foreign-key [table-name fk-name {:keys [columns foreign-table foreign-columns on-delete on-update checked-at]}]
  (str "ALTER TABLE " table-name " ADD CONSTRAINT " fk-name
       " FOREIGN KEY (" (str/join ", " columns) ") REFERENCES " foreign-table " (" (str/join ", " foreign-columns) ")"
       " ON DELETE " (action-sql on-delete) " ON UPDATE " (action-sql on-update)
       (when (= :commit checked-at) (str " " (checked-at-sql checked-at)))))

(defn- write-table-parts [{:keys [columns primary-key uniques]}]
  (concat (map write-column columns)
          (when (seq primary-key) [(str "PRIMARY KEY (" (str/join ", " primary-key) ")")])
          (for [[n {:keys [columns]}] uniques] (str "CONSTRAINT " n " UNIQUE (" (str/join ", " columns) ")"))))

(defn- does-reference-wait-for-unique-key?
  "Whether a reference over several columns points at a table Postgres
   has, whose unique key on those columns this plan makes in its slow
   part: the reference can be made only after it."
  [catalog {:keys [foreign-table foreign-columns]}]
  (and (< 1 (count foreign-columns))
       (contains? catalog foreign-table)
       (not (contains? (get-in catalog [foreign-table :uniques]) (names/make-unique-name foreign-table foreign-columns)))))

(defn- add-waiting-reference
  "A reference that waits for its target's unique key: made in the slow
   part, after every unique key, then proven."
  [state table-name n fk]
  (update state :waiting-references conj [table-name n fk]))

(defn- plan-waiting-references [state]
  (reduce (fn [state [table-name n fk]]
            (-> state
                (add-slow (str (write-foreign-key table-name n fk) " NOT VALID")
                          (str "reference " n ", after its target's unique key"))
                (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows"))))
          state
          (:waiting-references state)))

(defn- plan-new-table
  "A table not in Postgres: made whole. It has no rows, so its keys and
   indexes are made at once, in the instant part."
  [state table-name {:keys [checks indexes] :as table}]
  (as-> state state
    (add-instant state
                 (str "CREATE TABLE " table-name " (\n  " (str/join ",\n  " (write-table-parts table)) "\n)")
                 (str "missing table " table-name))
    (reduce (fn [state [n check]] (add-instant state (write-check table-name n check) (str "check of new table " table-name)))
            state checks)
    (reduce (fn [state [n index]]
              (add-instant state (write-index table-name n index false) (str "index of new table " table-name)))
            state indexes)))

(defn- plan-foreign-keys-of-new-table [state table-name {:keys [foreign-keys]}]
  (reduce (fn [state [n fk]]
            (if (does-reference-wait-for-unique-key? (:catalog state) fk)
              (add-waiting-reference state table-name n fk)
              (add-instant state (write-foreign-key table-name n fk) (str "reference of new table " table-name))))
          state foreign-keys))

(defn- plan-column
  "A column: added, or compared with Postgres's. Its :default is how
   Postgres prints the map's (groundwork-postgres.tables.probe), on a table it has."
  [state table-name has-rows? existing {:keys [name type not-null? default default-sql volatile-default?] :as column}]
  (let [where (str table-name "." name)]
    (if-not existing
      (cond
        (and not-null? (not default-sql) (has-rows? table-name))
        (add-stop state (str where " is required but " table-name " has rows: give it a :default, "
                             "or add it {:optional? true} and fill it with a change file"))
        (and volatile-default? (has-rows? table-name))
        (add-stop state (str where "'s default is computed per row, so adding it would rewrite " table-name
                             ": add it {:optional? true} and fill it with a change file"))
        :else
        (add-instant state (str "ALTER TABLE " table-name " ADD COLUMN " (write-column column)) (str "missing column " where)))
      (cond-> state
        (not= type (:type existing))
        (add-stop (str where " is " (:type existing) " in Postgres but " type " in its map: add a change, "
                       "{:change-column-type <its key> :from \"" (:type existing) "\"}"))

        (and (not not-null?) (:not-null? existing))
        (add-instant (str "ALTER TABLE " table-name " ALTER COLUMN " name " DROP NOT NULL") (str where " became optional"))

        (and (contains? column :default) (not= default (:default existing)))
        (add-instant (if default-sql
                       (str "ALTER TABLE " table-name " ALTER COLUMN " name " SET DEFAULT " default-sql)
                       (str "ALTER TABLE " table-name " ALTER COLUMN " name " DROP DEFAULT"))
                     (str where "'s default changed"))))))

(defn- plan-required
  "A column that became required: proven on old rows without blocking
   writes, then made NOT NULL. A check NOT VALID now; VALIDATE, SET NOT NULL
   and dropping the check in the slow part."
  [state table-name existing-table desired-table]
  (reduce
   (fn [state {:keys [name not-null?]}]
     (let [existing   (some #(when (= name (:name %)) %) (:columns existing-table))
           check-name (names/make-not-null-check-name table-name name)
           check      (get-in existing-table [:not-null-checks check-name])
           finish     (fn [state]
                        (-> state
                            (add-slow (str "ALTER TABLE " table-name " ALTER COLUMN " name " SET NOT NULL")
                                      (str table-name "." name " became required"))
                            (add-slow (str "ALTER TABLE " table-name " DROP CONSTRAINT " check-name)
                                      (str table-name "." name " is NOT NULL now"))))]
       (cond
         (not (and existing not-null? (not (:not-null? existing)))) state
         (nil? check)
         (-> state
             (add-instant (str "ALTER TABLE " table-name " ADD CONSTRAINT " check-name
                               " CHECK (" name " IS NOT NULL) NOT VALID")
                          (str table-name "." name " became required"))
             (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " check-name)
                       (str "prove " table-name "." name " on old rows"))
             finish)
         (not (:validated? check))
         (-> state
             (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " check-name)
                       (str "prove " table-name "." name " on old rows"))
             finish)
         :else (finish state))))
   state
   (:columns desired-table)))

(defn- plan-uniques
  "A unique key on a table that may have rows: its index built CONCURRENTLY,
   then the key made from it, in the slow part. One in no map is dropped."
  [state table-name existing desired]
  (as-> state state
    (reduce (fn [state [n {:keys [columns]}]]
              (if (contains? (:uniques existing) n)
                state
                (-> state
                    (add-slow (str "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS " n " ON " table-name " (" (str/join ", " columns) ")")
                              (str "missing unique key " n))
                    (add-slow (str "ALTER TABLE " table-name " ADD CONSTRAINT " n " UNIQUE USING INDEX " n)
                              (str "unique key " n " from its index")))))
            state (:uniques desired))
    (reduce (fn [state [n _]]
              (if (contains? (:uniques desired) n)
                state
                (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "unique key " n " is in no map"))))
            state (:uniques existing))))

(defn- plan-gates
  "Row-level security on and forced when the map has :gates, off when it
   hasn't; the policies made and dropped by name. All instant."
  [state table-name existing desired]
  (as-> state state
    (cond
      (and (:gated? desired) (not (:gated? existing)))
      (add-instant state (str "ALTER TABLE " table-name " ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY")
                   (str table-name " gets its gates"))
      (and (not (:gated? desired)) (:gated? existing))
      (add-instant state (str "ALTER TABLE " table-name " DISABLE ROW LEVEL SECURITY, NO FORCE ROW LEVEL SECURITY")
                   (str table-name " has no gates in its map"))
      :else state)
    (reduce (fn [state [policy-name _]]
              (if (contains? (:policies desired) policy-name)
                state
                (add-instant state (str "DROP POLICY " policy-name " ON " table-name) (str "policy " policy-name " is in no map"))))
            state (:policies existing))
    (reduce (fn [state [policy-name policy-sql]]
              (if (contains? (:policies existing) policy-name)
                state
                (add-instant state policy-sql (str "missing policy " policy-name))))
            state (:policies desired))))

(defn- plan-triggers
  "The change triggers made and dropped by name. All instant."
  [state table-name existing desired]
  (as-> state state
    (reduce (fn [state [trigger-name _]]
              (if (contains? (:triggers desired) trigger-name)
                state
                (add-instant state (str "DROP TRIGGER " trigger-name " ON " table-name) (str "trigger " trigger-name " is in no map"))))
            state (:triggers existing))
    (reduce (fn [state [trigger-name trigger-sql]]
              (if (contains? (:triggers existing) trigger-name)
                state
                (add-instant state trigger-sql (str "missing trigger " trigger-name))))
            state (:triggers desired))))

(defn- is-reference-changed?
  "Whether a reference Postgres has differs from its map's in what it is:
   other columns pointed at, another ON DELETE or ON UPDATE, another match.
   When it is checked can change in place (ALTER CONSTRAINT)."
  [found fk]
  (not= (select-keys found [:columns :foreign-table :foreign-columns :on-delete :on-update :match])
        (select-keys fk [:columns :foreign-table :foreign-columns :on-delete :on-update :match])))

(defn- plan-dropped-references
  "The references Postgres has that no map says, or that a map says
   otherwise: dropped first, before any unique key they rest on."
  [state table-name existing desired]
  (reduce (fn [state [n found]]
            (let [fk (get-in desired [:foreign-keys n])]
              (cond
                (nil? fk)
                (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "reference " n " is in no map"))
                (is-reference-changed? found fk)
                (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "reference " n " changed"))
                :else state)))
          state (:foreign-keys existing)))

(defn- plan-existing-table [state has-rows? table-name existing desired]
  (let [existing-columns (into {} (map (juxt :name identity)) (:columns existing))
        desired-names    (set (map :name (:columns desired)))]
    (as-> state state
      (reduce #(plan-column %1 table-name has-rows? (get existing-columns (:name %2)) %2) state (:columns desired))
      (reduce (fn [state {:keys [name]}]
                (if (contains? desired-names name)
                  state
                  (add-stop state (str table-name "." name " is in Postgres but in no map: add a change file, "
                                       "{:rename-column [:" table-name " … …]} or {:drop-column [:" table-name " …] :why \"…\"}"))))
              state (:columns existing))
      (if (= (:primary-key existing) (:primary-key desired))
        state
        (add-stop state (str table-name "'s primary key differs from its map; changing it isn't in v1")))
      (plan-required state table-name existing desired)
      (plan-uniques state table-name existing desired)
      (plan-gates state table-name existing desired)
      (plan-triggers state table-name existing desired)
      ;; references: NOT VALID now, VALIDATE in the slow part; one whose
      ;; target's unique key is still to be built, after it, in the slow part
      ;; (one in no map, or changed, was dropped first: plan-dropped-references)
      (reduce (fn [state [n fk]]
                (let [found (get-in existing [:foreign-keys n])]
                  (cond
                    (or (nil? found) (is-reference-changed? found fk))
                    (if (does-reference-wait-for-unique-key? (:catalog state) fk)
                      (add-waiting-reference state table-name n fk)
                      (-> state
                          (add-instant (str (write-foreign-key table-name n fk) " NOT VALID") (str "missing reference " n))
                          (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows"))))
                    :else
                    (cond-> state
                      (not= (:checked-at fk) (:checked-at found))
                      (add-instant (str "ALTER TABLE " table-name " ALTER CONSTRAINT " n " " (checked-at-sql (:checked-at fk)))
                                   (str "reference " n " is checked " (if (= :commit (:checked-at fk)) "at commit" "after each statement")))
                      (not (:validated? found))
                      (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows"))))))
              state (:foreign-keys desired))
      ;; checks: compared as Postgres prints them; NOT VALID now, VALIDATE in the slow part
      (reduce (fn [state [n check]]
                (let [found (get-in existing [:checks n])]
                  (cond
                    (nil? found)
                    (-> state
                        (add-instant (str (write-check table-name n check) " NOT VALID") (str "missing check " n))
                        (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows")))
                    (not= (:condition check) (:condition found))
                    (-> state
                        (add-instant (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "check " n " changed"))
                        (add-instant (str (write-check table-name n check) " NOT VALID") (str "check " n " changed"))
                        (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows")))
                    (not (:validated? found))
                    (add-slow state (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows"))
                    :else state)))
              state (:checks desired))
      (reduce (fn [state [n _]]
                (if (contains? (:checks desired) n)
                  state
                  (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "check " n " is in no map"))))
              state (:checks existing))
      ;; indexes: compared as Postgres prints them; built and dropped CONCURRENTLY, in the slow part
      (reduce (fn [state [n index]]
                (let [found (get-in existing [:indexes n])]
                  (cond
                    (nil? found)
                    (add-slow state (write-index table-name n index true) (str "missing index " n))
                    (not (:valid? found))
                    (-> state
                        (add-slow (str "DROP INDEX CONCURRENTLY IF EXISTS " (names/qualify-index-name table-name n)) (str "index " n " was left invalid"))
                        (add-slow (write-index table-name n index true) (str "rebuild index " n)))
                    (not= [(:unique? index) (:body index)] [(:unique? found) (:body found)])
                    (-> state
                        (add-slow (str "DROP INDEX CONCURRENTLY IF EXISTS " (names/qualify-index-name table-name n)) (str "index " n " differs from its map"))
                        (add-slow (write-index table-name n index true) (str "rebuild index " n)))
                    :else state)))
              state (:indexes desired))
      (reduce (fn [state [n _]]
                (if (contains? (:indexes desired) n)
                  state
                  (add-slow state (str "DROP INDEX CONCURRENTLY IF EXISTS " (names/qualify-index-name table-name n)) (str "index " n " is in no map"))))
              state (:indexes existing)))))

(defn- find-references-no-map-names
  "[table-name name], as Postgres has them now, of the references on the
   maps' tables that no map names, nor adopts, once the pending changes
   have run on paper: they go first, before the changes, so a table one
   of them points at can be dropped."
  [catalog desired pending]
  (let [tagged   (into {} (for [[table-name table] catalog]
                            [table-name (update table :foreign-keys
                                                #(into {} (for [[n fk] %] [n (assoc fk :origin [table-name n])])))]))
        on-paper (:catalog (reduce apply-change {:catalog tagged :desired desired :instant [] :slow [] :stops []} pending))]
    (for [[table-name {:keys [foreign-keys]}] on-paper
          :when (contains? desired table-name)
          :let [wanted  (get-in desired [table-name :foreign-keys])
                adopted (set (map first (find-adoptions foreign-keys wanted reference-shape)))]
          [n fk] foreign-keys
          :when (not (or (contains? wanted n) (contains? adopted n)))]
      (:origin fk))))

(defn- drop-references-first
  "The references no map names dropped before the changes run, on paper
   and in the instant part's order."
  [state references]
  (reduce (fn [state [table-name n]]
            (-> state
                (update-in [:catalog table-name :foreign-keys] dissoc n)
                (add-instant (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "reference " n " is in no map"))))
          state
          references))

;; -----------------------------------------------------------------------------
;; Extensions, functions and views

(defn- quote-name
  "A folder.name, or a name, each part in double quotes."
  [qualified]
  (str/join "." (map quotes/quote-name (str/split qualified #"\."))))

(defn- find-views-to-drop
  "The views Postgres has that go before the changes and tables: in no
   file; declared but printed otherwise (or not printable before the plan
   runs); reading a column or a table a pending change drops; and every
   view that reads one of those."
  [existing-views declared-views printed-views pending]
  (let [declared-names  (set (map :view-name declared-views))
        dropped-tables  (set (for [{:keys [drop-table]} pending :when drop-table]
                               (names/turn-table-key-into-table-name drop-table)))
        dropped-columns (set (for [{[table-key column-key] :drop-column} pending :when table-key]
                               [(names/turn-table-key-into-table-name table-key) (names/turn-key-into-column-name column-key)]))
        changed?        (fn [view-name]
                          (not= (get printed-views view-name)
                                (select-keys (get existing-views view-name) [:definition :options])))
        first-drops     (set (for [[view-name {:keys [reads]}] existing-views
                                   :when (or (not (contains? declared-names view-name))
                                             (changed? view-name)
                                             (some (fn [[table column]] (or (dropped-tables table) (dropped-columns [table column])))
                                                   reads))]
                               view-name))]
    (loop [drops first-drops]
      (let [more (into drops (for [[view-name {:keys [views-read]}] existing-views
                                   :when (some drops views-read)]
                               view-name))]
        (if (= more drops) drops (recur more))))))

(defn- plan-objects-before
  "Before the changes and tables, in the instant part: the views that go
   (one DROP VIEW, which Postgres orders), the folders and extensions
   missing, and the declared functions Postgres hasn't, or has printed
   otherwise, made (CREATE OR REPLACE, bodies unchecked, so a function may
   read a column this plan adds). An extension in no list is a stop: its
   types may hold data. The views to make after the tables are kept for
   plan-objects-after."
  [state {:keys [objects declared printed pending]}]
  (let [{:keys [extensions functions views]} declared
        drops   (find-views-to-drop (:views objects) views (:views printed) pending)
        to-make (filterv #(or (not (contains? (:views objects) (:view-name %))) (contains? drops (:view-name %))) views)
        folders (distinct (remove (:folders objects)
                                  (concat (map :folder functions)
                                          (map (comp names/find-folder-of-table :view-name) views))))
        to-run  (filter (fn [{:keys [key]}]
                          (let [{:keys [identity definition]} (get-in printed [:functions key])]
                            (or (nil? identity) (not= definition (get-in objects [:functions identity])))))
                        functions)]
    (as-> state state
      (if (seq drops)
        (add-instant state (str "DROP VIEW IF EXISTS " (str/join ", " (map quote-name (sort drops))))
                     "views in no file, changed, or over what changes: the declared ones are made again after the tables")
        state)
      (reduce #(add-instant %1 (str "CREATE SCHEMA IF NOT EXISTS " %2) (str "folder " %2 " for its functions and views")) state folders)
      (reduce (fn [state extension]
                (if (contains? (:extensions objects) extension)
                  state
                  (add-instant state (str "CREATE EXTENSION IF NOT EXISTS \"" extension "\"") (str "missing extension " extension))))
              state extensions)
      (reduce (fn [state extension]
                (add-stop state (str "extension " extension " is in the database but in no list: add it to :extensions, "
                                     "or drop it by hand (its types may hold data)")))
              state
              (sort (remove (into #{"plpgsql"} extensions) (:extensions objects))))
      (if (seq to-run)
        (add-instant state "SET LOCAL check_function_bodies = off" "functions are made before the tables and columns they read")
        state)
      (reduce (fn [state {:keys [from folder name sql]}]
                (add-instant state sql (str "function " folder "." name " " from)))
              state to-run)
      (assoc state :views-to-make to-make))))

(defn- plan-objects-after
  "After the tables, in the instant part: the functions Postgres has that
   nothing declared makes dropped (by folder, name and arguments), and the views to
   make made, in their list's order."
  [state {:keys [objects declared printed]}]
  (let [identities (set (keep #(get-in printed [:functions (:key %) :identity]) (:functions declared)))]
    (as-> state state
      (reduce (fn [state [[folder function-name arguments :as identity] _]]
                (if (contains? identities identity)
                  state
                  (add-instant state (str "DROP FUNCTION " (quote-name folder) "." (quote-name function-name) "(" arguments ")")
                               (str "function " folder "." function-name "(" arguments ") is in no file and no made function"))))
              state (sort-by key (:functions objects)))
      (reduce (fn [state {:keys [view-name file sql]}]
                (add-instant state sql (str "view " view-name " from " file)))
              state (:views-to-make state)))))

(defn list-folders-of-pending-changes
  "The folders of the tables pending changes drop or rename away: read
   with the maps' folders, so a table in a folder no map names any more
   can still be dropped."
  [changes changes-done]
  (set (for [{:keys [id drop-table rename-table]} changes
             :when (not (contains? changes-done id))
             table-key (cond drop-table [drop-table] rename-table [(first rename-table)])]
         (names/find-folder-of-table (names/turn-table-key-into-table-name table-key)))))

(defn is-fresh?
  "Whether the database is fresh: no change has run and Postgres has none
   of the maps' tables. A fresh database takes the maps as they are."
  [catalog desired changes-done]
  (and (empty? changes-done) (not-any? #(contains? catalog %) (keys desired))))

(defn list-tables-to-print
  "The maps' tables the plan compares with Postgres's, whose pieces the
   probe prints: those Postgres has, and those a pending change renames a
   table into."
  [catalog desired changes changes-done]
  (let [renamed-into (set (for [{:keys [id rename-table]} changes
                                :when (and rename-table (not (contains? changes-done id)))]
                            (names/turn-table-key-into-table-name (second rename-table))))]
    (if (is-fresh? catalog desired changes-done)
      {}
      (into {} (filter #(or (contains? catalog (key %)) (contains? renamed-into (key %)))) desired))))

(defn- stop-type-changes-under-views
  "A type change of a column a declared view reads is a stop: Postgres
   refuses the ALTER while the view stands, and the slow part can't take
   the view away while the app runs. A view no file makes goes in the
   instant part, before."
  [state objects declared]
  (reduce (fn [state [table-name column-name id]]
            (if-let [views (seq (sort (for [[view-name {:keys [reads]}] (:views objects)
                                            :when (contains? reads [table-name column-name])
                                            :when (some #(= view-name (:view-name %)) (:views declared))]
                                        view-name)))]
              (add-stop state (str "change " id ": " table-name "." column-name " is read by the views " (str/join ", " views)
                                   ", which Postgres keeps from changing its type: take them out of :views for this deploy"))
              state))
          state
          (:type-changes state)))

(defn make-plan
  "The plan from what Postgres has (catalog, its tables; objects, its
   folders, extensions, functions and views), what the maps and files say
   (desired, the tables; declared, the rest), how Postgres prints the
   maps' and files' pieces (printed, the probe's: :tables for those
   list-tables-to-print names, :functions, :views), the change files and
   which have run, and has-rows? (a table name -> whether it has rows,
   asked only when it matters). In the instant part: the views that go,
   the extensions and functions, the references no map names, the
   changes, the tables, the functions nothing declared makes dropped, the views
   made."
  [{:keys [catalog objects desired declared printed changes changes-done has-rows?]}]
  (let [fresh?   (is-fresh? catalog desired changes-done)
        pending  (remove #(contains? changes-done (:id %)) changes)
        start    (plan-objects-before {:catalog catalog :desired desired :instant [] :slow [] :stops [] :waiting-references []
                                       :changes-to-mark (mapv :id pending)}
                                      {:objects objects :declared declared :printed printed :pending (if fresh? [] pending)})
        state    (if fresh?
                   start
                   (reduce apply-change
                           (drop-references-first start (find-references-no-map-names catalog desired pending))
                           pending))
        desired  (into {} (map (fn [[table-name table]]
                                 [table-name (if (contains? (:catalog state) table-name)
                                               (add-printed table (get-in printed [:tables table-name]))
                                               table)]))
                       desired)
        state    (reduce (fn [state table-name]
                           (-> state
                               (adopt-pieces table-name (get desired table-name))
                               (promote-unique-indexes table-name (get desired table-name))))
                         state
                         (filter #(contains? (:catalog state) %) (keys desired)))
        catalog  (:catalog state)
        new      (remove #(contains? catalog %) (keys desired))
        folders  (->> new (map names/find-folder-of-table) (remove #{"public"}) distinct)
        state    (as-> state state
                   (reduce (fn [state table-name]
                             (plan-dropped-references state table-name (get catalog table-name) (get desired table-name)))
                           state
                           (filter #(contains? catalog %) (keys desired)))
                   (reduce #(add-instant %1 (str "CREATE SCHEMA IF NOT EXISTS " %2) (str "folder " %2 " for its tables")) state folders)
                   (reduce #(plan-new-table %1 %2 (get desired %2)) state new)
                   (reduce #(plan-foreign-keys-of-new-table %1 %2 (get desired %2)) state new)
                   (reduce #(plan-gates %1 %2 {:gated? false :policies {}} (get desired %2)) state new)
                   (reduce #(plan-triggers %1 %2 {:triggers {}} (get desired %2)) state new)
                   (reduce (fn [state table-name]
                             (plan-existing-table state has-rows? table-name (get catalog table-name) (get desired table-name)))
                           state
                           (filter #(contains? catalog %) (keys desired)))
                   (plan-waiting-references state)
                   (reduce (fn [state table-name]
                             (add-stop state (str "table " table-name " is in Postgres but in no map: "
                                                  "add a change file, {:rename-table [:" table-name " …]} "
                                                  "or {:drop-table :" table-name " :why \"…\"}")))
                           state
                           (remove #(contains? desired %) (keys catalog)))
                   (plan-objects-after state {:objects objects :declared declared :printed printed})
                   (stop-type-changes-under-views state objects declared)
                   (update state :changes-to-mark (partial filterv #(not (contains? (:marked-in-slow-part state) %)))))]
    (select-keys state [:instant :slow :stops :changes-to-mark])))
