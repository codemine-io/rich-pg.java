package io.codemine.java.richpg;

import java.util.Set;

/**
 * The result of {@link Session#healthCheck()}: the active probe's cached reachability result,
 * combined with the passive per-statement-class integrational-drift signal gathered from real
 * statement executions (see {@link StatementHealthTracker}). The two signals detect different
 * failure classes &mdash; an unreachable/saturated database vs. a drifted schema or codec contract
 * &mdash; and are combined into one overall verdict by {@link #healthy()}.
 *
 * @param probeHealthy whether the most recent active probe (a pooled {@code select 1}) succeeded
 * @param brokenStatementClasses statement classes whose most recent real execution surfaced
 *     integrational drift
 */
public record HealthStatus(boolean probeHealthy, Set<Class<?>> brokenStatementClasses) {

  /** Defensively copies {@code brokenStatementClasses} so the record is immutable. */
  public HealthStatus {
    brokenStatementClasses = Set.copyOf(brokenStatementClasses);
  }

  /**
   * The overall readiness verdict.
   *
   * @return {@code true} only if the probe succeeded and no statement class shows drift
   */
  public boolean healthy() {
    return probeHealthy && brokenStatementClasses.isEmpty();
  }
}
