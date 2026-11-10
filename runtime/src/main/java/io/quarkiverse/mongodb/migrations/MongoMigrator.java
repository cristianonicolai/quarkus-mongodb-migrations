package io.quarkiverse.mongodb.migrations;

import java.util.List;

/**
 * Runs the application's migrations and reports their state. Inject it to run migrations programmatically, for
 * example when {@code quarkus.mongodb-migrations.migrate-at-start} is disabled, or in tests.
 */
public interface MongoMigrator {

    /**
     * Acquires the migration lock, runs all pending migrations in order, and releases the lock.
     *
     * @return the ids of the migrations that ran, in run order
     * @throws MigrationException if the lock cannot be acquired or a migration fails
     */
    List<String> migrate();

    /**
     * Like {@link #migrate()}, but stops after the migration with the given id: only pending migrations up to and
     * including it in run order run. Useful in tests, to bring the database to the state just before a migration,
     * seed it with data in the old shape, and then run that migration.
     *
     * @return the ids of the migrations that ran, in run order; empty if they were all applied already
     * @throws MigrationException if there is no migration with that id, the lock cannot be acquired, or a migration
     *         fails
     */
    List<String> migrateTo(String id);

    /**
     * The state of every migration and of the lock, read without taking the lock.
     */
    MigrationsStatus status();

    /**
     * The ids of the migrations the next {@link #migrate()} would run, in run order.
     */
    default List<String> pending() {
        return status().pending();
    }

    /**
     * A context for the database migrations work on, the same one passed to blocking migrations. Useful in tests, to
     * seed data, check results, or call a migration directly: {@code new MyMigration().apply(migrator.context())}.
     */
    MigrationContext context();

    /**
     * A reactive context for the database migrations work on, the same one passed to reactive migrations.
     */
    ReactiveMigrationContext reactiveContext();
}
