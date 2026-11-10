package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;

import java.util.List;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

/**
 * Exercises a blocking transactional migration and the write helpers in it.
 */
@Migration(id = "transactional-orders", order = 4, transactional = true)
public class TransactionalOrders implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.recordChanges(context.collection("orders").insertMany(context.session(), List.of(
                new Document("_id", 1).append("state", "open").append("total", 10),
                new Document("_id", 2).append("state", "open").append("total", 20),
                new Document("_id", 3).append("state", "cancelled").append("total", 5))));
        context.updateMany("orders", eq("state", "open"), set("reviewed", true));
        context.deleteMany("orders", eq("state", "cancelled"));
        context.renameField("orders", "state", "status");
    }
}
