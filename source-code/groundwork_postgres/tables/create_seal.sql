-- The seal (groundwork_postgres.tables.seal): one row, the fingerprints of
-- what the app declares and of what Postgres has, when a full check last
-- found them matching. Unless the table exists.
CREATE SCHEMA IF NOT EXISTS groundwork_postgres;
CREATE TABLE IF NOT EXISTS groundwork_postgres.seal (
  seal__one      boolean     PRIMARY KEY DEFAULT true CHECK (seal__one),
  seal__tables   text        NOT NULL,
  seal__database text        NOT NULL,
  seal__at       timestamptz NOT NULL DEFAULT now()
)
