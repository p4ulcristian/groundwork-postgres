CREATE OR REPLACE FUNCTION public.with_param() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$;
SELECT param('limit')
