package io.quarkiverse.mongodb.migrations;

/**
 * A blocking MongoDB migration. Annotate the implementing class with {@link Migration}.
 */
public interface MongoMigration {

    /**
     * Applies the migration. Throwing an exception fails the migration run: {@link #rollback()} is called, and the
     * migration is not recorded as applied.
     */
    void apply();

    /**
     * Undoes the partial changes of a failed {@link #apply()}. Called only when {@code apply()} fails, never for
     * migrations that were applied successfully. Does nothing by default.
     */
    default void rollback() {
    }
}
