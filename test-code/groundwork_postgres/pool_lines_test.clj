(ns groundwork-postgres.pool-lines-test
  "HikariCP's and PgJDBC's own lines reach the log of the pool they are
   about, each pool's its own. Runs when POSTGRES_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings])
  (:import
   (java.util.logging Level Logger)))

(defn- start-pool! [lines pool-size leak-warning-after-ms]
  (postgres/start-pool! (assoc (test-settings/make-pool-settings pool-size 5000) :leak-warning-after-ms leak-warning-after-ms)
                      {:log (test-settings/make-log lines) :meter (test-settings/make-meter)}))

(defn- hold-a-connection! [pool ms]
  (postgres/run-in-transaction! pool {:read-only? true :log test-settings/log} (fn [_] (Thread/sleep (long ms)))))

(defn- find-said [lines text]
  (filterv (fn [[_ event data]] (and (= :groundwork-postgres/pool-said event) (str/includes? (:groundwork-postgres/message data) text))) @lines))

(defn- wait-until [done?]
  (loop [n 0]
    (when (and (not (done?)) (< n 100))
      (Thread/sleep 50)
      (recur (inc n)))))

(def ^:private find-backend
  (postgres/make-query-from-sql-text "the backend" "SELECT pg_backend_pid() AS pid"))

(def ^:private end-backend
  (postgres/make-query-from-sql-text "end the backend" "SELECT pg_terminate_backend(param('pid')) AS ended"))

(deftest each-pool-hears-its-own-lines
  (if-not test-settings/port
    (println "groundwork-postgres.pool-lines-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [lines-a (atom [])
          lines-b (atom [])
          pool-a  (start-pool! lines-a 1 2000)
          pool-b  (start-pool! lines-b 2 2000)
          name-a  (:groundwork-postgres/pool-name pool-a)
          name-b  (:groundwork-postgres/pool-name pool-b)]
      (try
        (testing "two pools on one database have names of their own"
          (is (not= name-a name-b)))
        (testing "SLF4J hands HikariCP's lines to groundwork-postgres: nothing says they went elsewhere"
          (is (= [] (filterv #(= :groundwork-postgres/pool-lines-elsewhere (second %)) (concat @lines-a @lines-b)))))
        (testing "a pool's start is in its own log, at info"
          (is (= [[:info {:groundwork-postgres/pool name-a :groundwork-postgres/logger "com.zaxxer.hikari.HikariDataSource"
                          :groundwork-postgres/message (str name-a " - Start completed.")}]]
                 (mapv (fn [[level _ data]] [level data]) (find-said lines-a "Start completed"))))
          (is (= [[:info name-b]] (mapv (fn [[level _ data]] [level (:groundwork-postgres/pool data)]) (find-said lines-b "Start completed")))))
        (testing "a connection held past :leak-warning-after-ms is a warning in its pool's log, with the stack"
          (let [held [(future (hold-a-connection! pool-a 2600)) (future (hold-a-connection! pool-b 2600))]]
            (run! deref held))
          (wait-until #(and (seq (find-said lines-a "was returned to the pool")) (seq (find-said lines-b "was returned to the pool"))))
          (doseq [[lines pool-name] [[lines-a name-a] [lines-b name-b]]]
            (let [[[level _ {:keys [error] :as data}] :as leaks] (find-said lines "Connection leak detection triggered")]
              (is (= 1 (count leaks)))
              (is (= :warn level))
              (is (= [pool-name "com.zaxxer.hikari.pool.ProxyLeakTask"] [(:groundwork-postgres/pool data) (:groundwork-postgres/logger data)]))
              (is (instance? Exception error) "HikariCP's stack of where the connection was taken")))
          (testing "and its return too, said on the thread that held it"
            (is (= [[:info name-a]] (mapv (fn [[level _ data]] [level (:groundwork-postgres/pool data)]) (find-said lines-a "was returned to the pool"))))))
        (testing "a connection Postgres ended is a warning in its pool's log, and the pool goes on"
          (let [{:keys [pid]} (postgres/find-one-row pool-a find-backend {})]
            (postgres/find-one-row pool-b end-backend {:pid pid})
            ;; HikariCP checks a connection only once it has sat idle half a second
            (Thread/sleep 700)
            (is (not= pid (:pid (postgres/find-one-row pool-a find-backend {}))))
            (let [[[level _ data]] (find-said lines-a "Failed to validate connection")]
              (is (= [:warn name-a] [level (:groundwork-postgres/pool data)])))
            (is (= [] (find-said lines-b "Failed to validate connection")))))
        (testing "no line of one pool is in the other's log"
          (is (every? #(= name-a (:groundwork-postgres/pool (nth % 2))) @lines-a))
          (is (every? #(= name-b (:groundwork-postgres/pool (nth % 2))) @lines-b)))
        (testing "a line no pool owns goes to each open pool's log; below info, nowhere"
          (let [logger (Logger/getLogger "org.postgresql.cellar-test")]
            @(future (.log logger Level/WARNING "a line no pool owns")
                     (.log logger Level/FINE "a line below info")))
          (is (= [[:warn {:groundwork-postgres/pool nil :groundwork-postgres/logger "org.postgresql.cellar-test" :groundwork-postgres/message "a line no pool owns"}]]
                 (mapv (fn [[level _ data]] [level data]) (find-said lines-a "no pool owns"))
                 (mapv (fn [[level _ data]] [level data]) (find-said lines-b "no pool owns"))))
          (is (= [] (find-said lines-a "below info"))))
        (finally
          (postgres/stop-pool! pool-a)
          (postgres/stop-pool! pool-b)))
      (testing "a pool's stop is in its own log"
        (is (= [[:info name-a]] (mapv (fn [[level _ data]] [level (:groundwork-postgres/pool data)]) (find-said lines-a "Shutdown completed"))))
        (is (= [[:info name-b]] (mapv (fn [[level _ data]] [level (:groundwork-postgres/pool data)]) (find-said lines-b "Shutdown completed"))))))))
