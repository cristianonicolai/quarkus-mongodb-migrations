package io.quarkiverse.mongodb.migrations;

import io.smallrye.mutiny.Uni;

/**
 * A reactive MongoDB migration. Annotate the implementing class with {@link Migration}.
 */
public interface ReactiveMongoMigration {

    /**
     * Applies the migration. The migration is complete when the returned {@link Uni} completes. A failed {@link Uni}
     * fails the migration run, and the migration is not recorded as applied. {@link #rollback(ReactiveMigrationContext)}
     * is then called, unless the migration is {@link Migration#transactional() transactional}: its transaction is
     * aborted instead.
     *
     * @param context the database to migrate, with reactive helpers that work on it
     */
    Uni<Void> apply(ReactiveMigrationContext context);

    /**
     * Undoes the partial changes of a failed {@link #apply(ReactiveMigrationContext)}. Called only when {@code apply}
     * fails, never for migrations that were applied successfully, and never for {@link Migration#transactional()
     * transactional} migrations, whose failed transaction is aborted instead. Does nothing by default.
     *
     * @param context the database to migrate, with reactive helpers that work on it
     */
    default Uni<Void> rollback(ReactiveMigrationContext context) {
        return Uni.createFrom().voidItem();
    }
}
