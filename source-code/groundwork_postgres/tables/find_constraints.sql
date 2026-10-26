-- Primary keys, foreign keys, unique keys and checks of the tables in the folders the maps name;
-- a check's condition as Postgres prints it.
SELECT CASE WHEN n.nspname = 'public' THEN c.relname::text ELSE n.nspname || '.' || c.relname END AS table_name,
       con.conname::text          AS constraint_name,
       con.contype::text          AS constraint_type,
       con.convalidated           AS validated,
       con.confupdtype::text      AS on_update,
       con.confdeltype::text      AS on_delete,
       con.condeferrable          AS deferrable,
       con.condeferred            AS deferred,
       con.confmatchtype::text    AS match,
       CASE WHEN con.contype = 'c' THEN pg_get_expr(con.conbin, con.conrelid) END AS condition,
       (SELECT array_agg(a.attname::text ORDER BY k.ord)
        FROM unnest(con.conkey) WITH ORDINALITY k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS column_names,
       CASE WHEN fn.nspname = 'public' THEN fc.relname::text ELSE fn.nspname || '.' || fc.relname END AS foreign_table,
       (SELECT array_agg(a.attname::text ORDER BY k.ord)
        FROM unnest(con.confkey) WITH ORDINALITY k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum) AS foreign_column_names
FROM pg_constraint con
JOIN pg_class c     ON c.oid = con.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_class fc ON fc.oid = con.confrelid
LEFT JOIN pg_namespace fn ON fn.oid = fc.relnamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ',')) AND NOT (n.nspname = 'groundwork_postgres' AND c.relname = 'changes_done')
