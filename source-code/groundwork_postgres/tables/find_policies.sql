-- The row policies of the tables in the folders the maps name.
SELECT CASE WHEN schemaname = 'public' THEN tablename::text ELSE schemaname || '.' || tablename END AS table_name,
       policyname::text AS policy_name
FROM pg_policies
WHERE schemaname = ANY (string_to_array(param('folders'), ','))
