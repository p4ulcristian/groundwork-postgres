-- A plan part's time limits: for the transaction (param('local') true, what SET LOCAL does) or the session.
SELECT set_config('statement_timeout', param('statement-timeout'), param('local')) AS statement_timeout,
       set_config('lock_timeout', param('lock-timeout'), param('local')) AS lock_timeout
