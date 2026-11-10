package io.quarkiverse.mongodb.migrations.runtime.devui;

import java.time.Instant;
import java.util.List;

import io.quarkiverse.mongodb.migrations.MigrationStatus;
import io.quarkiverse.mongodb.migrations.MigrationsStatus;

/**
 * The migration status as the Dev UI page reads it: times as ISO-8601 strings, and the number of pending migrations.
 *
 * @param migrations every migration, in run order
 * @param pending how many migrations would run on the next migration run
 * @param lock the current lock holder, or {@code null} when the lock is free
 */
public record StatusView(List<Migration> migrations, int pending, Lock lock) {

    static StatusView of(MigrationsStatus status) {
        List<Migration> migrations = status.migrations().stream().map(Migration::of).toList();
        Lock lock = status.lock() == null ? null : Lock.of(status.lock());
        return new StatusView(migrations, status.pending().size(), lock);
    }

    public record Migration(String id, int order, String className, boolean reactive, boolean runAlways, boolean applied,
            String appliedAt, String appliedBy, String lastAppliedAt, Integer runCount, Long documentsChanged,
            Failure lastFailure) {

        static Migration of(MigrationStatus migration) {
            return new Migration(migration.id(), migration.order(), migration.className(), migration.reactive(),
                    migration.runAlways(), migration.applied(), iso(migration.appliedAt()), migration.appliedBy(),
                    iso(migration.lastAppliedAt()), migration.runCount(), migration.documentsChanged(),
                    migration.lastFailure() == null ? null : Failure.of(migration.lastFailure()));
        }
    }

    public record Failure(String failedAt, String instanceId, String errorType, String errorMessage,
            String rollbackErrorMessage, Long documentsChangedBeforeFailure) {

        static Failure of(MigrationStatus.Failure failure) {
            return new Failure(iso(failure.failedAt()), failure.instanceId(), failure.errorType(), failure.errorMessage(),
                    failure.rollbackErrorMessage(), failure.documentsChangedBeforeFailure());
        }
    }

    public record Lock(String instanceId, String acquiredAt, String expiresAt, boolean expired) {

        static Lock of(MigrationsStatus.Lock lock) {
            return new Lock(lock.instanceId(), iso(lock.acquiredAt()), iso(lock.expiresAt()), lock.expired());
        }
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
