# A reference writes out what it does on delete, on update, and when it is checked

> **Added to** by [A reference over several columns may set only some of its columns to null on delete](2026-10-31-a-reference-may-set-only-some-of-its-columns-to-null-on-delete.md) (2026-10-31, on main): a several-column reference's `:on-delete` may be `{:set-null [key …]}`, Postgres 15's `ON DELETE SET NULL (column, …)`.

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: ON DELETE on 223 keys, ON UPDATE CASCADE on 75, 3 deferrable). Built on tidy-up, not released.

**What was wrong:** a column's `:references :package/id` meant, unsaid, "refuse the delete, refuse the update, check at the statement". cellar read only a key's update action, so an existing ON DELETE CASCADE key counted as the same as a map that refuses deletes: the map and Postgres disagreed and nothing said so.

**Decision:** a reference is always a map, every key written out, on a column and on the table's several-column `:references` alike:

```clojure
[:job/package-id :uuid {:references {:to :package/id :on-delete :cascade :on-update :refuse :checked-at :commit}}]
[:affiliate-referral/customer-user-id :uuid
 {:optional? true :references {:to :user/id :on-delete :set-null :on-update :cascade :checked-at :statement}}]
```

- `:on-delete` and `:on-update`: `:cascade`, `:set-null`, `:refuse` (NO ACTION) or `:restrict` (they differ once checked at commit). `:set-null` on a column that isn't `:optional?` is refused at the start.
- `:checked-at`: `:statement` or `:commit` (DEFERRABLE INITIALLY DEFERRED).
- cellar reads all three from Postgres and compares them. `:checked-at` changes in place (catalog only); a changed action is dropped and made again NOT VALID, then validated in the slow part.

**Why:** no reference behaves in a way its map doesn't say; the house rule, every value that sets behaviour written out.

**The price:** every table map in every library rewrites its references as maps.

**Instead of:**
- **the bare keyword as a short, safe form, a map only when special**: two forms, and the short one hides three behaviours
- **Iron Rainbow dropping what cellar lacked**: 147 cascades change behaviour and 75 keys are rebuilt

**Settled while building:**

1. **A table's several-column reference takes all four actions on update too**, not only `:cascade` and `:refuse` as before.
2. **What no map says differs**: a key that is `DEFERRABLE INITIALLY IMMEDIATE` is changed in place to the map's `:checked-at`; one with `SET DEFAULT`, or `MATCH FULL`, is made again.
3. **The SQL says both actions always**: `ON DELETE NO ACTION ON UPDATE CASCADE`, and `DEFERRABLE INITIALLY DEFERRED` when checked at commit.
4. **Only `:checked-at` changed is `ALTER CONSTRAINT`**, in the instant part; another action besides is a key made again, checked-at and all.
5. **`:set-null` on a delete or an update needs every column `:optional?`**, a column's and a several-column reference's alike; refused at the start, `:cellar/bad-reference`.
