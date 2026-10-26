-- What each view in the folders the maps name reads: a table's column, or another view.
SELECT DISTINCT
       CASE WHEN vn.nspname = 'public' THEN v.relname::text ELSE vn.nspname || '.' || v.relname END AS view_name,
       CASE WHEN tn.nspname = 'public' THEN t.relname::text ELSE tn.nspname || '.' || t.relname END AS read_name,
       t.relkind = 'v'      AS read_is_view,
       a.attname::text      AS column_name
FROM pg_rewrite r
JOIN pg_class v      ON v.oid = r.ev_class AND v.relkind = 'v'
JOIN pg_namespace vn ON vn.oid = v.relnamespace
JOIN pg_depend d     ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid AND d.refclassid = 'pg_class'::regclass
JOIN pg_class t      ON t.oid = d.refobjid AND t.oid <> v.oid
JOIN pg_namespace tn ON tn.oid = t.relnamespace
LEFT JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid AND d.refobjsubid > 0
WHERE vn.nspname = ANY (string_to_array(param('folders'), ','))
