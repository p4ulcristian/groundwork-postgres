(ns groundwork-postgres.quotes-test
  "Text and names in the SQL groundwork-postgres makes are quoted by PgJDBC.
   The Postgres test runs when POSTGRES_TEST_PORT is set; it starts from an
   empty database."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest text-and-names-are-quoted-by-pgjdbc
  (testing "a quote doubled; a backslash kept, for standard_conforming_strings on"
    (is (= "'it''s'" (postgres/quote-text "it's")))
    (is (= "'a\\b'" (postgres/quote-text "a\\b")))
    (is (= "''" (postgres/quote-text "")))
    (is (= "'7'" (postgres/quote-text 7)) "a value, as its text"))
  (testing "a name in double quotes, a double quote in it doubled"
    (is (= "\"orders\"" (postgres/quote-name "orders")))
    (is (= "\"may orders/edit on orders\"" (postgres/quote-name "may orders/edit on orders")))
    (is (= "\"a\"\"b\"" (postgres/quote-name "a\"b"))))
  (testing "a zero byte, which Postgres text can't hold, is refused"
    (is (= :groundwork-postgres/not-quotable (refusal #(postgres/quote-text "a\u0000b"))))
    (is (= :groundwork-postgres/not-quotable (refusal #(postgres/quote-name "a\u0000b"))))))

(def ^:private notes
  {:table      :notes
   :columns    [[:note/id     :uuid {:primary-key? true :default :random-uuid}]
                [:note/text   :string {:default "it's a \\ back"}]
                [:note/status [:enum "o'clock" "back\\slash"] {:default "o'clock"}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(deftest quoted-text-reaches-postgres-as-it-was
  (if-not port
    (println "groundwork-postgres.quotes-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! 2 10000)
          run! (fn [text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))]
      (try
        (run! "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
        (postgres/migrate! pool (test-settings/declare-tables [notes]) [] test-settings/migrate-options)
        (is (postgres/does-database-match-tables? pool (test-settings/declare-tables [notes]) [])
            "the defaults and the enum's check, as Postgres prints them back, are the map's")
        (run! "INSERT INTO notes DEFAULT VALUES")
        (run! "INSERT INTO notes (note__status) VALUES ('back\\slash')")
        (is (= [{:note/text "it's a \\ back" :note/status "o'clock"}
                {:note/text "it's a \\ back" :note/status "back\\slash"}]
               (postgres/find-rows pool (postgres/make-query-from-sql-text "test" "SELECT note__text, note__status FROM notes ORDER BY note__status DESC") {})))
        (is (= [{:quoted "it's \\ \"x\""}]
               (postgres/find-rows pool (postgres/make-query-from-sql-text
                                         "test" (str "SELECT " (postgres/quote-text "it's \\ \"x\"") " AS " (postgres/quote-name "quoted")))
                                   {}))
            "text and a name, quoted, read back as they were")
        (finally (postgres/stop-pool! pool))))))
