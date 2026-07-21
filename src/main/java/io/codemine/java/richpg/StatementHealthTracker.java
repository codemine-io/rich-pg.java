package io.codemine.java.richpg;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per statement class, whether the most recent real execution surfaced integrational drift
 * (see {@link IntegrationalDriftClassifier}). Presence in the set means broken; absence means
 * either "never executed" or "last known execution succeeded" &mdash; both collapse to the same
 * state, since neither implies anything different for the overall health verdict, and it makes
 * recovery free: a later successful execution simply removes the class from the set.
 */
final class StatementHealthTracker {

  private final Set<Class<?>> brokenStatementClasses = ConcurrentHashMap.newKeySet();

  /** Marks {@code statementClass} broken: its most recent real execution surfaced drift. */
  void markBroken(Class<?> statementClass) {
    brokenStatementClasses.add(statementClass);
  }

  /** Marks {@code statementClass} healthy: its most recent real execution succeeded. */
  void markHealthy(Class<?> statementClass) {
    brokenStatementClasses.remove(statementClass);
  }

  /**
   * Whether any statement class is currently marked broken.
   *
   * @return {@code true} if no statement class is currently marked broken
   */
  boolean isHealthy() {
    return brokenStatementClasses.isEmpty();
  }

  /**
   * A snapshot of the statement classes currently marked broken.
   *
   * @return a snapshot of the statement classes currently marked broken
   */
  Set<Class<?>> brokenStatementClasses() {
    return Set.copyOf(brokenStatementClasses);
  }
}
