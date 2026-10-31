# A declared trigger may be called on TRUNCATE

> The user's decision, 2026-10-31, from Iron Rainbow's port: its 12 catalog tables refuse TRUNCATE, so a slip can't wipe the catalog. Adds to [A table map says references over several columns, and the triggers a library declares](2026-10-23-a-table-map-says-references-over-several-columns-and-a-librarys-triggers.md). Built on main, not released.

**Decision:** a declared trigger's `:events` may hold `:truncate`, beside `:insert`, `:update` and `:delete`, on a trigger `:for-each :statement`:

```clojure
{:function "catalog.refuse_truncate" :arguments [] :timing :before :events #{:truncate} :update-of nil
 :for-each :statement :when nil}
;; CREATE TRIGGER coatings_trigger_1a2b3c4d BEFORE TRUNCATE ON catalog.coatings
;;   FOR EACH STATEMENT EXECUTE FUNCTION catalog.refuse_truncate()
```

- It is a declared trigger like any other: named by a hash of its SQL, made in the instant part, dropped when no map names it, another one (other events, timing, function) made again with the old one dropped. A database that has it matches its map.
- `:truncate` on a `:for-each :row` trigger is refused at the start, named, `:groundwork-postgres/bad-trigger`: Postgres calls no row trigger on TRUNCATE. With other events it is one trigger, `INSERT OR TRUNCATE`, the events in the order insert, update, delete, truncate.
- The function is the declarer's to make, as every declared trigger's: a guard raises (`RAISE EXCEPTION 'TRUNCATE of % is refused', TG_TABLE_NAME`), and Postgres keeps the rows; a TRUNCATE of another table that cascades to this one is refused too.

**Why:** a DELETE goes row by row through the gates and the references; a TRUNCATE empties a table at once, past both, and a mistyped one in a console takes the catalog with it. Postgres stops it before it starts, in one trigger, and the map says so where the table is declared. groundwork-postgres drops a trigger no map names, so one made by hand would be undone by the next migrate.

**Instead of:**
- **a `:refuse-truncate?` key on the table map**: groundwork-postgres would make a function of its own for one guard, and a table map key for one kind of trigger; the declared trigger already says it
- **revoking TRUNCATE from the app's role**: the owner, a migration or a console still truncates; the table itself says no
- **`:truncate` as a separate kind of trigger, with its own keys**: the same keys say it, `:update-of` nil and `:when` nil
