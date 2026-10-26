-- The views in the folders the maps name, but an extension's: Postgres's printed definition and options.
SELECT CASE WHEN n.nspname = 'public' THEN c.relname::text ELSE n.nspname || '.' || c.relname END AS view_name,
       pg_get_viewdef(c.oid)                                AS definition,
       coalesce(array_to_string(c.reloptions, ','), '')     AS options
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ',')) AND c.relkind = 'v'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
