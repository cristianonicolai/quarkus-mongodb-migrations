package io.quarkiverse.mongodb.migrations;

import java.util.List;

/**
 * Runs the application's migrations. Inject it to run migrations programmatically, for example when
 * {@code quarkus.mongodb-migrations.migrate-at-start} is disabled.
 */
public interface MongoMigrator {

    /**
     * Acquires the migration lock, runs all pending migrations in order, and releases the lock.
     *
     * @return the ids of the migrations that ran, in run order
     * @throws MigrationException if the lock cannot be acquired or a migration fails
     */
    List<String> migrate();
}
