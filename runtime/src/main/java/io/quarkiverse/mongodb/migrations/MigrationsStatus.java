package io.quarkiverse.mongodb.migrations;

import java.time.Instant;
import java.util.List;

/**
 * The state of the application's migrations, as returned by {@link MongoMigrator#status()}.
 *
 * @param migrations every migration, in run order
 * @param lock the current holder of the migration lock, or {@code null} when it is free
 */
public record MigrationsStatus(List<MigrationStatus> migrations, Lock lock) {

    /**
     * The ids of the migrations the next migration run would run, in run order.
     */
    public List<String> pending() {
        return migrations.stream().filter(MigrationStatus::pending).map(MigrationStatus::id).toList();
    }

    /**
     * The migration with the given id.
     *
     * @throws IllegalArgumentException if there is no migration with that id
     */
    public MigrationStatus migration(String id) {
        return migrations.stream().filter(migration -> migration.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No migration with id " + id));
    }

    /**
     * @param expired whether the lease has run out, so the next instance can take the lock over, by the server clock
     */
    public record Lock(String instanceId, Instant acquiredAt, Instant expiresAt, boolean expired) {
    }
}
