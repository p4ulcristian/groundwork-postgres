# A drop with nothing to drop is only marked done

> The user's decision, 2026-11-12, from an Iron Rainbow production deploy: two columns were added by their map on one day and dropped by a change two days later, both after production's last deploy. Stage had run each deploy; production took them all at once, never had the columns, and its migrate stopped at `there is no column … to drop`. Changes in part [Changes are data, and v1 makes tables, columns, references, enums and indexes](2026-10-22-changes-are-data-and-what-v1-makes.md). v0.5.4.

**Decision:** a `:drop-column` whose column Postgres hasn't, and a `:drop-table` whose table Postgres hasn't, run nothing and are marked done with the plan's other changes.

```clojure
;; orders has no order__fax in this database
(postgres/make-plan-from-tables pool declared
  [{:id "2026-10-24-drop-order-fax" :drop-column [:orders :order/fax] :why "no faxes since 2019"}])
;; => {:instant [] :slow [] :stops [] :changes-to-mark ["2026-10-24-drop-order-fax"]}
```

- **What a drop is for is that the column or table is gone.** A database where it is gone already, or was never made, is where the drop leaves every other database.
- **A database need not have run every deploy.** A column made by a map in one deploy and dropped by a change in a later one exists only on the databases deployed in between. One deployed less often takes the same list of changes.
- **A misspelt drop still stops.** The column or table it meant stays in Postgres with no map naming it: `orders.order__note is in Postgres but in no map`. The stop names what was left, not the change.
- **A drop still needs its `:why`**, there or not.
- **Renames and type changes are as they were:** a rename whose column Postgres hasn't is a stop; a type change already of the map's type is only marked done.

**Why:** a type change already of the map's type was only marked done from the start, and a fresh database marks every change done: a change says where the database must get to, and a database already there has nothing to run. A drop that stopped on a missing column made the list of changes fit only databases that had run every deploy one by one.

**The price:** a misspelt drop is marked done, and the stop that follows names the column left behind; the fix is a second change with the right name.

**Instead of:**
- **removing the drop from the list:** a change is never removed; a database that still has the column and has not run the change would stop at a column in no map
- **making the column by hand on the database that lacks it:** a hand edit, again at every such column
- **a key on the change saying it may find nothing (`:if-there? true`):** whoever writes a drop can't know which databases skipped the column, so every drop would say it
