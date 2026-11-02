# The database's provider's extensions are declared, and let be

> The user's decision, 2026-11-02, from Iron Rainbow's production flip: Neon put `pg_session_jwt` 0.4.0 in prod's database (its Data API's JWT functions), owned by Neon's admin, so the database's owner can't drop it; the deploy's migrate stopped at `extension pg_session_jwt is in the database but in no list`. Adds to [Checks, functions, views and extensions are declared, compared and dropped like tables](2026-10-23-checks-functions-views-and-extensions-are-declared-like-tables.md) (its point 12). v0.5.2.

**Decision:** what the app declares has a second list of extensions beside `:extensions`, every key still written out: `:provider-extensions`, the extensions the database's provider put in it and keeps.

```clojure
(postgres/migrate! pool {:tables              [shops orders]
                         :extensions          ["pg_trgm"]          ; the app's: made when missing
                         :provider-extensions ["pg_session_jwt"]   ; Neon's: let be
                         :functions           []
                         :views               []}
                   changes options)
```

- **Never made**: one the database hasn't is no stop and no `CREATE EXTENSION`; the plan is the same as when it is there. The provider may add it later, or never.
- **Never dropped, never a stop when present**: the plan leaves it as it leaves `plpgsql`.
- **Nothing else about it is checked**: its functions and views are an extension's, which groundwork-postgres never reads (point 7); its version is the provider's.
- **An extension is in one list**: a name in both is refused at the start, `:groundwork-postgres/bad-tables`, "an extension is in :extensions or :provider-extensions, not both".
- **Where the list comes from is the caller's**: the same app runs on databases whose providers put different extensions in (production on Neon has `pg_session_jwt`, a development Postgres nothing), so the list is a fact of the place, from its settings, not of the app's code.
- A stop for an extension in no list now names both ways out: add it to `:extensions`, or to `:provider-extensions` when the provider keeps it, or drop it by hand.

**Why:** an extension in no list is a stop because its types may hold data and nobody said whose it is. One the provider keeps has a known owner, and it is not the app: the app can't drop it, and it must not make it on a database whose provider doesn't. Saying so in the declared map keeps "every extension in the database is in a list" true, and the stop still catches one nobody named.

**Instead of:**
- **adding it to `:extensions`**: the app would make `pg_session_jwt` on every database that lacks it (development, stage, tests), where it isn't installed and where nothing wants it; and the next migrate after the provider removes it would put it back
- **dropping it by hand before each deploy**: the owner can't (Neon's admin owns it), and Neon's Data API puts it back
- **no stop for any extension**: one made by hand with data in its types would be adopted unseen
- **the list in the pool's settings**: the pool reaches a database, it doesn't plan one; what the plan compares is what the caller declares
