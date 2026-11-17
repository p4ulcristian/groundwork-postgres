# A sealed database is checked by fingerprints

> The user's decision, 2026-11-17, from an Iron Rainbow production ship: of its 6 minutes, the migrate's plan took 81 s and the app start's `does-database-match-tables?` 93 s, the same plan made twice against Neon, every table, check and index made again on a temporary table, one round trip after another.

**Decision:** when a full check finds nothing left to do, migrate writes a seal: two fingerprints, one of the app's side and one of the database's. The next migrate and the next `does-database-match-tables?` make both fingerprints again from what is there now, without the probe; when both equal the seal's, the plan isn't made. Anything different, or no seal, and the full check runs as before.

```clojure
(postgres/migrate! pool declared changes options)
;; first time:  plans, runs, then logs :groundwork-postgres/sealed
;; next time:   logs :groundwork-postgres/seal-stands, plans nothing
(postgres/does-database-match-tables? pool declared changes)
;; seal stands: true at once; otherwise the full plan, as before
```

- **The app's side:** the maps, extensions, functions and views (files read), the changes, and groundwork-postgres's own files. A new version of the library never trusts an old seal.
- **The database's side:** the catalog and objects of the folders the plan reads, the changes done, Postgres's version. The shape of the database, never its rows.
- **Only the owner writes it,** in migrate: after the slow part, a plan that was empty seals at once; one that ran something is made again and seals when it comes back empty. The app's role may read the seal, not write it.
- **A seal never refuses:** a fingerprint that differs only means the full check runs. A hand edit changes the catalog, so it is always checked in full.
- **No setting:** the seal changes how long a check takes, never what it answers.

**Why:** a ship that changes no table spent about three minutes proving what the last ship had proven. The catalog is read in about a dozen queries; the probe makes every piece again.

**The price:** a migrate that ran something makes its plan a second time to seal (on Iron Rainbow, about 80 s once per ship that changes tables; that ship's start is then fast). A build that keeps only compiled classes, without the library's files, never seals. Any new folder or extension anywhere in the database changes the fingerprint and costs one full check.

**Instead of:**
- **a fingerprint written by migrate alone, trusted at start:** a hand edit after the migrate would go unseen
- **the probe in one round trip:** still makes every temporary table at every start, and changes the probe's core
- **no check at start:** the start's refusal of a database that isn't current is the guarantee that new code never runs on old tables
