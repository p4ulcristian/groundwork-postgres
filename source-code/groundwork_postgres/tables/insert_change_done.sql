-- A change file that has run, or was marked done on a fresh database.
INSERT INTO groundwork_postgres.changes_done (change_done__id) VALUES (param('change-done/id'))
ON CONFLICT (change_done__id) DO NOTHING
