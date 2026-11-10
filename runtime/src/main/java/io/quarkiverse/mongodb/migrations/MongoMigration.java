package io.quarkiverse.mongodb.migrations;

/**
 * A blocking MongoDB migration. Annotate the implementing class with {@link Migration}.
 */
public interface MongoMigration {

    /**
     * Applies the migration. Throwing an exception fails the migration run, and the migration is not recorded as
     * applied. {@link #rollback(MigrationContext)} is then called, unless the migration is
     * {@link Migration#transactional() transactional}: its transaction is aborted instead.
     *
     * @param context the database to migrate, with helpers that work on it
     */
    void apply(MigrationContext context);

    /**
     * Undoes the partial changes of a failed {@link #apply(MigrationContext)}. Called only when {@code apply} fails,
     * never for migrations that were applied successfully, and never for {@link Migration#transactional()
     * transactional} migrations, whose failed transaction is aborted instead. Does nothing by default.
     *
     * @param context the database to migrate, with helpers that work on it
     */
    default void rollback(MigrationContext context) {
    }
}
