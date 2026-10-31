# A table map says references over several columns, and the triggers a library declares

> **Added to** by [A declared trigger may be called on TRUNCATE](2026-10-31-a-declared-trigger-may-be-called-on-truncate.md) (2026-10-31, on main): `:events` may hold `:truncate`, on a trigger `:for-each :statement`.

> **Changed in part** by [A trigger says when it is called](2026-10-23-a-trigger-says-when-it-is-called.md) (2026-10-23, on tidy-up): a declared trigger writes out `:when`, nil or a SQL condition.

> **Changed in part** by [A reference writes out what it does on delete, on update, and when it is checked](2026-10-23-a-reference-writes-out-what-it-does-on-delete-update-and-when-checked.md) (2026-10-23, on tidy-up): a several-column reference writes out `:on-delete` and `:checked-at` too, and `:on-update` takes `:set-null` and `:restrict` as well.

> Since v0.4.5. Adds to [Changes are data, and v1 makes tables, columns, references, enums and indexes](2026-10-22-changes-are-data-and-what-v1-makes.md) (a reference was one column, to a one-column primary key) and [A query knows the tables it reads; a table can call a function on change](2026-10-22-a-query-knows-its-tables-and-postgres-can-be-heard.md) (the change triggers were the only triggers a map could say). Asked for by doorman's [Records under records wait for cellar's matching links](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-records-under-records-wait-for-cellar.md).

**Decision:** a table map writes out two more keys, `[]` for none:

- **`:references`**, references over several columns: `{:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id] :on-update :cascade}`. `:to` names the other table's columns in the same order, its primary key among them; that table gets a unique key on them (named `<table>_<columns>_key`), which Postgres needs to point a reference at them and which holds for every row since the primary key is among them. `:on-update` is `:cascade` (Postgres carries a change of the other row's columns to this row, as its own referential action, past the gates) or `:refuse`; a delete of the other row is refused while a row points at it, as with a column's `:references`. A one-column reference stays the column's `:references`; one written here is refused. On a table with rows the reference is made `NOT VALID` and proven in the slow part; when its target's unique key is new on a table Postgres has, both wait for it in the slow part. A reference that changes (other columns, another `:on-update`) is dropped and made again; one in no map, and a changed one, are dropped before any unique key they rest on.
- **`:triggers`**, triggers a library on top (or the app) declares on its table: `{:function "doorman.fill_1a2b3c4d" :arguments [] :timing :before :events #{:insert :update} :update-of [:job/order-id] :for-each :row}`, every key written out; `:update-of` nil for any column, and only with `:update` among the events. Each is named by a hash of its SQL (`<table>_trigger_<hash>`), made and dropped like the change triggers: one no map names is dropped. The function is the declarer's to make, before migrate.

A column's references and unique keys are kept as column lists in the plan and the catalog, so a renamed column renames the constraints named after it, one column or several. `is-transaction?` tells a transaction from a pool, for a function that must run after its caller's transaction is kept.

**Why:** doorman keeps a child's scope equal to its parent's: the child's parent and carried columns point at the parent's id and columns, the parent's change carried down by Postgres, and a fill on insert. cellar makes the app's tables from their maps and drops what no map names, so a link or a trigger made outside a map is undone by the next migrate. Postgres's own cascade passes the forced gates (referential actions run as the table's owner, the gates not forced on it), which no trigger of a library's could do without a role that passes them.

**The price:** every table map gains two keys (`:references [] :triggers []` on most); a reference over several columns adds a unique key to its target.

**Instead of:**
- cellar making the link and the fill itself from a doorman word: cellar would know doorman
- a parent's change carried down by a trigger: it runs as the asker, held by the children's gates; Postgres's cascade is held by none
- a `:unique-keys` key the parent's map writes: the key exists only for the reference, and with the primary key among its columns it can never fail
- optional keys (unsaid is none): every other key of a map is written out
