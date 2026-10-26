-- The functions in the folders the maps name, but an extension's: each by its folder, name and
-- arguments, with Postgres's printed definition.
SELECT n.nspname::text                         AS folder,
       p.proname::text                         AS function_name,
       pg_get_function_identity_arguments(p.oid) AS arguments,
       pg_get_functiondef(p.oid)               AS definition
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = ANY (string_to_array(param('folders'), ',')) AND p.prokind = 'f'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
