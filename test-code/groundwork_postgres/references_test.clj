(ns groundwork-postgres.references-test
  "References over several columns and declared triggers. The Postgres
   tests run when POSTGRES_TEST_PORT is set; they say they were skipped
   otherwise. Each starts from an empty database."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(def ^:private shops
  {:table      :shops
   :columns    [[:shop/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private orders
  {:table      :orders
   :columns    [[:order/id      :uuid {:primary-key? true :default :random-uuid}]
                [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private fill-trigger
  {:function "public.fill_job" :arguments [] :timing :before :events #{:insert :update}
   :update-of [:job/order-id] :for-each :row :when nil})

(def ^:private jobs
  {:table      :jobs
   :columns    [[:job/id       :uuid {:primary-key? true :default :random-uuid}]
                [:job/order-id :uuid {:references {:to :order/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                [:job/shop-id  :uuid]]
   :indexes    [{:columns [[:job/order-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references [{:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id] :on-delete :refuse :on-update :cascade :checked-at :statement}]
   :triggers   [fill-trigger]
   :gates      nil
   :on-change  nil})

(defn- run-sql! [pool text]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text]
  (postgres/find-rows pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- declare-with-fill
  "These tables, and the functions the tests' triggers call: the jobs'
   fill (postgres/fill_job.sql), a counter (postgres/count_moves.sql) and
   a guard that refuses a TRUNCATE (postgres/refuse_truncate.sql)."
  [tables]
  {:tables     tables
   :extensions []
   :functions  ["groundwork_postgres/fill_job.sql" "groundwork_postgres/count_moves.sql" "groundwork_postgres/refuse_truncate.sql"]
   :views      []})

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
      (f pool)
      (finally (postgres/stop-pool! pool)))))

(defn- migrate-and-wait! [pool tables]
  @(:slow-part (postgres/migrate! pool (declare-with-fill tables) [] test-settings/migrate-options)))

(defn- plan-of [pool tables]
  (select-keys (postgres/make-plan-from-tables pool (declare-with-fill tables) []) [:instant :slow :stops :changes-to-mark]))

(def ^:private nothing-to-do {:instant [] :slow [] :stops [] :changes-to-mark []})

(deftest a-reference-over-several-columns-names-its-targets-primary-key
  (is (= :groundwork-postgres/bad-reference
         (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders (assoc-in jobs [:references 0 :to] [:order/shop-id :order/id :order/shop-id])]) [])
                              (catch Exception e e)))))
      "columns and :to pair up")
  (let [shopless (assoc jobs :references [{:columns [:job/shop-id :job/shop-id] :to [:order/shop-id :order/shop-id] :on-delete :refuse :on-update :cascade :checked-at :statement}])]
    (is (re-find #"primary key among :to"
                 (ex-message (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders shopless]) []) (catch Exception e e))))))
  (is (= :groundwork-postgres/bad-tables
         (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders (assoc-in jobs [:references 0 :columns] [:job/order-id])]) [])
                              (catch Exception e e)))))
      "a one-column reference is the column's :references"))

(deftest a-triggers-update-of-names-its-columns-with-update
  (is (= :groundwork-postgres/bad-trigger
         (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders (assoc-in jobs [:triggers 0 :events] #{:insert})]) [])
                              (catch Exception e e))))))
  (is (= :groundwork-postgres/bad-trigger
         (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders (assoc-in jobs [:triggers 0 :update-of] [:job/nothing])]) [])
                              (catch Exception e e)))))))

(deftest a-fresh-database-gets-the-reference-its-unique-key-and-the-trigger
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [shops orders jobs])
        (is (= nothing-to-do (plan-of pool [shops orders jobs])))
        (let [[shop-a shop-b] (map :shop/id (find-rows pool "INSERT INTO shops (shop__id) VALUES (DEFAULT), (DEFAULT) RETURNING shop__id"))
              order (:order/id (first (find-rows pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop-a "') RETURNING order__id"))))]
          (testing "the trigger fills the carried column on insert"
            (run-sql! pool (str "INSERT INTO jobs (job__order_id) VALUES ('" order "')"))
            (is (= [shop-a] (map :job/shop-id (find-rows pool "SELECT job__shop_id FROM jobs")))))
          (testing "a change of the parent's columns is carried down"
            (run-sql! pool (str "UPDATE orders SET order__shop_id = '" shop-b "'"))
            (is (= [shop-b] (map :job/shop-id (find-rows pool "SELECT job__shop_id FROM jobs")))))
          (testing "a child can't say another value than its parent's"
            (is (thrown? Exception (run-sql! pool (str "UPDATE jobs SET job__shop_id = '" shop-a "'")))))
          (testing "a parent with children can't be deleted"
            (is (thrown? Exception (run-sql! pool "DELETE FROM orders")))))))))

(deftest a-reference-added-to-tables-with-rows-waits-for-its-unique-key
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [plain-jobs (assoc jobs :references [] :triggers [])]
          (migrate-and-wait! pool [shops orders plain-jobs])
          (run-sql! pool "INSERT INTO shops DEFAULT VALUES")
          (run-sql! pool "INSERT INTO orders (order__shop_id) SELECT shop__id FROM shops")
          (run-sql! pool "INSERT INTO jobs (job__order_id, job__shop_id) SELECT order__id, order__shop_id FROM orders")
          (testing "the unique key is built in the slow part, the reference made and proven after it"
            (let [{:keys [instant slow stops]} (plan-of pool [shops orders jobs])
                  slow-sql (mapv :sql slow)]
              (is (= [] stops))
              (is (= ["CREATE TRIGGER"] (map #(subs (:sql %) 0 14) instant)) "only the trigger is instant")
              (is (re-find #"CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS orders_order__id_order__shop_id_key ON orders \(order__id, order__shop_id\)"
                           (first slow-sql)))
              (is (re-find #"ADD CONSTRAINT jobs_job__order_id_job__shop_id_fkey FOREIGN KEY \(job__order_id, job__shop_id\) REFERENCES orders \(order__id, order__shop_id\) ON DELETE NO ACTION ON UPDATE CASCADE NOT VALID"
                           (nth slow-sql 2)))
              (is (str/starts-with? (nth slow-sql 3) "ALTER TABLE jobs VALIDATE CONSTRAINT"))))
          (migrate-and-wait! pool [shops orders jobs])
          (is (= nothing-to-do (plan-of pool [shops orders jobs])))
          (testing "another ON UPDATE: the reference is made again"
            (let [refusing (assoc-in jobs [:references 0 :on-update] :refuse)
                  {:keys [instant slow]} (plan-of pool [shops orders refusing])]
              (is (= ["ALTER TABLE jobs DROP CONSTRAINT jobs_job__order_id_job__shop_id_fkey"
                      "ALTER TABLE jobs ADD CONSTRAINT jobs_job__order_id_job__shop_id_fkey FOREIGN KEY (job__order_id, job__shop_id) REFERENCES orders (order__id, order__shop_id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT VALID"]
                     (map :sql instant)))
              (is (= 1 (count slow)))
              (migrate-and-wait! pool [shops orders refusing])
              (is (= nothing-to-do (plan-of pool [shops orders refusing])))))
          (testing "a changed trigger is made again; a reference in no map goes before its unique key"
            (let [changed (assoc jobs :references [] :triggers [(assoc fill-trigger :update-of nil)])
                  sqls    (map :sql (:instant (plan-of pool [shops orders changed])))]
              (is (= ["ALTER TABLE jobs DROP CONSTRAINT jobs_job__order_id_job__shop_id_fkey"
                      "ALTER TABLE orders DROP CONSTRAINT orders_order__id_order__shop_id_key"]
                     (filter #(str/includes? % "DROP CONSTRAINT") sqls)))
              (is (= 1 (count (filter #(str/starts-with? % "DROP TRIGGER") sqls))))
              (is (= 1 (count (filter #(str/starts-with? % "CREATE TRIGGER") sqls))))
              (migrate-and-wait! pool [shops orders changed])
              (is (= nothing-to-do (plan-of pool [shops orders changed])))))
          (testing "no trigger in the map: it goes"
            (migrate-and-wait! pool [shops orders plain-jobs])
            (is (= [] (find-rows pool "SELECT tgname FROM pg_trigger WHERE tgrelid = 'jobs'::regclass AND NOT tgisinternal")))))))))

(deftest a-renamed-column-keeps-the-references-names
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [shops orders jobs])
        (let [renamed (-> jobs
                          (assoc :columns [[:job/id :uuid {:primary-key? true :default :random-uuid}]
                                           [:job/parent-id :uuid {:references {:to :order/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                                           [:job/shop-id :uuid]])
                          (assoc :indexes [{:columns [[:job/parent-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}])
                          (assoc-in [:references 0 :columns] [:job/parent-id :job/shop-id])
                          (assoc-in [:triggers 0 :update-of] [:job/parent-id]))
              changes [{:id "r1" :rename-column [:jobs :job/order-id :job/parent-id]}]
              plan    (postgres/make-plan-from-tables pool (declare-with-fill [shops orders renamed]) changes)]
          (is (= [] (:stops plan)))
          (is (some #(= "ALTER TABLE jobs RENAME CONSTRAINT jobs_job__order_id_job__shop_id_fkey TO jobs_job__parent_id_job__shop_id_fkey" (:sql %))
                    (:instant plan)))
          @(:slow-part (postgres/migrate! pool (declare-with-fill [shops orders renamed]) changes test-settings/migrate-options))
          (is (= {:instant [] :slow [] :stops [] :changes-to-mark []}
                 (select-keys (postgres/make-plan-from-tables pool (declare-with-fill [shops orders renamed]) changes) [:instant :slow :stops :changes-to-mark]))))))))

(deftest is-transaction-tells-a-transaction-from-a-pool
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! 1 10000)]
      (try
        (is (false? (postgres/is-transaction? pool)))
        (is (true? (postgres/run-in-transaction! pool {:read-only? true :log test-settings/log} postgres/is-transaction?)))
        (finally (postgres/stop-pool! pool))))))

(def ^:private refusing-reference {:to :order/id :on-delete :refuse :on-update :refuse :checked-at :statement})

(defn- notes-of [reference]
  {:table      :notes
   :columns    [[:note/id :uuid {:primary-key? true :default :random-uuid}]
                [:note/order-id :uuid {:optional? true :references reference}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(deftest a-reference-says-what-it-does-on-delete-update-and-when-checked
  (is (= :groundwork-postgres/bad-reference
         (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders (assoc-in jobs [:columns 1 2 :references :on-delete] :set-null)]) [])
                              (catch Exception e e)))))
      ":set-null on a column that isn't :optional? is refused")
  (is (= :groundwork-postgres/bad-tables
         (:type (ex-data (try (postgres/make-plan-from-tables nil (declare-with-fill [shops orders (update-in jobs [:columns 1 2 :references] dissoc :checked-at)]) [])
                              (catch Exception e e)))))
      "every key written out")
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [shops orders (notes-of refusing-reference)])
        (run-sql! pool "ALTER TABLE notes DROP CONSTRAINT notes_note__order_id_fkey")
        (run-sql! pool "ALTER TABLE notes ADD CONSTRAINT notes_order_fk FOREIGN KEY (note__order_id) REFERENCES orders ON DELETE CASCADE")
        (testing "a key that cascades deletes isn't the map's, which refuses them: made again, proven in the slow part"
          (let [{:keys [instant slow]} (plan-of pool [shops orders (notes-of refusing-reference)])]
            (is (= ["ALTER TABLE notes RENAME CONSTRAINT notes_order_fk TO notes_note__order_id_fkey"
                    "ALTER TABLE notes DROP CONSTRAINT notes_note__order_id_fkey"
                    "ALTER TABLE notes ADD CONSTRAINT notes_note__order_id_fkey FOREIGN KEY (note__order_id) REFERENCES orders (order__id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT VALID"]
                   (map :sql instant)))
            (is (= ["ALTER TABLE notes VALIDATE CONSTRAINT notes_note__order_id_fkey"] (map :sql slow)))))
        (testing "the map's cascade and set null are Postgres's"
          (let [set-null (assoc refusing-reference :on-delete :set-null :on-update :cascade)]
            (migrate-and-wait! pool [shops orders (notes-of set-null)])
            (is (= nothing-to-do (plan-of pool [shops orders (notes-of set-null)])))
            (let [shop  (:shop/id (first (find-rows pool "INSERT INTO shops DEFAULT VALUES RETURNING shop__id")))
                  order (:order/id (first (find-rows pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop "') RETURNING order__id"))))]
              (run-sql! pool (str "INSERT INTO notes (note__order_id) VALUES ('" order "')"))
              (run-sql! pool "DELETE FROM orders")
              (is (= [nil] (map :note/order-id (find-rows pool "SELECT note__order_id FROM notes")))))))
        (testing "checked at commit: changed in place, the catalog only"
          (let [at-commit (assoc refusing-reference :checked-at :commit)
                {:keys [instant slow]} (plan-of pool [shops orders (notes-of at-commit)])]
            (is (= ["ALTER TABLE notes DROP CONSTRAINT notes_note__order_id_fkey"
                    "ALTER TABLE notes ADD CONSTRAINT notes_note__order_id_fkey FOREIGN KEY (note__order_id) REFERENCES orders (order__id) ON DELETE NO ACTION ON UPDATE NO ACTION DEFERRABLE INITIALLY DEFERRED NOT VALID"]
                   (map :sql instant))
                "with other actions too, made again")
            (is (= 1 (count slow)))
            (migrate-and-wait! pool [shops orders (notes-of at-commit)])
            (is (= nothing-to-do (plan-of pool [shops orders (notes-of at-commit)])))
            (testing "a note may name its order before the order is made, in one transaction"
              (postgres/run-in-transaction! pool {:read-only? false :log test-settings/log}
                (fn [transaction]
                  (let [order (random-uuid)
                        shop  (:shop/id (first (find-rows transaction "INSERT INTO shops DEFAULT VALUES RETURNING shop__id")))]
                    (run-sql! transaction (str "INSERT INTO notes (note__order_id) VALUES ('" order "')"))
                    (run-sql! transaction (str "INSERT INTO orders (order__id, order__shop_id) VALUES ('" order "', '" shop "')")))))
              (is (= 2 (count (find-rows pool "SELECT note__id FROM notes")))))
            (is (= ["ALTER TABLE notes ALTER CONSTRAINT notes_note__order_id_fkey NOT DEFERRABLE"]
                   (map :sql (:instant (plan-of pool [shops orders (notes-of refusing-reference)]))))
                "back to each statement: only that changes")))))))

(deftest a-trigger-says-when-it-is-called
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [moved  {:function "public.count_moves" :arguments [] :timing :before :events #{:update} :update-of nil :for-each :row
                      :when "OLD.order__shop_id IS DISTINCT FROM NEW.order__shop_id"}
              counted (assoc orders :triggers [moved])]
          (migrate-and-wait! pool [shops counted])
          (is (= nothing-to-do (plan-of pool [shops counted])))
          (let [[a b] (map :shop/id (find-rows pool "INSERT INTO shops (shop__id) VALUES (DEFAULT), (DEFAULT) RETURNING shop__id"))]
            (postgres/run-in-transaction! pool {:read-only? false :log test-settings/log}
              (fn [transaction]
                (run-sql! transaction (str "INSERT INTO orders (order__shop_id) VALUES ('" a "')"))
                (run-sql! transaction (str "UPDATE orders SET order__shop_id = '" a "'"))
                (run-sql! transaction (str "UPDATE orders SET order__shop_id = '" b "'"))
                (is (= [{:moves/count "1"}] (find-rows transaction "SELECT current_setting('test.moves') AS moves__count"))
                    "called only when the shop changed"))))
          (testing "another condition is another trigger"
            (let [sqls (map :sql (:instant (plan-of pool [shops (assoc-in counted [:triggers 0 :when] nil)])))]
              (is (= 2 (count sqls)))
              (is (str/starts-with? (first sqls) "DROP TRIGGER"))
              (is (not (str/includes? (second sqls) "WHEN"))))))))))

(def ^:private roles
  {:table      :legacy/roles
   :columns    [[:role/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(defn- invitations-of [columns]
  {:table      :invitations
   :columns    columns
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(deftest a-table-a-reference-no-map-names-points-at-can-be-dropped
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool "DROP SCHEMA IF EXISTS legacy CASCADE")
        (migrate-and-wait! pool [roles (invitations-of [[:invitation/id :uuid {:primary-key? true :default :random-uuid}]
                                                        [:invitation/role-id :uuid {:optional? true :references (assoc refusing-reference :to :role/id)}]])])
        (let [invitations (invitations-of [[:invitation/id :uuid {:primary-key? true :default :random-uuid}]
                                           [:invitation/role-id :uuid {:optional? true}]])
              changes     [{:id "d1" :drop-table :legacy/roles :why "roles moved to groundwork-access"}]
              {:keys [instant stops]} (postgres/make-plan-from-tables pool (declare-with-fill [invitations]) changes)]
          (testing "the map no longer names the reference: it goes before the table, in the check and in the statements"
            (is (= [] stops))
            (is (= ["ALTER TABLE invitations DROP CONSTRAINT invitations_invitation__role_id_fkey"
                    "DROP TABLE legacy.roles"]
                   (map :sql instant))))
          @(:slow-part (postgres/migrate! pool (declare-with-fill [invitations]) changes test-settings/migrate-options))
          (is (= {:instant [] :slow [] :stops [] :changes-to-mark []}
                 (select-keys (postgres/make-plan-from-tables pool (declare-with-fill [invitations]) changes) [:instant :slow :stops :changes-to-mark])))
          (is (= [] (find-rows pool "SELECT relname FROM pg_class WHERE relname = 'roles'"))))))))

;; -----------------------------------------------------------------------------
;; A reference that sets only some of its columns to null on delete

(defn- batches-of
  "A batch belongs to a shop and may name one of its orders: a reference
   over both, to the order's shop and id, that does on-delete."
  [on-delete]
  {:table      :batches
   :columns    [[:batch/id       :uuid {:primary-key? true :default :random-uuid}]
                [:batch/shop-id  :uuid]
                [:batch/order-id :uuid {:optional? true}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references [{:columns [:batch/shop-id :batch/order-id] :to [:order/shop-id :order/id]
                 :on-delete on-delete :on-update :cascade :checked-at :statement}]
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private keeps-the-shop {:set-null [:batch/order-id]})

(def ^:private batch-reference "batches_batch__shop_id_batch__order_id_fkey")

(defn- refusal-of
  "What make-plan-from-tables refuses these tables with, before it asks
   Postgres anything: {:type :message}, or nil."
  [tables]
  (try (postgres/make-plan-from-tables nil (declare-with-fill tables) []) nil
       (catch Exception e {:type (:type (ex-data e)) :message (ex-message e)})))

(deftest a-reference-sets-to-null-only-its-own-optional-columns-it-lists
  (let [refused (refusal-of [shops orders (batches-of {:set-null [:order/id]})])]
    (is (= :groundwork-postgres/bad-reference (:type refused)))
    (is (re-find #"sets \[:order/id\] to null on delete, which isn't among its :columns" (:message refused))))
  (let [refused (refusal-of [shops orders (batches-of {:set-null [:batch/shop-id]})])]
    (is (= :groundwork-postgres/bad-reference (:type refused)))
    (is (re-find #"sets \[:batch/shop-id\] to null, which isn't \{:optional\? true\}" (:message refused))))
  (let [refused (refusal-of [shops orders (batches-of {:set-null [:batch/order-id :batch/order-id]})])]
    (is (= :groundwork-postgres/bad-reference (:type refused)))
    (is (re-find #"names a column twice" (:message refused))))
  (let [both-optional (assoc-in (batches-of {:set-null [:batch/order-id :batch/shop-id]}) [:columns 1 2] {:optional? true})
        refused       (refusal-of [shops orders both-optional])]
    (is (= :groundwork-postgres/bad-reference (:type refused)))
    (is (re-find #"sets every one of its :columns to null on delete: say :on-delete :set-null" (:message refused))))
  (is (= :groundwork-postgres/bad-tables (:type (refusal-of [shops orders (batches-of {:set-null []})])))
      "at least one column")
  (is (= :groundwork-postgres/bad-tables (:type (refusal-of [shops orders (batches-of {:set-null [:batch/order-id] :keep [:batch/shop-id]})])))
      "closed: only :set-null")
  (is (= :groundwork-postgres/bad-tables
         (:type (refusal-of [shops (assoc-in orders [:columns 1 2 :references :on-delete] {:set-null [:order/shop-id]})])))
      "a column's reference has one column: all of it is :set-null")
  (is (= :groundwork-postgres/bad-tables
         (:type (refusal-of [shops orders (assoc-in (batches-of :refuse) [:references 0 :on-update] {:set-null [:batch/order-id]})])))
      "on update Postgres sets all or none")
  (is (nil? (:type (refusal-of [shops orders (batches-of keeps-the-shop)])))
      "taken: it goes on to ask Postgres (here no pool)"))

(deftest a-reference-that-sets-some-columns-to-null-is-made-migrated-and-matched
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (testing "made on a fresh database: ON DELETE SET NULL (batch__order_id)"
          (is (some #{(str "ALTER TABLE batches ADD CONSTRAINT " batch-reference " FOREIGN KEY (batch__shop_id, batch__order_id)"
                           " REFERENCES orders (order__shop_id, order__id) ON DELETE SET NULL (batch__order_id) ON UPDATE CASCADE")}
                    (map :sql (:instant (plan-of pool [shops orders (batches-of keeps-the-shop)])))))
          (migrate-and-wait! pool [shops orders (batches-of keeps-the-shop)])
          (is (= nothing-to-do (plan-of pool [shops orders (batches-of keeps-the-shop)])))
          (is (true? (postgres/does-database-match-tables? pool (declare-with-fill [shops orders (batches-of keeps-the-shop)]) [])))
          (is (= [{:confdelsetcols "{3}"}]
                 (find-rows pool (str "SELECT confdelsetcols::text AS confdelsetcols FROM pg_constraint WHERE conname = '" batch-reference "'")))
              "Postgres keeps the third column, batch__order_id"))
        (let [shop  (:shop/id (first (find-rows pool "INSERT INTO shops DEFAULT VALUES RETURNING shop__id")))
              order #(:order/id (first (find-rows pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop "') RETURNING order__id"))))]
          (testing "a deleted order leaves its batches the shop"
            (run-sql! pool (str "INSERT INTO batches (batch__shop_id, batch__order_id) VALUES ('" shop "', '" (order) "')"))
            (run-sql! pool "DELETE FROM orders")
            (is (= [{:batch/shop-id shop :batch/order-id nil}] (find-rows pool "SELECT batch__shop_id, batch__order_id FROM batches"))))
          (testing "another action on delete: made again, NOT VALID, proven in the slow part"
            (let [{:keys [instant slow]} (plan-of pool [shops orders (batches-of :refuse)])]
              (is (= [(str "ALTER TABLE batches DROP CONSTRAINT " batch-reference)
                      (str "ALTER TABLE batches ADD CONSTRAINT " batch-reference " FOREIGN KEY (batch__shop_id, batch__order_id)"
                           " REFERENCES orders (order__shop_id, order__id) ON DELETE NO ACTION ON UPDATE CASCADE NOT VALID")]
                     (map :sql instant)))
              (is (= [(str "ALTER TABLE batches VALIDATE CONSTRAINT " batch-reference)] (map :sql slow))))
            (migrate-and-wait! pool [shops orders (batches-of :refuse)])
            (is (= nothing-to-do (plan-of pool [shops orders (batches-of :refuse)])))
            (run-sql! pool (str "UPDATE batches SET batch__order_id = '" (order) "'"))
            (is (thrown? Exception (run-sql! pool "DELETE FROM orders")) "refused now")
            (let [{:keys [instant slow]} (plan-of pool [shops orders (batches-of keeps-the-shop)])]
              (is (= [(str "ALTER TABLE batches DROP CONSTRAINT " batch-reference)
                      (str "ALTER TABLE batches ADD CONSTRAINT " batch-reference " FOREIGN KEY (batch__shop_id, batch__order_id)"
                           " REFERENCES orders (order__shop_id, order__id) ON DELETE SET NULL (batch__order_id) ON UPDATE CASCADE NOT VALID")]
                     (map :sql instant)))
              (is (= 1 (count slow))))
            (migrate-and-wait! pool [shops orders (batches-of keeps-the-shop)])
            (is (= nothing-to-do (plan-of pool [shops orders (batches-of keeps-the-shop)])))
            (run-sql! pool "DELETE FROM orders")
            (is (= [{:batch/shop-id shop :batch/order-id nil}] (find-rows pool "SELECT batch__shop_id, batch__order_id FROM batches")))))
        (testing "made outside under another name, the same: adopted, the catalog only"
          (run-sql! pool (str "ALTER TABLE batches DROP CONSTRAINT " batch-reference))
          (run-sql! pool (str "ALTER TABLE batches ADD CONSTRAINT batches_order_fk FOREIGN KEY (batch__shop_id, batch__order_id)"
                              " REFERENCES orders (order__shop_id, order__id) ON DELETE SET NULL (batch__order_id) ON UPDATE CASCADE"))
          (is (= {:instant [{:sql (str "ALTER TABLE batches RENAME CONSTRAINT batches_order_fk TO " batch-reference)
                             :why (str "adopt batches_order_fk as " batch-reference ": the same as its map's")}]
                  :slow [] :stops [] :changes-to-mark []}
                 (plan-of pool [shops orders (batches-of keeps-the-shop)])))
          (migrate-and-wait! pool [shops orders (batches-of keeps-the-shop)]))
        (testing "a renamed column: renamed in the list too, the key kept"
          (let [renamed (-> (batches-of {:set-null [:batch/placed-order-id]})
                            (assoc-in [:columns 2 0] :batch/placed-order-id)
                            (assoc-in [:references 0 :columns] [:batch/shop-id :batch/placed-order-id]))
                changes [{:id "r2" :rename-column [:batches :batch/order-id :batch/placed-order-id]}]
                sqls    (map :sql (:instant (postgres/make-plan-from-tables pool (declare-with-fill [shops orders renamed]) changes)))]
            (is (= ["ALTER TABLE batches RENAME COLUMN batch__order_id TO batch__placed_order_id"
                    (str "ALTER TABLE batches RENAME CONSTRAINT " batch-reference " TO batches_batch__shop_id_batch__placed_order_id_fkey")]
                   sqls))
            @(:slow-part (postgres/migrate! pool (declare-with-fill [shops orders renamed]) changes test-settings/migrate-options))
            (is (true? (postgres/does-database-match-tables? pool (declare-with-fill [shops orders renamed]) changes)))))
        (testing "a list of every column Postgres has is :set-null"
          (let [all-optional (-> (batches-of :set-null) (assoc-in [:columns 1 2] {:optional? true}))]
            (run-sql! pool "DROP TABLE batches")
            (migrate-and-wait! pool [shops orders all-optional])
            (run-sql! pool (str "ALTER TABLE batches DROP CONSTRAINT " batch-reference))
            (run-sql! pool (str "ALTER TABLE batches ADD CONSTRAINT " batch-reference " FOREIGN KEY (batch__shop_id, batch__order_id)"
                                " REFERENCES orders (order__shop_id, order__id) ON DELETE SET NULL (batch__order_id, batch__shop_id) ON UPDATE CASCADE"))
            (is (= nothing-to-do (plan-of pool [shops orders all-optional])))))))))

;; -----------------------------------------------------------------------------
;; Triggers on TRUNCATE

(def ^:private truncate-guard
  {:function "public.refuse_truncate" :arguments [] :timing :before :events #{:truncate} :update-of nil
   :for-each :statement :when nil})

(def ^:private guarded-orders (assoc orders :triggers [truncate-guard]))

(deftest a-truncate-trigger-is-for-each-statement
  (let [refused (refusal-of [shops (assoc orders :triggers [(assoc truncate-guard :for-each :row)])])]
    (is (= :groundwork-postgres/bad-trigger (:type refused)))
    (is (re-find #"is on :truncate, so :for-each :statement" (:message refused))))
  (is (= :groundwork-postgres/bad-trigger
         (:type (refusal-of [shops (assoc orders :triggers [(assoc truncate-guard :events #{:insert :truncate} :for-each :row)])])))
      "with other events too")
  (is (= :groundwork-postgres/bad-tables (:type (refusal-of [shops (assoc orders :triggers [(assoc truncate-guard :events #{:select})])])))
      "an event Postgres has no trigger for")
  (is (nil? (:type (refusal-of [shops guarded-orders])))
      "taken: it goes on to ask Postgres (here no pool)"))

(deftest a-table-refuses-truncate-by-a-trigger-it-declares
  (if-not port
    (println "groundwork-postgres.references-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (testing "made on a fresh database, and Postgres's matches the map"
          (migrate-and-wait! pool [shops guarded-orders])
          (is (= nothing-to-do (plan-of pool [shops guarded-orders])))
          (is (true? (postgres/does-database-match-tables? pool (declare-with-fill [shops guarded-orders]) [])))
          (is (= [{:trigger/level "statement" :trigger/timing "before" :trigger/on-truncate true}]
                 (find-rows pool (str "SELECT CASE WHEN tgtype & 1 = 1 THEN 'row' ELSE 'statement' END AS trigger__level,"
                                      " CASE WHEN tgtype & 2 = 2 THEN 'before' ELSE 'after' END AS trigger__timing,"
                                      " tgtype & 32 = 32 AS trigger__on_truncate"
                                      " FROM pg_trigger WHERE tgrelid = 'orders'::regclass AND NOT tgisinternal")))))
        (let [shop (:shop/id (first (find-rows pool "INSERT INTO shops DEFAULT VALUES RETURNING shop__id")))]
          (run-sql! pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop "')"))
          (testing "a TRUNCATE is refused, of the table or through a cascade, and the rows kept"
            (is (thrown-with-msg? Exception #"TRUNCATE of orders is refused" (run-sql! pool "TRUNCATE orders")))
            (is (thrown-with-msg? Exception #"TRUNCATE of orders is refused" (run-sql! pool "TRUNCATE shops CASCADE")))
            (is (= 1 (count (find-rows pool "SELECT order__id FROM orders")))))
          (testing "a DELETE isn't TRUNCATE"
            (run-sql! pool "DELETE FROM orders")
            (is (= [] (find-rows pool "SELECT order__id FROM orders")))))
        (testing "in no map: it goes, and TRUNCATE runs"
          (is (= [:drop] (map #(if (str/starts-with? (:sql %) "DROP TRIGGER orders_trigger_") :drop (:sql %))
                              (:instant (plan-of pool [shops orders])))))
          (migrate-and-wait! pool [shops orders])
          (run-sql! pool "TRUNCATE orders")
          (is (= nothing-to-do (plan-of pool [shops orders]))))
        (testing "migrated onto a table Postgres has: BEFORE TRUNCATE … FOR EACH STATEMENT, in the instant part"
          (let [{:keys [instant slow]} (plan-of pool [shops guarded-orders])]
            (is (re-matches #"CREATE TRIGGER orders_trigger_[0-9a-f]{8} BEFORE TRUNCATE ON orders FOR EACH STATEMENT EXECUTE FUNCTION public\.refuse_truncate\(\)"
                            (:sql (first instant))))
            (is (= 1 (count instant)))
            (is (= [] slow)))
          (migrate-and-wait! pool [shops guarded-orders])
          (is (= nothing-to-do (plan-of pool [shops guarded-orders])))
          (is (thrown-with-msg? Exception #"TRUNCATE of orders is refused" (run-sql! pool "TRUNCATE orders"))))
        (testing "with other events: one trigger, its events in order; another trigger, the old one dropped"
          (let [sqls (map :sql (:instant (plan-of pool [shops (assoc-in guarded-orders [:triggers 0 :events] #{:truncate :insert})])))]
            (is (= 2 (count sqls)))
            (is (str/starts-with? (first sqls) "DROP TRIGGER orders_trigger_"))
            (is (re-find #" BEFORE INSERT OR TRUNCATE ON orders FOR EACH STATEMENT " (second sqls)))))))))
