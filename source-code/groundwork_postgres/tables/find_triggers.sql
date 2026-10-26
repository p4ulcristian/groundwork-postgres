-- The triggers of the tables in the folders the maps name (not Postgres's own).
SELECT CASE WHEN n.nspname = 'public' THEN c.relname::text ELSE n.nspname || '.' || c.relname END AS table_name,
       t.tgname::text AS trigger_name
FROM pg_trigger t
JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE NOT t.tgisinternal
  AND n.nspname = ANY (string_to_array(param('folders'), ','))
