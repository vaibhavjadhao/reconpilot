# ADR 0011: Frontend stack -- one design system, RTK Query for server state

**Status:** Accepted
**Date:** 2026-09-20

## Context

The backend is usable only through curl. An analyst needs a queue of findings to
work through and a way to move claims along.

## Decisions

**Material UI alone, not Material plus Bootstrap.** They are two complete,
competing design systems: each ships its own reset, grid, typography scale,
spacing scale and component set. Loading both means conflicting resets, two
visual languages in one screen, roughly 250 KB of unused CSS and JS, and a
constant low-grade decision -- "is this a Bootstrap card or a Material card?"
MUI alone was chosen because this is a data-heavy console and its tables,
theming and dark mode are stronger.

**Redux Toolkit with RTK Query**, not plain Redux and not hand-rolled fetching.
Declaring endpoints generates typed hooks, a normalised cache, loading and error
state, and tag-based invalidation. Raising a claim invalidates `Break` and
`Dispute`, so the queue and the dashboard both refresh without either knowing a
claim was raised. Writing that by hand means an action, a reducer and three
state flags per request, which is most of why hand-rolled Redux feels miserable.

**Server state and client state are kept apart.** RTK Query owns what the
backend says; a small `uiSlice` owns what this browser is currently doing --
the active filter, the toast. Mixing them is how caches go stale and filters
get lost on refetch.

**A Vite dev proxy rather than CORS headers.** The browser treats
localhost:5173 and localhost:8080 as different origins and blocks the request
unless the server opts in. Proxying `/api` through Vite means the browser sees a
single origin, so the problem does not arise -- and it mirrors production, where
one reverse proxy serves both.

**The state machine is mirrored in the UI, but is not the authority.** The
client uses `ALLOWED_NEXT` only to decide which buttons to *offer*. The backend
still rejects anything illegal with 409. A UI that hides a button has made an
action inconvenient, not impossible; anyone with curl can still try it.

## The API had to change first

Writing the first screen immediately exposed two defects in
`GET /api/breaks`: it returned raw `Map<String,Object>`, leaking the database's
snake_case naming into the API, and it omitted the break's own id -- so a client
could display a finding but could not act on it.

Replaced with a `BreakView` record carrying the id, camelCase fields, and the
associated `disputeId`/`disputeStatus` so a list can decide whether to offer
"raise a claim" without a request per row.

Worth recording as a general point: **an API designed without a consumer is an
API that technically works and is unusable.** The first real client found both
faults within minutes.

## Consequences

- Frontend and backend are separately deployable; the proxy exists only in dev.
- `types.ts` mirrors the backend records by hand, so a backend change surfaces
  as a TypeScript error rather than as undefined at runtime. Generating them
  from an OpenAPI spec would remove the duplication and is worth doing later.
- MUI 9 removed system props from `Stack` and `Typography`; layout now goes
  through `sx`. Noted because the compiler errors are not obvious.
