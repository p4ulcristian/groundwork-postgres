(ns groundwork-postgres.tables.triggers
  "Change triggers: a table map's :on-change names a function Postgres
   calls after every statement that inserts, updates or deletes its rows:

     {:table :orders … :on-change {:function \"app.tell_change\" :arguments [\"order__id\" \"order__shop_id\"]}}

   Three triggers, one per kind of statement, each FOR EACH STATEMENT, with
   the changed rows as tables the function reads: groundwork_postgres_new_rows (after
   an insert or update) and groundwork_postgres_old_rows (after an update or delete).
   A trigger's name ends in a hash of its SQL, so a changed :on-change is a
   new name: the old trigger is dropped and the new one made.

   A table map's :triggers are triggers declared by a library on top (or
   the app), each its own: a function, before or after which events, for
   each row or statement, when a condition holds or always
   (turn-declared-triggers-into-triggers); groundwork-access's
   fill of a record's carried columns from its parent is one."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.tables.names :as names]
   [groundwork-postgres.tables.quotes :as quotes]))

(def ^:private statements
  {"insert" "AFTER INSERT ON %s REFERENCING NEW TABLE AS groundwork_postgres_new_rows"
   "update" "AFTER UPDATE ON %s REFERENCING OLD TABLE AS groundwork_postgres_old_rows NEW TABLE AS groundwork_postgres_new_rows"
   "delete" "AFTER DELETE ON %s REFERENCING OLD TABLE AS groundwork_postgres_old_rows"})

(defn turn-on-change-into-triggers
  "{trigger-name sql} for a table's :on-change."
  [table-name {:keys [function arguments]}]
  (into {}
        (for [[operation when-text] statements
              :let [body (str (format when-text table-name) " FOR EACH STATEMENT EXECUTE FUNCTION "
                              function "(" (str/join ", " (map quotes/quote-text arguments)) ")")
                    trigger-name (names/make-identifier (names/find-table-part table-name) "on" operation (format "%08x" (bit-and (hash body) 0xffffffff)))]]
          [trigger-name (str "CREATE TRIGGER " trigger-name " " body)])))

(defn- write-events
  "INSERT OR UPDATE OF a, b OR DELETE, in that order."
  [events update-of]
  (str/join " OR " (for [event [:insert :update :delete] :when (contains? events event)]
                     (if (and (= :update event) (seq update-of))
                       (str "UPDATE OF " (str/join ", " (map names/turn-key-into-column-name update-of)))
                       (str/upper-case (name event))))))

(defn turn-declared-triggers-into-triggers
  "{trigger-name sql} for a table's :triggers, each named by a hash of
   its SQL, so a changed one is a new name: the old dropped, the new made."
  [table-name declared]
  (into {}
        (for [{:keys [function arguments timing events update-of for-each] condition :when} declared
              :let [body (str (str/upper-case (name timing)) " " (write-events events update-of)
                              " ON " table-name " FOR EACH " (str/upper-case (name for-each))
                              (when condition (str " WHEN (" condition ")"))
                              " EXECUTE FUNCTION " function "(" (str/join ", " (map quotes/quote-text arguments)) ")")
                    trigger-name (names/make-trigger-name table-name body)]]
          [trigger-name (str "CREATE TRIGGER " trigger-name " " body)])))
