(ns groundwork-postgres.tables-test
  "The Postgres tests run when POSTGRES_TEST_PORT is set (on localhost,
   user from POSTGRES_TEST_USER, password from POSTGRES_TEST_PASSWORD, no TLS); they say they
   were skipped otherwise. Each starts from an empty database."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(def ^:private shops
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

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id         :uuid         {:primary-key? true :default :random-uuid}]
               [:order/shop-id    :uuid         {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
               [:order/total      :decimal      {:default 0}]
               [:order/status     [:enum "draft" "done"] {:default "draft"}]
               [:order/note       :string       {:optional? true}]
               [:order/paid       :boolean      {:default false}]
               [:order/created-at :time/instant {:default :now}]]
   :indexes   [{:columns [[:order/shop-id :ascending :nulls-last] [:order/created-at :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change nil})

(def ^:private orders-with-promised
  (update orders :columns conj [:order/promised-at :time/instant {:optional? true}]))

(def ^:private orders-with-customer-note
  (assoc-in orders-with-promised [:columns 4] [:order/customer-note :string {:optional? true}]))

(defn- run-sql! [pool text]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
      (f pool)
      (finally (postgres/stop-pool! pool)))))

(defn- migrate-and-wait! [pool tables changes]
  (let [plan (postgres/migrate! pool (test-settings/declare-tables tables) changes test-settings/migrate-options)]
    @(:slow-part plan)
    plan))

(defn- plan-of [pool tables changes]
  (select-keys (postgres/make-plan-from-tables pool (test-settings/declare-tables tables) changes) [:instant :slow :stops :changes-to-mark]))

(def ^:private nothing-to-do {:instant [] :slow [] :stops [] :changes-to-mark []})

(def ^:private invoices
  {:table     :billing/invoices
   :columns   [[:invoice/id     :uuid   {:primary-key? true :default :random-uuid}]
               [:invoice/number :string {:unique? true}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change nil})

(deftest a-folder-and-a-unique-key
  (if-not port
    (println "groundwork-postgres.tables-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool "DROP SCHEMA IF EXISTS billing CASCADE")
        (testing "a namespaced table key: its own folder, made with the table"
          (migrate-and-wait! pool [shops invoices] [])
          (is (= nothing-to-do (plan-of pool [shops invoices] []))))
        (testing "a unique key added to a table with rows: built in the slow part"
          (run-sql! pool "INSERT INTO shops (shop__name) VALUES ('Hotcoats')")
          (let [unique-shops (assoc-in shops [:columns 1] [:shop/name :string {:unique? true}])
                plan         (plan-of pool [unique-shops invoices] [])]
            (is (= [] (:instant plan)))
            (is (= 2 (count (:slow plan))))
            (migrate-and-wait! pool [unique-shops invoices] [])
            (is (= nothing-to-do (plan-of pool [unique-shops invoices] [])))))))))

(deftest the-apps-own-role-can-check-the-database
  (if-not port
    (println "groundwork-postgres.tables-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_postgres_app') THEN CREATE ROLE groundwork_postgres_app; END IF; END $$")
        (migrate-and-wait! pool [shops orders] [{:id "c1" :rename-column [:orders :order/old :order/note]}])
        (postgres/grant-tables-to-role! pool [shops orders] "groundwork_postgres_app")
        (is (postgres/run-in-transaction! pool {:read-only? false :log test-settings/log}
              (fn [transaction]
                (run-sql! transaction "SET LOCAL ROLE groundwork_postgres_app")
                (postgres/does-database-match-tables? transaction (test-settings/declare-tables [shops orders]) [{:id "c1" :rename-column [:orders :order/old :order/note]}])))
            "as the app's role, which owns nothing: the changes done are read, the database matches")))))

(deftest the-row-shape-comes-from-the-map
  (is (= [:map [:shop/id :uuid] [:shop/name :string]] (postgres/turn-table-into-row-shape shops)))
  (is (= [:order/note {:optional true} [:maybe :string]]
         (nth (postgres/turn-table-into-row-shape orders) 5))))

(deftest migrating-against-postgres
  (if-not port
    (println "groundwork-postgres.tables-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (testing "a fresh database gets the tables, and then nothing differs"
          (migrate-and-wait! pool [shops orders] [{:id "c1" :rename-column [:orders :order/old :order/note]}])
          (is (= nothing-to-do (plan-of pool [shops orders] [{:id "c1" :rename-column [:orders :order/old :order/note]}])))
          (is (postgres/does-database-match-tables? pool (test-settings/declare-tables [shops orders]) [])))

        (run-sql! pool "INSERT INTO shops (shop__name) VALUES ('Hotcoats')")
        (run-sql! pool "INSERT INTO orders (order__shop_id) SELECT shop__id FROM shops")

        (testing "an optional column: one instant statement"
          (let [plan (plan-of pool [shops orders-with-promised] [])]
            (is (= ["ALTER TABLE orders ADD COLUMN order__promised_at timestamp with time zone"]
                   (map :sql (:instant plan))))
            (migrate-and-wait! pool [shops orders-with-promised] [])
            (is (= nothing-to-do (plan-of pool [shops orders-with-promised] [])))))

        (testing "a required column without a default on a table with rows: a stop"
          (let [plan (plan-of pool [shops (update orders-with-promised :columns conj [:order/priority :int])] [])]
            (is (re-find #"order__priority is required" (first (:stops plan))))))

        (testing "a column in Postgres but in no map: a stop, until a change file says rename"
          (let [rename [{:id "c2" :rename-column [:orders :order/note :order/customer-note]}]]
            (is (some #(re-find #"order__note is in Postgres but in no map" %)
                      (:stops (plan-of pool [shops orders-with-customer-note] []))))
            (migrate-and-wait! pool [shops orders-with-customer-note] rename)
            (is (= nothing-to-do (plan-of pool [shops orders-with-customer-note] rename)))))

        (testing "a new index: in the slow part, built CONCURRENTLY"
          (let [indexed (update orders-with-customer-note :indexes conj {:columns [[:order/status :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil})
                plan    (plan-of pool [shops indexed] [])]
            (is (= [] (:instant plan)))
            (is (re-find #"CREATE INDEX CONCURRENTLY" (:sql (first (:slow plan)))))))

        (testing "a column that became required: a NOT VALID check now, the rest in the background"
          (let [required (assoc-in orders [:columns 4] [:order/note :string])
                _        (run-sql! pool "DROP SCHEMA public CASCADE; CREATE SCHEMA public; DROP SCHEMA groundwork_postgres CASCADE")
                _        (migrate-and-wait! pool [shops orders] [])
                _        (run-sql! pool "INSERT INTO shops (shop__name) VALUES ('Hotcoats')")
                _        (run-sql! pool "INSERT INTO orders (order__shop_id, order__note) SELECT shop__id, 'x' FROM shops")
                plan     (plan-of pool [shops required] [])]
            (is (re-find #"NOT VALID" (:sql (first (:instant plan)))))
            (is (= 3 (count (:slow plan))))
            (migrate-and-wait! pool [shops required] [])
            (is (= nothing-to-do (plan-of pool [shops required] [])))))))))
