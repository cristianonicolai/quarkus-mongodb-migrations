package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Indexes.ascending;

import java.util.List;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

/**
 * Exercises forEachDatabase and forEachCollection on per-tenant databases.
 */
@Migration(id = "index-tenant-event-collections", order = 6)
public class IndexTenantEventCollections implements MongoMigration {

    static final List<String> TENANT_DATABASES = List.of("it-tenant-acme", "it-tenant-globex");

    @Override
    public void apply(MigrationContext context) {
        for (String tenant : TENANT_DATABASES) {
            context.client().getDatabase(tenant).getCollection("run-events").insertOne(new Document("runId", 1));
            context.client().getDatabase(tenant).getCollection("settings").insertOne(new Document("_id", "settings"));
        }
        context.forEachDatabase(name -> name.startsWith("it-tenant-"), tenant -> tenant.forEachCollection(
                name -> name.endsWith("-events"),
                collection -> tenant.createIndex(collection, "run_idx", ascending("runId"))));
        context.forEachDatabase(name -> name.startsWith("it-tenant-"),
                tenant -> tenant.setFieldIfMissing("settings", "version", 1));
    }
}
