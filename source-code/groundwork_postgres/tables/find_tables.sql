-- The tables in the folders (Postgres schemas) the maps name; a table outside
-- public is named folder.table. schema's own changes_done is left out.
SELECT CASE WHEN n.nspname = 'public' THEN c.relname::text ELSE n.nspname || '.' || c.relname END AS table_name
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ',')) AND NOT (n.nspname = 'groundwork_postgres' AND c.relname = 'changes_done') AND c.relkind IN ('r', 'p')
