# ADR 0003: Persist document snapshots and a search audit in PostgreSQL

**Status:** Accepted

## Context
The backend option requires a persistent database, and the brief leaves open what to store.
The systems of record for documents remain the Sales and Service systems.

## Decision
Store two things in PostgreSQL, with the schema managed by Flyway:
1. `document_snapshot`: the last successful result per VIN and source (metadata and links, not
   files). Replaced atomically on each successful call; read as the fallback in ADR 0002.
2. `search_audit`: one row per search with VIN, correlation id, duration and outcome per source.

## Alternatives
- **Persist nothing / cache only**: fails the brief and gives no fallback or audit.
- **Become a document store (copy the files)**: duplicates the systems of record, with sync and
  data-protection costs the brief does not ask for.
- **Redis cache**: good for speed, but not the right home for an audit trail.

## Consequences
- Snapshots can be stale by design; `fetched_at` records their age. A retention job is a next step.
- Two concurrent refreshes of one VIN can collide on the unique key; the loser rolls back
  harmlessly and the search still succeeds.
- The audit table holds full VINs (personal data when linked to an owner), so it needs a
  retention policy and access control in production.
