(ns groundwork-postgres.params-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.queries.params :as params]))

(defn- syntax-message [text]
  (try (params/turn-sql-text-into-statement text)
       nil
       (catch clojure.lang.ExceptionInfo e
         (when (= :groundwork-postgres/syntax (:type (ex-data e))) (ex-message e)))))

(deftest a-param-becomes-a-question-mark
  (is (= {:text-with-question-marks "SELECT order__id FROM orders WHERE order__shop_id = ? AND x::int > ? AND note = 'param(''not/a-param'')'"
          :text-with-dollar-numbers "SELECT order__id FROM orders WHERE order__shop_id = $1 AND x::int > $2 AND note = 'param(''not/a-param'')'"
          :param-names              [:order/shop-id :limit]}
         (params/turn-sql-text-into-statement
          "SELECT order__id FROM orders WHERE order__shop_id = param('order/shop-id') AND x::int > param( 'limit' ) AND note = 'param(''not/a-param'')'"))))

(deftest what-is-not-a-param-stays
  (testing "a name used twice is two ?s"
    (is (= [:order/id :order/id]
           (:param-names (params/turn-sql-text-into-statement "SELECT param('order/id')::uuid = param('order/id')::uuid AS same")))))
  (testing "comments, quoted identifiers, dollar-quoted bodies, other names ending in param"
    (let [text "SELECT \"param('a')\", my_param('b'), app.param('c'), params -- param('d')\n/* param('e') */ FROM t WHERE $$param('f')$$ <> ''"]
      (is (= {:text-with-question-marks text :text-with-dollar-numbers text :param-names []}
             (params/turn-sql-text-into-statement text)))))
  (testing "a function named param, its header with no quoted name"
    (is (= [] (:param-names (params/turn-sql-text-into-statement "CREATE OR REPLACE FUNCTION param(name text) RETURNS text LANGUAGE sql AS $$ SELECT name $$")))))
  (testing "::casts, array slices and jsonb ? operators"
    (is (= {:text-with-question-marks "SELECT x::int, arr[1:n], doc ?? 'k', doc ??| array['a'] FROM t WHERE y = ?::uuid"
            :text-with-dollar-numbers "SELECT x::int, arr[1:n], doc ? 'k', doc ?| array['a'] FROM t WHERE y = $1::uuid"
            :param-names              [:y]}
           (params/turn-sql-text-into-statement "SELECT x::int, arr[1:n], doc ? 'k', doc ?| array['a'] FROM t WHERE y = param('y')::uuid")))))

(deftest a-param-written-another-way-throws
  (is (= "A param is written param('order/shop-id'), not :order/shop-id"
         (syntax-message "SELECT 1 FROM orders WHERE order__shop_id = :order/shop-id")))
  (is (= "A param is written param('limit'), not :limit" (syntax-message "SELECT 1 LIMIT :limit")))
  (is (re-find #"^Positional params \(\$1\) aren't allowed" (syntax-message "SELECT $1")))
  (doseq [text ["SELECT PARAM('limit')" "SELECT param ('limit')" "SELECT param('Shop_Id')" "SELECT param('')"
                "SELECT param('limit'" "SELECT param('a', 'b')"]]
    (is (= "A param is written param('name'), the name like limit or order/shop-id" (syntax-message text)) text)))

(deftest a-missing-param-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing param :limit for a.sql"
                        (params/list-param-values {:param-names [:limit]} {} "a.sql"))))

(deftest a-param-the-query-has-not-throws
  (let [e (try (params/list-param-values {:param-names [:limit]} {:limit 5 :order/shop-id 7} "a.sql")
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "Unknown param :order/shop-id for a.sql: the query has no param of that name" (ex-message e)))
    (is (= {:type :groundwork-postgres/unknown-param :param :order/shop-id :file "a.sql"} (ex-data e))))
  (is (= [5 5] (params/list-param-values {:param-names [:limit :limit]} {:limit 5} "a.sql"))))
