(ns groundwork-postgres.test-settings
  "The settings and options the tests run with, each written out."
  (:require
   [groundwork-postgres.api :as postgres]
   [clojure.string :as str]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]
   [groundwork-settings.api :as settings]))

(def port (some-> (System/getenv "POSTGRES_TEST_PORT") parse-long))

(def password
  "The test user's password, a secret as the app's settings hold one."
  (some->> (System/getenv "POSTGRES_TEST_PASSWORD") (settings/make-secret :groundwork-postgres/password)))

(def database
  "The test database: POSTGRES_TEST_DATABASE, or postgres when unset. The
   tests empty its public folder, so on a Postgres others use too it is
   one of the tests' own."
  (or (System/getenv "POSTGRES_TEST_DATABASE") "postgres"))

(defn make-pool-settings
  "A test Postgres on localhost, the database from POSTGRES_TEST_DATABASE,
   the user from POSTGRES_TEST_USER, the password from
   POSTGRES_TEST_PASSWORD, no TLS."
  [pool-size statement-timeout-ms]
  {:host                           "localhost"
   :port                           port
   :database                       database
   :user                           (System/getenv "POSTGRES_TEST_USER")
   :password                       password
   :tls                            :off
   :tls-root-cert                  nil
   :pool-size                      pool-size
   :connection-wait-ms             30000
   :connection-check-wait-ms       5000
   :connection-max-lifetime-ms     1800000
   :connection-keepalive-every-ms  120000
   :leak-warning-after-ms          :none
   :connect-timeout-seconds        10
   :read-timeout-seconds           :none
   :tcp-keepalive?                 false
   :statement-timeout-ms           statement-timeout-ms
   :lock-timeout-ms                :none
   :idle-in-transaction-timeout-ms :none
   :time-zone                      "UTC"
   :metric-buckets                 {:query-seconds              [0.001 0.01 0.1 1]
                                    :connection-acquire-seconds [0.001 0.01 0.1 1]
                                    :connection-usage-seconds   [0.001 0.01 0.1 1]}})

(defn make-log
  "A log function as an app's logger makes one, keeping each call's
   [level event data] in the atom lines."
  [lines]
  (fn [level event data] (swap! lines conj [level event data]) nil))

(def lines-logged
  "What the tests' log has been handed, [level event data] each."
  (atom []))

(def log (make-log lines-logged))

(def meter-lines
  "What the tests' meters have written to their log, as text."
  (atom []))

(defn make-meter
  "A new meter, as an app makes its own: a meter holds one pool's metrics.
   Its log is a logger's (meter takes no other), its lines in meter-lines."
  []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 100000}
                    {:log (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 10
                                                                       :write-to #(swap! meter-lines conj %)}))}))

(defn make-pool-options
  "start-pool!'s options: the tests' log and a new meter."
  []
  {:log log :meter (make-meter)})

(defn start-pool! [pool-size statement-timeout-ms]
  (postgres/start-pool! (make-pool-settings pool-size statement-timeout-ms) (make-pool-options)))

(defn find-metric-lines
  "The groundwork-metrics's sample lines that start with prefix."
  [a-meter prefix]
  (filterv #(str/starts-with? % prefix) (str/split-lines (metrics/write-metrics-text a-meter))))

(def migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
   :log          log})

(defn declare-tables
  "What make-plan-from-tables and migrate! take: these tables, and no
   extensions, functions or views."
  [tables]
  {:tables tables :extensions [] :functions [] :views []})
