# Text and names in the SQL groundwork-postgres makes are quoted by PgJDBC

> The user's decision, 2026-10-27 (keep groundwork minimal; take a library only where the problem is one people get subtly wrong, escaping among them, and its cost is small where it lands). Since v0.5.0.

**Decision:** every text literal and every quoted name groundwork-postgres writes into SQL it makes is quoted by PgJDBC's `org.postgresql.core.Utils`, the driver already on the path:

- **a text literal**, `Utils/escapeLiteral` with `standardConformingStrings` true, in single quotes: a column's text `:default`, an `:enum`'s values in its check, a trigger's arguments, a change's id written to `groundwork_postgres.changes_done`;
- **a name**, `Utils/escapeIdentifier`, in double quotes: each part of a view or a function that is dropped (`DROP VIEW`, `DROP FUNCTION`).

Both are in one place, `groundwork-postgres.tables.quotes`, and in the api as `quote-text` and `quote-name`, so a library that makes SQL text (groundwork-access's gates and functions) quotes by the same code instead of a copy of its own.

**It depends on `standard_conforming_strings` being on.** Postgres has had it on by default since 9.1: a backslash in `'…'` is a backslash, only a quote is doubled. A server, a database or a role set with it off (`ALTER DATABASE … SET standard_conforming_strings = off`) reads a backslash in these literals as an escape: `'a\b'` would be `a` and a backspace, `'\'` an unclosed text. groundwork-postgres never turns it off, and an app must not.

**Why:** quoting is the place SQL text goes subtly wrong: the one quote nobody doubled, a backslash read as an escape, a zero byte. Four copies of `(str "'" (replace s "'" "''") "'")` and one of the name quoting lived in groundwork-postgres and groundwork-access, each read by nobody else. PgJDBC's are the driver's own, public in its core package and read and fixed by the people who keep the driver; they cost nothing, as PgJDBC is already a dependency.

**The price:** text or a name with a zero byte (`\u0000`), which Postgres text can't hold, now throws `:groundwork-postgres/not-quotable` naming it, where the copies wrote SQL Postgres then refused. Otherwise the SQL made is the same, character for character. `Utils` is in `org.postgresql.core`, public and stable for years, though not in the JDBC interface; a PgJDBC that moved it would fail the tests.

**Instead of:**
- **the copies, each its own**: right today, but a fifth copy, or a fix in one, is wrong in the others
- **asking Postgres to quote (`quote_literal`, `quote_ident`)**: a round trip for every literal, from code that makes SQL text without a connection (a column's default, a trigger, groundwork-access's sentences)
- **`E'…'` literals with backslashes doubled**: right on either setting, but every literal changes, so every made function, check and default Postgres has would differ from the map's and be made again
