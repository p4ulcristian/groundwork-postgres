(ns groundwork-postgres.queries.params
  "A .sql file's text with param('name') params turned into what JDBC sends:
   the text with ? in their place, and the param names in order; and the
   text with $1, $2… in their place, for asking Postgres to plan it.

     SELECT order__id FROM orders WHERE order__shop_id = param('order/shop-id') LIMIT param('limit')
     => {:text-with-question-marks \"SELECT order__id FROM orders WHERE order__shop_id = ? LIMIT ?\"
         :text-with-dollar-numbers \"SELECT order__id FROM orders WHERE order__shop_id = $1 LIMIT $2\"
         :param-names              [:order/shop-id :limit]}

   The file stays SQL Postgres's parser accepts as it is. A field of the
   data is namespaced ('order/shop-id'); a control of the query is plain
   ('limit'). The params map's keys are the names as keywords.

   - Written one way: param('name'), in lowercase, the ( right after it;
     whitespace inside the parens is fine. PARAM('limit'), param ('limit')
     and a name that isn't one (param('Shop_Id')) throw. A param right
     after a letter, digit, _, $ or . is part of another name (my_param(…),
     app.param(…)), and param(…) with no quoted text in it (the header
     of a function of that name, param(name text)) stays as it is.
   - A name used twice is two ?s, with its value bound twice.
   - A ? that isn't a param (the jsonb operators ?, ?|, ?&) becomes ??,
     which JDBC sends as ?.
   - Left untouched: string literals (also E'..'), quoted identifiers,
     -- and /* */ comments (nested), dollar-quoted bodies, ::casts, and a
     colon right after a letter, digit or _ (array slices, arr[1:n]).
   - Throws: a :name param (the old way, a colon before a name, not after
     a letter, digit or _ and not ::), and a positional $1: params are
     param('name').")

(def ^:private param-name
  #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:/[a-z][a-z0-9]*(?:-[a-z0-9]+)*)?")
(def ^:private dollar-tag #"^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$")
;; a call of param with a quoted text in it, as Postgres reads one: any case, spaces before and in the (
(def ^:private param-call-start #"(?i)^param\s*\(\s*'")
;; the one way it is written
(def ^:private param-call #"^param\(\s*'([^']*)'\s*\)")

(defn- ident-char? [c]
  (and c (or (Character/isLetterOrDigit (char c)) (= c \_) (= c \$))))

(defn- syntax-error [message text i]
  (throw (ex-info message {:type :groundwork-postgres/syntax :position i :near (subs text i (min (count text) (+ i 30)))})))

(defn- quoted-end
  "Index just past the quoted text opening at i. A doubled quote stays
   inside; with backslash? a backslash escapes the next character."
  [^String text i backslash?]
  (let [n (count text)
        q (.charAt text i)]
    (loop [j (inc i)]
      (cond
        (>= j n)                                 n
        (and backslash? (= \\ (.charAt text j))) (recur (+ j 2))
        (not= q (.charAt text j))                (recur (inc j))
        (and (< (inc j) n) (= q (.charAt text (inc j)))) (recur (+ j 2))
        :else                                    (inc j)))))

(defn- block-comment-end [^String text i]
  (let [n (count text)]
    (loop [j (+ i 2), depth 1]
      (cond
        (>= j n)                    n
        (.startsWith text "/*" (int j)) (recur (+ j 2) (inc depth))
        (.startsWith text "*/" (int j)) (if (= 1 depth) (+ j 2) (recur (+ j 2) (dec depth)))
        :else                       (recur (inc j) depth)))))

(defn- line-end [^String text i]
  (let [j (.indexOf text "\n" (int i))]
    (if (neg? j) (count text) j)))

(defn- escape-string? [^String text i]
  (and (pos? i)
       (#{\E \e} (.charAt text (dec i)))
       (not (and (> i 1) (ident-char? (.charAt text (- i 2)))))))

(defn turn-sql-text-into-statement
  "SQL text -> {:text-with-question-marks … :text-with-dollar-numbers …
   :param-names [one per ?, in order]}."
  [^String text]
  (let [n        (count text)
        out      (StringBuilder.)
        numbered (StringBuilder.)
        at       (fn [i] (when (< i n) (.charAt text i)))]
    (loop [i 0, params []]
      (if (>= i n)
        {:text-with-question-marks (str out) :text-with-dollar-numbers (str numbered) :param-names params}
        (let [c    (.charAt text i)
              nxt  (at (inc i))
              prev (when (pos? i) (.charAt text (dec i)))
              copy (fn [j] (.append out text (int i) (int j)) (.append numbered text (int i) (int j)) j)]
          (cond
            (= c \')
            (recur (copy (quoted-end text i (escape-string? text i))) params)

            (= c \")
            (recur (copy (quoted-end text i false)) params)

            (and (= c \-) (= nxt \-))
            (recur (copy (line-end text i)) params)

            (and (= c \/) (= nxt \*))
            (recur (copy (block-comment-end text i)) params)

            (and (= c \$) (not (ident-char? prev)))
            (if-let [tag (re-find dollar-tag (subs text i (min n (+ i 64))))]
              (let [close (.indexOf text ^String tag (int (+ i (count tag))))]
                (recur (copy (if (neg? close) n (+ close (count tag)))) params))
              (if (and nxt (Character/isDigit (char nxt)))
                (syntax-error "Positional params ($1) aren't allowed, write param('name')" text i)
                (recur (copy (inc i)) params)))

            (= c \?)
            (do (.append out "??")
                (.append numbered "?")
                (recur (inc i) params))

            (and (= c \:) (= nxt \:))
            (recur (copy (+ i 2)) params)

            (and (= c \:) (not (ident-char? prev)))
            (if-let [pname (re-find param-name (subs text (inc i)))]
              (syntax-error (str "A param is written param('" pname "'), not :" pname) text i)
              (recur (copy (inc i)) params))

            (and (#{\p \P} c) (not (ident-char? prev)) (not= prev \.)
                 (re-find param-call-start (subs text i (min n (+ i 64)))))
            (let [[call pname] (re-find param-call (subs text i (min n (+ i 300))))]
              (if (and call (re-matches param-name pname))
                (do (.append out "?")
                    (.append numbered (str "$" (inc (count params))))
                    (recur (+ i (count call)) (conj params (keyword pname))))
                (syntax-error "A param is written param('name'), the name like limit or order/shop-id" text i)))

            :else
            (recur (copy (inc i)) params)))))))

(defn list-param-values
  "The values for a statement, one per ?, from a params map; nil is sent as
   NULL. A param missing from the map throws, and so does a key of the map
   the query has no param for, each naming the file."
  [{:keys [param-names]} params path]
  (let [values (mapv (fn [k]
                       (if (contains? params k)
                         (get params k)
                         (throw (ex-info (str "Missing param " k " for " path)
                                         {:type :groundwork-postgres/missing-param :param k :file path}))))
                     param-names)]
    (when-let [unknown (first (remove (set param-names) (keys params)))]
      (throw (ex-info (str "Unknown param " (pr-str unknown) " for " path ": the query has no param of that name")
                      {:type :groundwork-postgres/unknown-param :param unknown :file path})))
    values))
