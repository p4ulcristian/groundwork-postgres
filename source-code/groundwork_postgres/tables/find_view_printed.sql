-- How Postgres prints one view: its definition and options.
SELECT pg_get_viewdef(c.oid)                            AS definition,
       coalesce(array_to_string(c.reloptions, ','), '') AS options
FROM pg_class c
WHERE c.oid = to_regclass(param('name'))
