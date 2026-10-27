(ns groundwork-postgres.api-names-test
  "The public names of groundwork-postgres's api. Adding or removing one
   fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-postgres.api]))

(deftest the-api-names
  (is (= '#{settings-shape run-and-list-tables-read turn-table-name-into-table-key start-listening! stop-listening! is-listening?
           start-pool! stop-pool!
           make-query-from-sql-file make-query-from-sql-text
           find-rows find-one-row run-statement! run-in-transaction! run-after-commit! is-transaction?
           make-plan-from-tables run-instant-part! run-slow-part! migrate!
           does-database-match-tables? turn-table-into-row-shape
           turn-key-into-column-name turn-table-key-into-table-name turn-column-type-into-postgres-type
           quote-text quote-name
           grant-tables-to-role!}
         (set (keys (ns-publics 'groundwork-postgres.api))))))
