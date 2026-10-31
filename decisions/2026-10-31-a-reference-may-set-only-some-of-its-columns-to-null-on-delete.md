# A reference over several columns may set only some of its columns to null on delete

> The user's decision, 2026-10-31, from Iron Rainbow's port: its references over a workspace and a row of it, (workspace_id, x_id), where deleting the row must null x_id and keep workspace_id (batches twice, shop_categories once). Adds to [A reference writes out what it does on delete, on update, and when it is checked](2026-10-23-a-reference-writes-out-what-it-does-on-delete-update-and-when-checked.md). Built on main, not released.

**Decision:** a table's several-column reference's `:on-delete` may be `{:set-null [key …]}` beside `:cascade`, `:set-null`, `:refuse` and `:restrict`: only the columns listed are set to null when the row it points at is deleted, the others kept. groundwork-postgres writes it as Postgres 15's `ON DELETE SET NULL (column, …)`:

```clojure
{:columns    [:batch/workspace-id :batch/coating-id]
 :to         [:coating/workspace-id :coating/id]
 :on-delete  {:set-null [:batch/coating-id]}
 :on-update  :cascade
 :checked-at :statement}
;; … FOREIGN KEY (batch__workspace_id, batch__coating_id) REFERENCES coatings (coating__workspace_id, coating__id)
;;     ON DELETE SET NULL (batch__coating_id) ON UPDATE CASCADE
```

- The columns listed are the reference's own `:columns`, each once, each `:optional?`, and not every one of them (that is `:on-delete :set-null`); otherwise refused at the start, named, `:groundwork-postgres/bad-reference`. An empty list, or another key in the map, is refused by the table shape, `:groundwork-postgres/bad-tables`.
- groundwork-postgres reads it back from Postgres (pg_constraint's `confdelsetcols`), so a live database that has it matches its map; another list, or none, is another action: the key is made again, `NOT VALID`, then proven in the slow part, like any changed action. A list of every column Postgres has is read as `:set-null`, which acts the same.
- A renamed column is renamed in the list too, on paper, so the key is kept.

**Why:** a workspace's row (a batch) points at another row of the same workspace (its coating) through both columns, so the two stay in one workspace. Deleting the coating must leave the batch in its workspace with no coating; plain `SET NULL` nulls the workspace too, which the batch's `NOT NULL` refuses, and `:refuse` blocks the delete. Postgres does exactly this itself since 15, as the reference's own action, past the gates.

**The price:** the catalog query reads `confdelsetcols`, which Postgres 15 added: groundwork-postgres needs Postgres 15 or newer.

**Instead of:**
- **a trigger that nulls the column before the delete**: a second place that says what the reference does, run as the asker and held by the gates; the map no longer says the behaviour
- **`{:set-null …}` on `:on-update` too**: Postgres takes a column list only on delete
- **on a column's one-column reference too**: its one column is all of them, so the list is `:set-null`; one way to say one thing
- **`:on-delete :set-null` with a separate `:set-null-columns` key**: two keys that mean one action, and a key that must be nil on every other reference
