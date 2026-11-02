(ns groundwork-postgres.indexes-test
  "An index is a map with every key written out; unique plain columns are
   :uniques. The Postgres tests run when POSTGRES_TEST_PORT is set; each
   starts from an empty database."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.adopt-test :refer [find-oids]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(defn- run-sql! [pool text]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool (str "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE;"
                          "CREATE EXTENSION IF NOT EXISTS pg_trgm"))
      (f pool)
      (finally (postgres/stop-pool! pool)))))

(defn- declare-with-trigrams
  "These tables, and pg_trgm, whose operator class a gin index uses."
  [tables]
  {:tables tables :extensions ["pg_trgm"] :provider-extensions [] :functions [] :views []})

(defn- plan-of [pool tables]
  (select-keys (postgres/make-plan-from-tables pool (declare-with-trigrams tables) []) [:instant :slow :stops :changes-to-mark]))

(defn- migrate-and-wait! [pool tables]
  @(:slow-part (postgres/migrate! pool (declare-with-trigrams tables) [] test-settings/migrate-options)))

(def ^:private nothing-to-do {:instant [] :slow [] :stops [] :changes-to-mark []})

(def ^:private customers
  {:table      :customers
   :columns    [[:customer/id           :uuid {:primary-key? true :default :random-uuid}]
                [:customer/workspace-id :uuid]
                [:customer/email        :string {:optional? true :unique? true}]
                [:customer/status       :string]
                [:customer/kind         :string]
                [:customer/created-at   :time/instant]]
   :indexes    [{:columns [[:customer/workspace-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where "customer__status = 'pending'"}
                {:columns [[:customer/created-at :descending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}
                {:columns [[:customer/email :unordered]]
                 :method :gin :ops {:customer/email :gin-trgm-ops} :unique? false :where nil}
                {:columns [[:customer/workspace-id :ascending :nulls-last] ["lower(customer__email)" :ascending :nulls-last]]
                 :method :btree :ops {} :unique? true :where "customer__email IS NOT NULL AND customer__email <> ''"}]
   :uniques    [[:customer/workspace-id :customer/kind]]
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private made-by-hand
  (str "CREATE TABLE customers (customer__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), customer__workspace_id uuid NOT NULL,"
       " customer__email text, customer__status text NOT NULL, customer__kind text NOT NULL,"
       " customer__created_at timestamptz NOT NULL,"
       " CONSTRAINT customers_kind_unique UNIQUE (customer__workspace_id, customer__kind));"
       "CREATE INDEX pending_customers ON customers (customer__workspace_id) WHERE customer__status = 'pending';"
       "CREATE INDEX customers_newest ON customers (customer__created_at DESC NULLS LAST);"
       "CREATE INDEX customers_email_trgm_idx ON customers USING GIN (customer__email gin_trgm_ops);"
       "CREATE UNIQUE INDEX customers_workspace_email_unique_idx ON customers (customer__workspace_id, lower(customer__email))"
       " WHERE customer__email IS NOT NULL AND customer__email <> '';"
       "CREATE UNIQUE INDEX users_email_idx ON customers (customer__email);"
       "INSERT INTO customers (customer__workspace_id, customer__email, customer__status, customer__kind, customer__created_at)"
       " VALUES (gen_random_uuid(), 'a@b.c', 'pending', 'shop', now())"))

(deftest an-index-map-says-what-postgres-can-make
  (let [refusal (fn [index] (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-trigrams [(assoc customers :indexes [index])]) [])
                                                 (catch Exception e e)))))]
    (is (= :groundwork-postgres/bad-index (refusal {:columns [[:customer/email :unordered]] :method :btree :ops {} :unique? false :where nil}))
        "a btree index's columns say their order")
    (is (= :groundwork-postgres/bad-index (refusal {:columns [[:customer/email :ascending :nulls-last]] :method :gin :ops {} :unique? false :where nil}))
        "a gin index's don't")
    (is (= :groundwork-postgres/bad-index (refusal {:columns [[:customer/email :unordered]] :method :gin :ops {} :unique? true :where nil})))
    (is (= :groundwork-postgres/bad-index (refusal {:columns [[:customer/email :ascending :nulls-last]] :method :btree
                                       :ops {:customer/name :text-pattern-ops} :unique? false :where nil})))
    (is (= :groundwork-postgres/bad-tables (refusal {:columns [[:customer/email :ascending :nulls-last]] :method :btree :unique? false :where nil}))
        "every key written out")))

(deftest a-fresh-database-gets-every-index
  (if-not port
    (println "groundwork-postgres.indexes-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [customers])
        (is (= nothing-to-do (plan-of pool [customers])))
        (testing "the unique ones hold"
          (run-sql! pool "INSERT INTO customers (customer__workspace_id, customer__email, customer__status, customer__kind, customer__created_at) VALUES ('7f3c1e2a-0000-4000-8000-000000000001', 'A@b.c', 'x', 'shop', now())")
          (is (thrown? Exception (run-sql! pool "INSERT INTO customers (customer__workspace_id, customer__email, customer__status, customer__kind, customer__created_at) VALUES ('7f3c1e2a-0000-4000-8000-000000000001', 'a@B.c', 'x', 'other', now())"))
              "lower(email) once per workspace")
          (is (thrown? Exception (run-sql! pool "INSERT INTO customers (customer__workspace_id, customer__status, customer__kind, customer__created_at) VALUES ('7f3c1e2a-0000-4000-8000-000000000001', 'x', 'shop', now())"))
              "the :uniques"))
        (testing "a changed condition is another index: the new built, the old dropped, both CONCURRENTLY"
          (let [changed (assoc-in customers [:indexes 0 :where] "customer__status = 'waiting'")
                {:keys [instant slow]} (plan-of pool [changed])]
            (is (= [] instant))
            (is (= 2 (count slow)))
            (is (str/starts-with? (:sql (first slow)) "CREATE INDEX CONCURRENTLY IF NOT EXISTS customers_customer__workspace_id_"))
            (is (str/starts-with? (:sql (second slow)) "DROP INDEX CONCURRENTLY IF EXISTS customers_customer__workspace_id_"))
            (migrate-and-wait! pool [changed])
            (is (= nothing-to-do (plan-of pool [changed])))))))))

(deftest indexes-made-by-hand-are-adopted-without-a-rebuild
  (if-not port
    (println "groundwork-postgres.indexes-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool made-by-hand)
        (let [before (find-oids pool)
              {:keys [instant slow stops]} (plan-of pool [customers])]
          (is (= [] stops))
          (is (= [] slow) "nothing built, nothing dropped")
          (is (every? #(re-find #"^ALTER (INDEX \S+ RENAME TO|TABLE customers (RENAME CONSTRAINT|ADD CONSTRAINT \S+ UNIQUE USING INDEX users_email_idx))" %)
                      (map :sql instant))
              (pr-str (map :sql instant)))
          (is (= 6 (count instant)))
          (migrate-and-wait! pool [customers])
          (is (= nothing-to-do (plan-of pool [customers])))
          (let [after (find-oids pool)]
            (is (= (set (vals before)) (set (vals after))) "every index and key the same object, renamed")
            (is (= (get before "users_email_idx") (get after "customers_customer__email_key"))
                "the unique index is the key's, under the key's name")))))))
