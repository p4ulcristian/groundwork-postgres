(ns groundwork-postgres.tables-read-test
  "Which tables a question read, hearing NOTIFY, and change triggers. Runs
   when POSTGRES_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as database]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(defn- start-pool! [] (test-settings/start-pool! 3 5000))

(defn- run-sql! [pool text] (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

(def ^:private find-things-named
  (database/make-query-from-sql-text "things named" "SELECT thing__id FROM things WHERE thing__name = param('thing/name')"))
(def ^:private find-named-things
  (database/make-query-from-sql-text "a view" "SELECT thing__id FROM named_things"))
(def ^:private count-things
  (database/make-query-from-sql-text "a function" "SELECT count_things() AS n"))
(def ^:private count-things-saying-so
  (database/make-query-from-sql-text "a function, named" "-- reads: things\nSELECT count_things() AS n"))
(def ^:private find-untyped
  (database/make-query-from-sql-text "a param Postgres can't type" "SELECT param('x') IS NULL AS b"))

(deftest a-key-from-a-table-name
  (is (= :orders (database/turn-table-name-into-table-key "public" "orders")))
  (is (= :billing/invoices (database/turn-table-name-into-table-key "billing" "invoices"))))

(deftest which-tables-a-question-read
  (if-not port
    (println "groundwork-postgres.tables-read-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (start-pool!)]
      (try
        (run-sql! pool (str "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;"
                            "CREATE TABLE things (thing__id int PRIMARY KEY, thing__name text);"
                            "CREATE VIEW named_things AS SELECT * FROM things WHERE thing__name IS NOT NULL;"
                            "CREATE FUNCTION count_things() RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM things'"))
        (testing "a query: its tables, planned without running it"
          (is (= [[] #{:things}] (database/run-and-list-tables-read {:log test-settings/log} #(database/find-rows pool find-things-named {:thing/name "a"}))))
          (is (= [nil #{:things}] (database/run-and-list-tables-read {:log test-settings/log} #(database/find-one-row pool find-things-named {:thing/name "a"})))))
        (testing "a view counts as the tables under it"
          (is (= #{:things} (second (database/run-and-list-tables-read {:log test-settings/log} #(database/find-rows pool find-named-things {}))))))
        (testing "a function hides its tables; the query names them in a comment"
          (is (= #{} (second (database/run-and-list-tables-read {:log test-settings/log} #(database/find-rows pool count-things {})))))
          (is (= #{:things} (second (database/run-and-list-tables-read {:log test-settings/log} #(database/find-rows pool count-things-saying-so {}))))))
        (testing "a query Postgres can't prepare without values is unknown, and the transaction it was in goes on"
          (is (= [1 :unknown]
                 (database/run-and-list-tables-read
                  {:log test-settings/log}
                  #(database/run-in-transaction! pool {:read-only? false :log test-settings/log}
                     (fn [transaction]
                       (database/find-one-row transaction find-untyped {:x "a"})
                       (run-sql! transaction "INSERT INTO things VALUES (1, 'a')"))))))
          (is (= [{:thing/id 1}] (database/find-rows pool find-things-named {:thing/name "a"})))
          (is (= [:warn :groundwork-postgres/tables-read-unknown {:groundwork-postgres/query "a param Postgres can't type"}]
                 (last @test-settings/lines-logged))
              "and the log is told, with the query"))
        (testing "outside run-and-list-tables-read nothing is noted"
          (is (= [{:thing/id 1}] (database/find-rows pool find-things-named {:thing/name "a"}))))
        (finally (database/stop-pool! pool))))))

(deftest hearing-notify
  (if-not port
    (println "groundwork-postgres.tables-read-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool     (start-pool!)
          heard    (promise)
          lines    (atom [])
          listener (database/start-listening! pool "groundwork_postgres_test" {:log                    (test-settings/make-log lines)
                                                                      :meter                  (:groundwork-postgres/meter pool)
                                                                      :on-notifications       #(do (deliver heard %)
                                                                                                   (throw (RuntimeException. "the app's own failure")))
                                                                      :on-reconnect           (fn [])
                                                                      :batch-window-ms        50
                                                                      :check-stopped-every-ms 500
                                                                      :reconnect-after-ms     1000
                                                                      :stop-wait-ms           2000})]
      (try
        (Thread/sleep 300)
        (run-sql! pool "SELECT pg_notify('groundwork_postgres_test', 'one'), pg_notify('groundwork_postgres_test', 'two')")
        (is (= [{:channel "groundwork_postgres_test" :payload "one"} {:channel "groundwork_postgres_test" :payload "two"}]
               (deref heard 3000 :nothing))
            "two notifications close together: one batch")
        (Thread/sleep 100)
        (let [[[level event data]] @lines]
          (is (= [:error :groundwork-postgres/listener-function-failed "groundwork_postgres_test" "the app's own failure"]
                 [level event (:groundwork-postgres/channel data) (ex-message (:error data))])
              "a failing function is written to the log, and listening goes on"))
        (testing "a break: written to the log, and listening again"
          (run-sql! pool "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query = 'LISTEN groundwork_postgres_test'")
          (Thread/sleep 1500)
          (is (some (fn [[level event data]] (and (= [:warn :groundwork-postgres/listening-broke] [level event])
                                                  (= 1000 (:groundwork-postgres/reconnect-after-ms data))
                                                  (instance? Throwable (:error data))))
                    @lines)))
        (finally
          (is (database/stop-listening! listener) "the listener had ended")
          (database/stop-pool! pool))))))

(def ^:private told-things
  {:table     :told-things
   :columns   [[:told-thing/id :int {:primary-key? true}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change {:function "public.tell_test" :arguments ["told_thing__id"]}})

(defn- declare-with-tell
  "These tables, and the function their change triggers call (postgres/tell_test.sql)."
  [tables]
  {:tables tables :extensions [] :functions ["groundwork_postgres/tell_test.sql"] :views []})

(deftest change-triggers
  (if-not port
    (println "groundwork-postgres.tables-read-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (start-pool!)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
        (let [plan (database/make-plan-from-tables pool (declare-with-tell [told-things]) [])]
          (is (= 3 (count (filter #(str/starts-with? (:sql %) "CREATE TRIGGER") (:instant plan))))
              "one trigger per insert, update and delete"))
        (let [lines (atom [])]
          @(:slow-part (database/migrate! pool (declare-with-tell [told-things]) [] (assoc test-settings/migrate-options :log (test-settings/make-log lines))))
          (is (= [[:info :groundwork-postgres/planned :instant]]
                 (distinct (map (fn [[level event data]] [level event (:groundwork-postgres/part data)]) @lines))))
          (is (some #(str/starts-with? (:groundwork-postgres/sql (nth % 2)) "CREATE TRIGGER") @lines)
              "each planned statement is written to the log, with its part and why"))
        (is (database/does-database-match-tables? pool (declare-with-tell [told-things]) []))
        (testing "a changed :on-change is new triggers in place of the old"
          (let [changed (assoc-in told-things [:on-change :arguments] ["told_thing__id" "x"])
                plan    (database/make-plan-from-tables pool (declare-with-tell [changed]) [])]
            (is (= 3 (count (filter #(str/starts-with? (:sql %) "DROP TRIGGER") (:instant plan)))))
            (is (= 3 (count (filter #(str/starts-with? (:sql %) "CREATE TRIGGER") (:instant plan)))))))
        (testing "no :on-change: the triggers go"
          (is (= 3 (count (filter #(str/starts-with? (:sql %) "DROP TRIGGER")
                                  (:instant (database/make-plan-from-tables pool (declare-with-tell [(assoc told-things :on-change nil)]) [])))))))
        (finally (database/stop-pool! pool))))))
