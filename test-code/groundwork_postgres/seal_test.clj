(ns groundwork-postgres.seal-test
  "The seal: a check that finds both fingerprints as sealed skips the plan;
   anything changed, on either side, gets the full check. The Postgres
   tests run when POSTGRES_TEST_PORT is set."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.tables.seal :as seal]
   [groundwork-postgres.test-settings :as test-settings]))

(deftest the-library-files-are-every-file-of-the-library
  (let [root  (io/file "source-code")
        files (->> (file-seq (io/file root "groundwork_postgres"))
                   (filter #(.isFile ^java.io.File %))
                   (map #(str/replace (str (.relativize (.toPath root) (.toPath ^java.io.File %))) "\\" "/"))
                   sort)]
    (is (= files (sort seal/library-files)) "a file added to the library goes in seal/library-files")
    (is (some? @seal/library-contents) "every one is on the classpath")))

(deftest equal-data-fingerprints-the-same-whatever-its-order
  (is (= (seal/make-fingerprint {:a 1 :b #{3 2 1} :c {:x [1 2] :y "z"}})
         (seal/make-fingerprint (into {} [[:c (into {} [[:y "z"] [:x [1 2]]])] [:b #{1 2 3}] [:a 1]]))))
  (is (not= (seal/make-fingerprint {:columns [:a :b]}) (seal/make-fingerprint {:columns [:b :a]}))
      "a vector's order counts: a table's columns are in order")
  (is (nil? (seal/make-fingerprint {:on-change inc})) "a function can't be compared: no fingerprint"))

(deftest a-fingerprint-never-changes-for-the-same-data
  ;; pinned with v0.5.5's (sort-by pr-str xs): a changed order would make
  ;; every sealed database look changed
  (is (= "9c2ef014a58ff82c59e5e5a1e2433055f1625d75ff99f0540d0a60b1261d42aa"
         (seal/make-fingerprint {:table   :shops
                                 :columns [[:shop/id :uuid {:primary-key? true}] [:shop/name :string]]
                                 :sets    #{:b :a "a" 10 2 [:x 1]}
                                 :nested  {:z {:k #{1 "1" :1}} :a [{:q 1 :p 2}]}}))))

(def ^:private shops
  {:table      :shops
   :columns    [[:shop/id   :uuid   {:primary-key? true :default :random-uuid}]
                [:shop/name :string]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private shops-with-city
  (update shops :columns conj [:shop/city :string {:optional? true}]))

(defn- run-sql! [pool text]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- migrate! [pool tables]
  (let [plan (postgres/migrate! pool (test-settings/declare-tables tables) [] test-settings/migrate-options)]
    @(:slow-part plan)
    plan))

(defn- events-logged-since [n]
  (set (map second (drop n @test-settings/lines-logged))))

(deftest a-standing-seal-skips-the-plan
  (if-not test-settings/port
    (println "groundwork-postgres.seal-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! 4 10000)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
        (testing "a migrate that makes the tables seals after them"
          (let [n (count @test-settings/lines-logged)]
            (migrate! pool [shops])
            (is (contains? (events-logged-since n) :groundwork-postgres/sealed))
            (is (some? (seal/find-seal pool)))))
        (testing "the next migrate finds the seal standing and plans nothing"
          (let [n    (count @test-settings/lines-logged)
                plan (migrate! pool [shops])]
            (is (contains? (events-logged-since n) :groundwork-postgres/seal-stands))
            (is (= [[] [] [] []] ((juxt :instant :slow :stops :changes-to-mark) plan)))
            (is (postgres/does-database-match-tables? pool (test-settings/declare-tables [shops]) []))))
        (testing "a map that changed: the seal doesn't stand, the plan runs and seals again"
          (let [n    (count @test-settings/lines-logged)
                plan (migrate! pool [shops-with-city])]
            (is (= 1 (count (:instant plan))))
            (is (not (contains? (events-logged-since n) :groundwork-postgres/seal-stands)))
            (is (contains? (events-logged-since n) :groundwork-postgres/sealed))
            (is (postgres/does-database-match-tables? pool (test-settings/declare-tables [shops-with-city]) []))))
        (testing "a hand edit: the seal doesn't stand, the full check finds it"
          (run-sql! pool "ALTER TABLE shops DROP COLUMN shop__city")
          (is (not (postgres/does-database-match-tables? pool (test-settings/declare-tables [shops-with-city]) [])))
          (let [n (count @test-settings/lines-logged)]
            (is (= 1 (count (:instant (migrate! pool [shops-with-city])))) "the migrate makes the column again")
            (is (contains? (events-logged-since n) :groundwork-postgres/sealed))))
        (finally (postgres/stop-pool! pool))))))
