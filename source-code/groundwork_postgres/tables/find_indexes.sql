-- The indexes of the tables in the folders the maps name: their plain columns in order, Postgres's
-- printed definition, and whether a constraint of their table (a primary or unique key) is built on them.
SELECT CASE WHEN n.nspname = 'public' THEN t.relname::text ELSE n.nspname || '.' || t.relname END AS table_name,
       i.relname::text  AS index_name,
       ix.indisvalid    AS valid,
       ix.indisunique   AS is_unique,
       ix.indisprimary  AS is_primary,
       EXISTS (SELECT 1 FROM pg_constraint con
               WHERE con.conindid = ix.indexrelid AND con.conrelid = ix.indrelid AND con.contype IN ('p', 'u', 'x')) AS backs_constraint,
       pg_get_indexdef(ix.indexrelid) AS definition,
       (SELECT array_agg(a.attname::text ORDER BY k.ord)
        FROM unnest(ix.indkey::int2[]) WITH ORDINALITY k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum) AS column_names
FROM pg_index ix
JOIN pg_class i     ON i.oid = ix.indexrelid
JOIN pg_class t     ON t.oid = ix.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ',')) AND NOT (n.nspname = 'groundwork_postgres' AND t.relname = 'changes_done')
