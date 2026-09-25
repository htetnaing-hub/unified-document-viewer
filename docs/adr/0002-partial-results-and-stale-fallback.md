# ADR 0002: Partial results with stale fallback instead of failing the search

**Status:** Accepted

## Context
The two source systems fail independently. The brief does not say what to return when one of
them is slow or down.

## Decision
- A failed or timed-out source never fails the search. The response has `partial: true` and a
  status per source (`OK`, `TIMEOUT`, `ERROR`).
- For a failed source, serve its **last known documents** from the snapshot store, flagged
  `stale: true` on the source and on each document.
- Only when no source answered **and** nothing is stored, respond **503** with a problem body.
- No retries on the request path.

## Alternatives
- **Fail the whole request**: one system's outage becomes an outage of the feature.
- **Silently return what we have**: users would read "no service history" when the Service
  system was actually down. Hence the explicit per-source status.
- **Retry**: adds latency for a user who is waiting; better handled later by a circuit breaker.

## Consequences
- Users always see the most complete information available, with its freshness made explicit.
- Clients must display `partial` and `stale`; the demo page does.
- Requires persistence (see ADR 0003).
