-- How Postgres prints one function the probe made in its temporary folder: its arguments and definition.
SELECT pg_get_function_identity_arguments(p.oid) AS arguments,
       pg_get_functiondef(p.oid)                 AS definition
FROM pg_proc p
WHERE p.pronamespace = pg_my_temp_schema() AND p.proname = param('name') AND p.prokind = 'f'
