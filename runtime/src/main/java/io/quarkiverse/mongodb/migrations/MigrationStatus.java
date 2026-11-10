package io.quarkiverse.mongodb.migrations;

import java.time.Instant;

/**
 * The state of one migration, as returned by {@link MongoMigrator#status()}. Times come from the MongoDB server
 * clock.
 *
 * @param applied whether the migration is recorded in the history
 * @param appliedAt when it first ran, or {@code null} if it never did
 * @param appliedBy the instance id of its first run
 * @param lastAppliedAt when it last ran; differs from {@code appliedAt} only for {@code runAlways} migrations
 * @param runCount how many times it ran, or {@code null} if it never did
 * @param documentsChanged the documents its latest run reported as changed, or {@code null} if it reported none
 * @param lastFailure its latest recorded failure, only when that happened after its latest successful run
 */
public record MigrationStatus(String id, int order, String className, boolean reactive, boolean runAlways,
        boolean transactional, boolean applied, Instant appliedAt, String appliedBy, Instant lastAppliedAt,
        Integer runCount, Long documentsChanged, Failure lastFailure) {

    /**
     * Whether the next migration run would run this migration: it hasn't been applied, or it runs every time.
     */
    public boolean pending() {
        return !applied || runAlways;
    }

    /**
     * A failed attempt, from the failure collection.
     *
     * @param rollbackErrorMessage why the rollback failed too, or {@code null} if it succeeded
     * @param documentsChangedBeforeFailure the documents reported as changed before the failure, or {@code null}
     */
    public record Failure(Instant failedAt, String instanceId, String errorType, String errorMessage,
            String rollbackErrorMessage, Long documentsChangedBeforeFailure) {
    }
}
