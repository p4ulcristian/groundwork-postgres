-- Which tables in the folders the maps name have row-level security on and forced.
SELECT CASE WHEN n.nspname = 'public' THEN c.relname::text ELSE n.nspname || '.' || c.relname END AS table_name,
       c.relrowsecurity AS row_security,
       c.relforcerowsecurity AS forced_row_security
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ',')) AND c.relkind IN ('r', 'p')
