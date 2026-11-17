-- The seal, written over the one before.
INSERT INTO groundwork_postgres.seal (seal__one, seal__tables, seal__database, seal__at)
VALUES (true, param('seal/tables'), param('seal/database'), now())
ON CONFLICT (seal__one) DO UPDATE
SET seal__tables = EXCLUDED.seal__tables, seal__database = EXCLUDED.seal__database, seal__at = EXCLUDED.seal__at
