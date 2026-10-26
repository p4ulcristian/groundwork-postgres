(ns groundwork-postgres.objects-test
  "Checks, functions, views and extensions are declared, compared and
   dropped like tables; the new default words. The Postgres tests run when
   POSTGRES_TEST_PORT is set; each starts from an empty database."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.adopt-test :refer [find-oids]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(defn- run-sql! [pool text]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text]
  (postgres/find-rows pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
      (f pool)
      (finally (postgres/stop-pool! pool)))))

(def ^:private nothing-to-do {:instant [] :slow [] :stops [] :changes-to-mark []})

(defn- plan-of [pool declared changes]
  (select-keys (postgres/make-plan-from-tables pool declared changes) [:instant :slow :stops :changes-to-mark]))

(defn- migrate-and-wait! [pool declared changes]
  @(:slow-part (postgres/migrate! pool declared changes test-settings/migrate-options)))

(def ^:private jobs
  {:table      :jobs
   :columns    [[:job/id      :uuid {:primary-key? true :default :time-ordered-uuid}]
                [:job/shop-id :uuid]
                [:job/status  [:enum "open" "done"] {:default "open"}]
                [:job/photos  [:json [:vector :string]] {:default []}]
                [:job/extras  [:json :any] {:default {}}]
                [:job/tags    [:vector :string] {:default []}]
                [:job/day     :time/local-date {:default :today}]
                [:job/note    :string {:optional? true}]]
   :indexes    []
   :uniques    []
   :checks     ["jsonb_typeof(job__photos) = 'array'"]
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private declared
  {:tables     [jobs]
   :extensions ["pg_trgm"]
   :functions  ["groundwork_postgres/objects/count_jobs.sql"]
   :views      ["groundwork_postgres/objects/jobs_per_shop.sql" "groundwork_postgres/objects/busy_shops.sql"]})

(deftest a-fresh-database-gets-them-all
  (if-not port
    (println "groundwork-postgres.objects-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [{:keys [instant]} (plan-of pool declared [])]
          (testing "in order: the extension, the function (its body names a table still to make), the table, the views"
            (is (= ["CREATE EXTENSION" "SET LOCAL check_function_bodies" "-- How many jobs" "CREATE TABLE jobs"]
                   (map #(subs (:sql %) 0 (min (count (:sql %)) (count %2))) (take 4 instant)
                        ["CREATE EXTENSION" "SET LOCAL check_function_bodies" "-- How many jobs" "CREATE TABLE jobs"])))
            (is (= ["view jobs_per_shop from groundwork_postgres/objects/jobs_per_shop.sql" "view busy_shops from groundwork_postgres/objects/busy_shops.sql"]
                   (map :why (take-last 2 instant))))))
        (migrate-and-wait! pool declared [])
        (is (= nothing-to-do (plan-of pool declared [])) "and then nothing differs: defaults, check, function, views")
        (let [shop (random-uuid)]
          (run-sql! pool (str "INSERT INTO jobs (job__shop_id) VALUES ('" shop "'), ('" shop "')"))
          (testing "the new default words"
            (let [[job] (find-rows pool "SELECT job__id, job__photos, job__extras, job__tags, job__day, uuid_extract_version(job__id) AS id__version FROM jobs")]
              (is (= [[] {} [] (java.time.LocalDate/now (java.time.ZoneId/of "UTC")) 7]
                     [(:job/photos job) (:job/extras job) (:job/tags job) (:job/day job) (:id/version job)]))))
          (testing "the function and the views work"
            (is (= [{:shop/jobs 2}] (find-rows pool (str "SELECT count_jobs('" shop "') AS shop__jobs"))))
            (is (= [{:shop/id shop}] (find-rows pool "SELECT shop__id FROM busy_shops"))))
          (testing "the check holds"
            (is (thrown? Exception (run-sql! pool "UPDATE jobs SET job__photos = '{}'")))))))))

(deftest what-changes-is-made-again-and-what-no-file-names-goes
  (if-not port
    (println "groundwork-postgres.objects-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool declared [])
        (run-sql! pool (str "INSERT INTO jobs (job__shop_id) VALUES ('" (random-uuid) "')"))
        (testing "another body: replaced, nothing else"
          (let [changed (assoc declared :functions ["groundwork_postgres/objects/count_jobs_done.sql"])]
            (is (= ["SET LOCAL check_function_bodies = off" "function public.count_jobs from groundwork_postgres/objects/count_jobs_done.sql"]
                   (map #(if (str/starts-with? (:sql %) "SET") (:sql %) (:why %)) (:instant (plan-of pool changed [])))))
            (migrate-and-wait! pool changed [])
            (is (= nothing-to-do (plan-of pool changed [])))))
        (testing "a changed view is dropped with the view over it, and both made again after the tables"
          (let [changed (assoc declared :functions ["groundwork_postgres/objects/count_jobs_done.sql"]
                               :views ["groundwork_postgres/objects/jobs_per_shop_done.sql" "groundwork_postgres/objects/busy_shops.sql"])
                sqls    (map :sql (:instant (plan-of pool changed [])))]
            (is (= "DROP VIEW IF EXISTS \"busy_shops\", \"jobs_per_shop\"" (first sqls)))
            (is (= 3 (count sqls)))
            (migrate-and-wait! pool changed [])
            (is (= nothing-to-do (plan-of pool changed [])))))
        (testing "a view over a column a change drops goes before the change"
          (let [with-notes (assoc declared :functions ["groundwork_postgres/objects/count_jobs_done.sql"]
                                  :views ["groundwork_postgres/objects/jobs_per_shop_done.sql" "groundwork_postgres/objects/busy_shops.sql"
                                          "groundwork_postgres/objects/notes_of_jobs.sql"])]
            (migrate-and-wait! pool with-notes [])
            (let [noteless (-> with-notes
                               (assoc :views ["groundwork_postgres/objects/jobs_per_shop_done.sql" "groundwork_postgres/objects/busy_shops.sql"])
                               (update-in [:tables 0 :columns] #(vec (butlast %))))
                  changes  [{:id "o1" :drop-column [:jobs :job/note] :why "notes moved"}]
                  sqls     (map :sql (:instant (plan-of pool noteless changes)))]
              (is (= ["DROP VIEW IF EXISTS \"notes_of_jobs\"" "ALTER TABLE jobs DROP COLUMN job__note"] sqls)
                  "a view no file names goes too, first")
              (migrate-and-wait! pool noteless changes)
              (is (= nothing-to-do (plan-of pool noteless changes))))))
        (testing "a function and views no file names are dropped"
          (let [bare (assoc declared :functions [] :views [] :tables [(update jobs :columns #(vec (butlast %)))])]
            (is (= ["DROP VIEW IF EXISTS \"busy_shops\", \"jobs_per_shop\"" "DROP FUNCTION \"public\".\"count_jobs\"(shop uuid)"]
                   (map :sql (:instant (plan-of pool bare [{:id "o1" :drop-column [:jobs :job/note] :why "notes moved"}])))))))))))

(deftest a-check-is-declared-like-the-rest
  (if-not port
    (println "groundwork-postgres.objects-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [plain (assoc jobs :checks [])
              only  {:tables [plain] :extensions [] :functions [] :views []}]
          (migrate-and-wait! pool only [])
          (run-sql! pool (str "INSERT INTO jobs (job__shop_id) VALUES ('" (random-uuid) "')"))
          (testing "added to a table with rows: NOT VALID now, proven in the slow part"
            (let [{:keys [instant slow]} (plan-of pool {:tables [jobs] :extensions [] :functions [] :views []} [])]
              (is (re-find #"^ALTER TABLE jobs ADD CONSTRAINT jobs_check_\w+ CHECK \(jsonb_typeof\(job__photos\) = 'array'\) NOT VALID$"
                           (:sql (first instant))))
              (is (re-find #"^ALTER TABLE jobs VALIDATE CONSTRAINT jobs_check_" (:sql (first slow))))))
          (testing "one made by hand, the same condition, is adopted"
            (run-sql! pool "ALTER TABLE jobs ADD CONSTRAINT photos_are_a_list CHECK (jsonb_typeof(job__photos) = 'array')")
            (let [before (get (find-oids pool) "photos_are_a_list")
                  {:keys [instant slow]} (plan-of pool {:tables [jobs] :extensions [] :functions [] :views []} [])]
              (is (= 1 (count instant)))
              (is (re-find #"^ALTER TABLE jobs RENAME CONSTRAINT photos_are_a_list TO jobs_check_" (:sql (first instant))))
              (is (= [] slow))
              (migrate-and-wait! pool {:tables [jobs] :extensions [] :functions [] :views []} [])
              (is (= #{before} (set (keep (fn [[n oid]] (when (str/starts-with? n "jobs_check_") oid)) (find-oids pool)))))))
          (testing "a condition Postgres refuses is a stop, in its words"
            (let [{:keys [stops]} (plan-of pool {:tables [(assoc jobs :checks ["job__nothing > 0"])] :extensions [] :functions [] :views []} [])]
              (is (= 1 (count stops)))
              (is (re-find #"jobs's check jobs_check_\w+ can't be made: .*job__nothing" (first stops))))))))))

(deftest an-extension-no-list-names-is-a-stop
  (if-not port
    (println "groundwork-postgres.objects-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool "CREATE EXTENSION IF NOT EXISTS pg_trgm")
        (is (= ["extension pg_trgm is in the database but in no list: add it to :extensions, or drop it by hand (its types may hold data)"]
               (:stops (plan-of pool {:tables [jobs] :extensions [] :functions [] :views []} []))))))))

(deftest a-file-is-one-function-or-view
  (doseq [file ["groundwork_postgres/objects/with_param.sql" "groundwork_postgres/objects/not_a_function.sql"]]
    (is (= :groundwork-postgres/bad-file
           (:type (ex-data (try (postgres/make-plan-from-tables nil (assoc declared :functions [file]) [])
                                (catch Exception e e)))))
        file))
  (is (= :groundwork-postgres/bad-file
         (:type (ex-data (try (postgres/make-plan-from-tables nil (assoc declared :views ["groundwork_postgres/objects/count_jobs.sql"]) [])
                              (catch Exception e e))))))
  (is (= :groundwork-postgres/bad-tables
         (:type (ex-data (try (postgres/make-plan-from-tables nil (dissoc declared :views) []) (catch Exception e e)))))
      "every key written out"))

(defn- make-function [function-name answer]
  {:name function-name
   :sql  (str "CREATE OR REPLACE FUNCTION " function-name "() RETURNS boolean LANGUAGE plpgsql STABLE AS $$ BEGIN RETURN "
              answer "; END $$")})

(deftest a-function-a-library-makes-is-declared-like-a-files
  (if-not port
    (println "groundwork-postgres.objects-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [may-see  (make-function "public.\"may see on jobs\"" "true")
              gated    (assoc jobs :gates {:read   "public.\"may see on jobs\"()" :insert "true"
                                           :update "true"                      :delete "false"})
              with-it  (assoc declared :tables [gated] :functions ["groundwork_postgres/objects/count_jobs.sql" may-see])]
          (testing "made in its folder before the gate that calls it, then nothing differs"
            (let [whys (map :why (:instant (plan-of pool with-it [])))]
              (is (some #{"function public.may see on jobs made by a library"} whys))
              (is (< (.indexOf ^java.util.List (vec whys) "function public.may see on jobs made by a library")
                     (first (keep-indexed #(when (str/starts-with? %2 "missing policy") %1) whys)))
                  "the function before the table's gates"))
            (migrate-and-wait! pool with-it [])
            (is (= nothing-to-do (plan-of pool with-it [])))
            (is (= [{:job/seen true}] (find-rows pool "SELECT public.\"may see on jobs\"() AS job__seen"))))
          (testing "another body: replaced, nothing else"
            (let [changed (assoc with-it :functions ["groundwork_postgres/objects/count_jobs.sql" (make-function "public.\"may see on jobs\"" "false")])]
              (is (= ["SET LOCAL check_function_bodies = off" "function public.may see on jobs made by a library"]
                     (map #(if (str/starts-with? (:sql %) "SET") (:sql %) (:why %)) (:instant (plan-of pool changed [])))))
              (migrate-and-wait! pool changed [])
              (is (= nothing-to-do (plan-of pool changed [])))))
          (testing "gone with the gate that called it: the gate first, then the function"
            (let [bare (assoc declared :functions ["groundwork_postgres/objects/count_jobs.sql"])
                  sqls (map :sql (:instant (plan-of pool bare [])))]
              (is (= "DROP FUNCTION \"public\".\"may see on jobs\"()" (last sqls)))
              (migrate-and-wait! pool bare [])
              (is (= nothing-to-do (plan-of pool bare []))))))))))

(deftest a-made-function-is-one-function-named-as-it-says
  (doseq [[why entry] [["another name than its :name" {:name "made.other" :sql (:sql (make-function "made.one" "true"))}]
                       ["not a function"               {:name "made.one" :sql "SELECT 1"}]
                       ["two names"                    {:name "made.one" :sql "CREATE OR REPLACE FUNCTION made.one.two() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$"}]]]
    (is (= :groundwork-postgres/bad-function
           (:type (ex-data (try (postgres/make-plan-from-tables nil (assoc declared :functions [entry]) [])
                                (catch Exception e e)))))
        why))
  (is (= :groundwork-postgres/bad-tables
         (:type (ex-data (try (postgres/make-plan-from-tables nil (assoc declared :functions [{:name "made.one"}]) [])
                              (catch Exception e e)))))
      "every key written out"))

(deftest the-apps-own-role-can-check-functions-and-views
  (if-not port
    (println "groundwork-postgres.objects-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_postgres_app') THEN CREATE ROLE groundwork_postgres_app; END IF; END $$")
        (run-sql! pool "REVOKE CREATE ON SCHEMA public FROM PUBLIC")
        (migrate-and-wait! pool declared [])
        (postgres/grant-tables-to-role! pool (:tables declared) "groundwork_postgres_app")
        (is (postgres/run-in-transaction! pool {:read-only? false :log test-settings/log}
              (fn [transaction]
                (run-sql! transaction "SET LOCAL ROLE groundwork_postgres_app")
                (postgres/does-database-match-tables? transaction declared [])))
            "as the app's role, which may make nothing in public: the functions and views are made only in its temporary folder")))))
