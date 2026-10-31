-- A test's guard: a table with this trigger BEFORE TRUNCATE can't be emptied at once.
CREATE OR REPLACE FUNCTION public.refuse_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'TRUNCATE of % is refused', TG_TABLE_NAME;
END $$
