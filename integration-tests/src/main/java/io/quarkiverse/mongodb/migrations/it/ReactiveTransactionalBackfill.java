package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Filters.empty;
import static com.mongodb.client.model.Updates.set;

import java.util.List;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.smallrye.mutiny.Uni;

/**
 * Exercises a reactive transactional migration, a pipeline update and the reactive updateEach.
 */
@Migration(id = "reactive-transactional-backfill", order = 5, transactional = true)
public class ReactiveTransactionalBackfill implements ReactiveMongoMigration {

    @Override
    public Uni<Void> apply(ReactiveMigrationContext context) {
        return context.updateMany("orders", empty(), List.of(set("totalLabel", new Document("$toString", "$total"))))
                .chain(() -> context.updateEach("orders", empty(), order -> set("doubled", order.getInteger("total") * 2)))
                .replaceWithVoid();
    }
}
