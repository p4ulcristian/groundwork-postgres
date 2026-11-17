(ns groundwork-postgres.tables.seal
  "The seal: proof that a full check found Postgres matching what the app
   declares, kept so the next check need not build the plan again.

   A full check (make-plan-from-tables, its probe of every table) takes
   minutes on a remote Postgres: every table, check and index is made again
   on an empty temporary table, one round trip after another. The seal is
   two fingerprints, written by migrate as the owner once a plan comes back
   empty:

     tables   — what the app declares (its maps, extensions, functions and
                views, files read), its changes, and groundwork-postgres's own
                files: everything the plan is made from on the app's side
     database — what Postgres has (the catalog and objects of the folders
                the plan reads, the changes done, Postgres's version):
                everything the plan reads on the database's side

   A check makes both fingerprints again from what is there now (a dozen
   catalog queries, no probe). Both equal the seal's: the full check would
   find the same, so it isn't run. Either differs, or there is no seal: the
   full check runs, as before the seal. It sees the shape of the database,
   never its rows.

   SHA-256 over the data in one order (canonical): maps and sets sorted,
   vectors as they are. Data that doesn't print as data (a function in a
   map) gives no fingerprint, and then no seal is written or trusted."
  (:require
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [groundwork-postgres.queries.postgres :as sql])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(defn- order-by-printed [xs]
  (sort-by pr-str xs))

(defn make-canonical
  "The data in one order: every map a sorted vector of [key value], every
   set a sorted vector, by how each prints. Two equal values give the same
   canonical value, whatever order they were built in."
  [data]
  (walk/postwalk (fn [x]
                   (cond
                     (map? x)        (into [:map] (order-by-printed (map vec x)))
                     (set? x)        (into [:set] (order-by-printed x))
                     (map-entry? x)  (vec x)
                     :else           x))
                 data))

(defn- is-plain-data?
  "Whether every value in it prints the same each time: collections of
   text, numbers, keywords, symbols, booleans, characters, uuids, instants
   and nil. A function or an atom prints as its place in memory."
  [data]
  (every? #(or (coll? %) (nil? %) (string? %) (number? %) (keyword? %) (symbol? %)
               (boolean? %) (char? %) (uuid? %) (inst? %))
          (tree-seq coll? seq data)))

(defn make-fingerprint
  "SHA-256 of the canonical data, in hex; nil when something in it isn't
   plain data (a function, an atom), which can't be compared."
  [data]
  (when (is-plain-data? data)
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes (pr-str (make-canonical data)) StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(def library-files
  "groundwork-postgres's own files on the classpath: a plan depends on them
   as much as on the maps, so a new version of the library never trusts an
   old seal. Every file under source-code/groundwork_postgres
   (seal-test checks the list against the folder)."
  ["groundwork_postgres/api.clj"
   "groundwork_postgres/queries/listen.clj"
   "groundwork_postgres/queries/metrics.clj"
   "groundwork_postgres/queries/names.clj"
   "groundwork_postgres/queries/params.clj"
   "groundwork_postgres/queries/pool_lines.clj"
   "groundwork_postgres/queries/postgres.clj"
   "groundwork_postgres/queries/rows.clj"
   "groundwork_postgres/queries/tables_read.clj"
   "groundwork_postgres/settings.clj"
   "groundwork_postgres/tables/catalog.clj"
   "groundwork_postgres/tables/create_changes_done.sql"
   "groundwork_postgres/tables/create_seal.sql"
   "groundwork_postgres/tables/desired.clj"
   "groundwork_postgres/tables/find_changes_done.sql"
   "groundwork_postgres/tables/find_columns.sql"
   "groundwork_postgres/tables/find_constraints.sql"
   "groundwork_postgres/tables/find_extensions.sql"
   "groundwork_postgres/tables/find_folders.sql"
   "groundwork_postgres/tables/find_function_printed.sql"
   "groundwork_postgres/tables/find_functions.sql"
   "groundwork_postgres/tables/find_indexes.sql"
   "groundwork_postgres/tables/find_policies.sql"
   "groundwork_postgres/tables/find_row_security.sql"
   "groundwork_postgres/tables/find_seal.sql"
   "groundwork_postgres/tables/find_server_version.sql"
   "groundwork_postgres/tables/find_tables.sql"
   "groundwork_postgres/tables/find_triggers.sql"
   "groundwork_postgres/tables/find_view_printed.sql"
   "groundwork_postgres/tables/find_view_reads.sql"
   "groundwork_postgres/tables/find_views.sql"
   "groundwork_postgres/tables/gates.clj"
   "groundwork_postgres/tables/insert_change_done.sql"
   "groundwork_postgres/tables/names.clj"
   "groundwork_postgres/tables/plan.clj"
   "groundwork_postgres/tables/probe.clj"
   "groundwork_postgres/tables/quotes.clj"
   "groundwork_postgres/tables/reset_part_limits.sql"
   "groundwork_postgres/tables/seal.clj"
   "groundwork_postgres/tables/set_part_limits.sql"
   "groundwork_postgres/tables/triggers.clj"
   "groundwork_postgres/tables/types.clj"
   "groundwork_postgres/tables/write_seal.sql"])

(def library-contents
  "Every library file's text, by path; nil when one isn't on the classpath
   (a build that keeps only compiled classes): then no seal is written or
   trusted, and every check is a full one."
  (delay
    (let [found (into {} (for [path library-files :let [url (io/resource path)] :when url] [path (slurp url)]))]
      (when (= (count found) (count library-files)) found))))

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork_postgres/tables/" file-name)))

(def ^:private create-seal         (make-query "create_seal.sql"))
(def ^:private find-seal-row       (make-query "find_seal.sql"))
(def ^:private write-seal          (make-query "write_seal.sql"))
(def ^:private find-version-row    (make-query "find_server_version.sql"))

(def ^:private does-seal-exist
  (sql/make-query-from-sql-text "seal is there" "SELECT to_regclass('groundwork_postgres.seal') IS NOT NULL AS exists"))

(defn find-server-version
  "Postgres's version as a number (server_version_num): a new Postgres may
   print a check or an index another way."
  [pool]
  (:version (sql/find-one-row pool find-version-row {})))

(defn find-seal
  "The seal, {:seal/tables :seal/database :seal/at}, or nil when there is
   none (or no table for it yet)."
  [pool]
  (when (:exists (sql/find-one-row pool does-seal-exist {}))
    (sql/find-one-row pool find-seal-row {})))

(defn make-tables-fingerprint
  "The fingerprint of the app's side: what the plan is made from there, and
   the library's own files. nil when it can't be made."
  [{:keys [desired files changes]}]
  (when-let [contents @library-contents]
    (make-fingerprint {:desired desired :files files :changes changes :library contents})))

(defn make-database-fingerprint
  "The fingerprint of the database's side: what the plan reads there."
  [{:keys [catalog objects changes-done server-version]}]
  (make-fingerprint {:catalog catalog :objects objects :changes-done changes-done :server-version server-version}))

(defn does-seal-match?
  "Whether both fingerprints are made and equal the seal's."
  [seal tables-fingerprint database-fingerprint]
  (boolean (and seal tables-fingerprint database-fingerprint
                (= tables-fingerprint (:seal/tables seal))
                (= database-fingerprint (:seal/database seal)))))

(defn write-seal!
  "Writes the seal (the one row), the table first if it isn't there: the
   owner's, in migrate. Fingerprints that couldn't be made write nothing."
  [pool tables-fingerprint database-fingerprint]
  (when (and tables-fingerprint database-fingerprint)
    (sql/run-statement! pool create-seal {})
    (sql/run-statement! pool write-seal {:seal/tables tables-fingerprint :seal/database database-fingerprint})
    true))

(defn create-seal-table!
  "The seal's table, if it isn't there."
  [pool]
  (sql/run-statement! pool create-seal {}))
