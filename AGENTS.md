# Agent instructions

## Dependency graph

`docs/dependency-graph.mmd` maps module dependencies (Mermaid flowchart).
Consult it before any cross-module task (review, refactor, design, diagnosis)
instead of grepping cold. Update the relevant nodes/edges as part of any
change that adds, removes, or rewires a module — don't defer it to a separate
pass. Prefer it over writing a new ADR for structural/dependency rationale;
ADRs remain for non-structural one-off decisions.
