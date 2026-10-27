(ns groundwork-postgres.tables.types
  "A column's type in groundwork-shaped-json's words as Postgres's type, and a default as
   SQL. How Postgres prints a default back is asked of it
   (groundwork-postgres.tables.probe), not foretold."
  (:require
   [groundwork-postgres.tables.quotes :as quotes]))

(def ^:private postgres-types
  ;; as format_type names them, which is also valid in CREATE TABLE
  {:uuid            "uuid"
   :int             "bigint"
   :decimal         "numeric"
   :double          "double precision"
   :string          "text"
   :boolean         "boolean"
   :time/instant    "timestamp with time zone"
   :time/local-date "date"})

(def ^:private int-ranges
  ;; an :int's :min and :max as a Postgres type holds them; :int alone is
  ;; bigint, which holds a JVM long
  {{:min -2147483648 :max 2147483647} "integer"
   {:min -32768 :max 32767}           "smallint"})

(defn- throw-unknown-type [type why]
  (throw (ex-info (str "No Postgres type for the column type " (pr-str type) ": " why)
                  {:type :groundwork-postgres/unknown-type :column-type type})))

(defn- split-type
  "[word properties children] of a type in malli's words: :int is [:int
   {} []], [:string {:max 10}] is [:string {:max 10} []]."
  [type]
  (if (vector? type)
    (let [[word & more] type]
      (if (map? (first more))
        [word (first more) (vec (rest more))]
        [word {} (vec more)]))
    [type {} []]))

(defn find-enum-values
  "The values of [:enum \"a\" \"b\"] (or [:enum {:max 10} \"a\" \"b\"]), or
   nil for another type."
  [type]
  (let [[word _ children] (split-type type)]
    (when (= :enum word) children)))

(defn turn-type-into-postgres-type
  "The Postgres type of a column type, as format_type names it, from a
   closed list of malli's words; the limit the type states is Postgres's:

     :string [:string {:max 10}]          text, character varying(10)
     [:enum \"a\" …] [:enum {:max 10} \"a\" …] text, character varying(10), with a check
     :int [:int {:min -2147483648 :max 2147483647}] [:int {:min -32768 :max 32767}]
                                          bigint, integer, smallint
     :decimal [:decimal {:precision 10 :scale 2}]   numeric, numeric(10,2)
     :uuid :double :boolean :time/instant :time/local-date
     [:vector t], t one of the above but an enum: its array, t[]
     [:json shape]                        jsonb

   Anything else throws :groundwork-postgres/unknown-type."
  [type]
  (let [[word properties children] (split-type type)]
    (case word
      :string  (cond (empty? properties) "text"
                     (and (= [:max] (keys properties)) (pos-int? (:max properties)))
                     (str "character varying(" (:max properties) ")")
                     :else (throw-unknown-type type "a :string says only {:max n}, its length"))
      :enum    (let [{:keys [max]} properties]
                 (cond
                   (or (empty? children) (not-every? string? children))
                   (throw-unknown-type type "an :enum's values are text")
                   (empty? properties) "text"
                   (not (and (= [:max] (keys properties)) (pos-int? max)))
                   (throw-unknown-type type "an :enum says only {:max n}, its length")
                   (some #(< max (count %)) children)
                   (throw-unknown-type type (str "a value is longer than its {:max " max "}"))
                   :else (str "character varying(" max ")")))
      :int     (cond (empty? properties) "bigint"
                     (contains? int-ranges properties) (get int-ranges properties)
                     :else (throw-unknown-type type "an :int's {:min :max} are integer's or smallint's, or none"))
      :decimal (let [{:keys [precision scale]} properties]
                 (cond (empty? properties) "numeric"
                       (and (= #{:precision :scale} (set (keys properties))) (pos-int? precision) (nat-int? scale) (<= scale precision))
                       (str "numeric(" precision "," scale ")")
                       :else (throw-unknown-type type "a :decimal says {:precision p :scale s}, 0 <= s <= p, or nothing")))
      :vector  (let [[item] children]
                 (cond
                   (not (and (empty? properties) (= 1 (count children))))
                   (throw-unknown-type type "a :vector says one item type and no properties")
                   (or (find-enum-values item) (#{:vector :json} (first (split-type item))))
                   (throw-unknown-type type "an array's items are a plain type, not an :enum, a :vector or :json")
                   :else (str (turn-type-into-postgres-type item) "[]")))
      :json    (if (and (empty? properties) (= 1 (count children)))
                 "jsonb"
                 (throw-unknown-type type "[:json shape], one child and no properties"))
      (if (and (empty? properties) (empty? children) (contains? postgres-types word))
        (get postgres-types word)
        (throw-unknown-type type "not one of groundwork-postgres's types")))))

(defn turn-default-into-sql
  "A column's :default as SQL, for a column of this Postgres type:
   :random-uuid, :time-ordered-uuid, :now, :today, a number, true/false,
   text, or [] or {} (empty JSON; [] an empty array on an array column).
   [] or {} on a column they can't fill throws :groundwork-postgres/bad-default."
  [default postgres-type]
  (let [array? (.endsWith ^String postgres-type "[]")
        json?  (= "jsonb" postgres-type)]
    (cond
      (= :random-uuid default)       "gen_random_uuid()"
      (= :time-ordered-uuid default) "uuidv7()"
      (= :now default)               "now()"
      (= :today default)             "CURRENT_DATE"
      (boolean? default)             (str default)
      (number? default)              (if (neg? default) (str "(" default ")") (str default))
      (string? default)              (quotes/quote-text default)
      (and (= [] default) json?)     "'[]'"
      (and (= [] default) array?)    "'{}'"
      (and (= {} default) json?)     "'{}'"
      :else (throw (ex-info (str "Not a default groundwork-postgres can write for " postgres-type ": " (pr-str default))
                            {:type :groundwork-postgres/bad-default :default default})))))

(defn is-default-volatile?
  "Whether Postgres computes the default anew for each row, which makes
   adding the column to a table with rows rewrite the table."
  [default]
  (contains? #{:random-uuid :time-ordered-uuid} default))
