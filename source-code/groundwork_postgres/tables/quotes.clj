(ns groundwork-postgres.tables.quotes
  "Text and names written into SQL that groundwork-postgres (or a library
   on top: groundwork-access's gates and functions) makes, quoted by
   PgJDBC's own Utils, the driver's:

     it's              ->  'it''s'
     may orders/edit   ->  \"may orders/edit\"     (a \" inside doubled)

   A text literal is quoted for standard_conforming_strings on (Postgres's
   default since 9.1): a quote doubled, a backslash kept as it is. A
   server, role or database with it off would read a backslash as an
   escape (decisions/2026-10-27-text-and-names-in-made-sql-are-quoted-by-pgjdbc.md).
   Postgres text holds no zero byte: one throws :groundwork-postgres/not-quotable."
  (:import
   (java.sql SQLException)
   (org.postgresql.core Utils)))

(defn- throw-not-quotable [what text ^SQLException e]
  (throw (ex-info (str "Not " what " Postgres can hold: " (.getMessage e) " " (pr-str text))
                  {:type :groundwork-postgres/not-quotable :text text}
                  e)))

(defn quote-text
  "A SQL text literal: 'it''s'."
  [text]
  (let [text (str text)]
    (try
      (str "'" (Utils/escapeLiteral (StringBuilder.) text true) "'")
      (catch SQLException e (throw-not-quotable "text" text e)))))

(defn quote-name
  "One name (a folder's, a table's, a function's) in double quotes, a
   double quote in it doubled: \"orders\", \"may orders/edit\"."
  [name-text]
  (let [name-text (str name-text)]
    (try
      (str (Utils/escapeIdentifier (StringBuilder.) name-text))
      (catch SQLException e (throw-not-quotable "a name" name-text e)))))
