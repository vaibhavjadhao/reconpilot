# ADR 0001: Record architecture decisions

**Status:** Accepted
**Date:** 2026-09-20

## Context

Architectural decisions carry reasoning that is invisible in the code itself.
Six months later the "why" is gone, and the team (or a future maintainer)
re-litigates settled questions or, worse, breaks an invariant they did not know
existed.

## Decision

Every significant architectural decision gets a numbered ADR in `docs/adr/`,
recording the context, the decision, and the consequences -- explicitly
including what was given up.

## Consequences

- Reasoning survives beyond the memory of whoever made the decision.
- Reversing a decision requires a new ADR superseding the old one, which forces
  the tradeoff to be re-examined rather than silently discarded.
- Small ongoing writing cost.
