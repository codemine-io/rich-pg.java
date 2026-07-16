# Agent instructions

## Dependency graph

`docs/dependency-graph.toon` maps module dependencies (TOON format). Consult
it before any cross-module task (review, refactor, design, diagnosis) instead
of grepping cold. Update the relevant rows as part of any change that adds,
removes, or rewires a module — don't defer it to a separate pass. Prefer it
over writing a new ADR for structural/dependency rationale; ADRs remain for
non-structural one-off decisions.
