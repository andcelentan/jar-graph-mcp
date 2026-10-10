# 1. Record architecture decisions

Date: 2026-10-10

## Status

Accepted

## Context

The project makes several decisions that are expensive to reverse (graph model, persistence strategy, test strategy,
security) and that are not obvious from the code alone. Without a record, the reasoning behind them is lost and the
same questions get re-litigated.

## Decision

We will use Architecture Decision Records, as described by Michael Nygard in
[Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

- One Markdown file per decision in `docs/adr/`, named `NNNN-title-in-kebab-case.md`, numbered sequentially.
- Sections: Status, Context, Decision, Consequences.
- An accepted ADR is immutable. A changed decision gets a new ADR that supersedes the old one, whose status becomes
  `Superseded by ADR-NNNN`.

## Consequences

- The rationale behind the architecture is versioned next to the code and reviewed in the same pull requests.
- Writing an ADR becomes part of any architecturally significant change.
