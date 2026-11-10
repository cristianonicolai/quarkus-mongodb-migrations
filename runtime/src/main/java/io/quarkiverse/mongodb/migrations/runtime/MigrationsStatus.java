package io.quarkiverse.mongodb.migrations.runtime;

import java.util.List;

/**
 * The state of the application's migrations, as shown in the Dev UI. Times are ISO-8601 strings.
 *
 * @param migrations every migration, in run order
 * @param pending how many migrations would run on the next migration run
 * @param lock the current lock holder, or {@code null} when the lock is free
 */
public record MigrationsStatus(List<Migration> migrations, int pending, Lock lock) {

    /**
     * @param applied whether the migration is recorded in the history
     * @param documentsChanged the documents its latest run reported as changed, or {@code null} if it reported none
     * @param lastFailure the latest recorded failure, only when it happened after the latest successful run
     */
    public record Migration(String id, int order, String className, boolean reactive, boolean runAlways, boolean applied,
            String appliedAt, String appliedBy, String lastAppliedAt, Integer runCount, Long documentsChanged,
            Failure lastFailure) {
    }

    /**
     * @param documentsChangedBeforeFailure the documents reported as changed before the failure, or {@code null}
     */
    public record Failure(String failedAt, String instanceId, String errorType, String errorMessage,
            String rollbackErrorMessage, Long documentsChangedBeforeFailure) {
    }

    /**
     * @param expired whether the lease has run out, so the next instance can take the lock over
     */
    public record Lock(String instanceId, String acquiredAt, String expiresAt, boolean expired) {
    }
}
