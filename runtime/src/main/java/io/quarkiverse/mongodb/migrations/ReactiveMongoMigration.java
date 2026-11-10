package io.quarkiverse.mongodb.migrations;

import io.smallrye.mutiny.Uni;

/**
 * A reactive MongoDB migration. Annotate the implementing class with {@link Migration}.
 */
public interface ReactiveMongoMigration {

    /**
     * Applies the migration. The migration is complete when the returned {@link Uni} completes. A failed {@link Uni}
     * fails the migration run: {@link #rollback()} is called, and the migration is not recorded as applied.
     */
    Uni<Void> apply();

    /**
     * Undoes the partial changes of a failed {@link #apply()}. Called only when {@code apply()} fails, never for
     * migrations that were applied successfully. Does nothing by default.
     */
    default Uni<Void> rollback() {
        return Uni.createFrom().voidItem();
    }
}
