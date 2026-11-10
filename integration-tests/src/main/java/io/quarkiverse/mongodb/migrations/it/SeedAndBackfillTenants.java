package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Filters.empty;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.not;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Updates.set;

import java.util.List;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

/**
 * Exercises the field, array and updateEach helpers, and change counting.
 */
@Migration(id = "seed-and-backfill-tenants", order = 3)
public class SeedAndBackfillTenants implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.recordChanges(context.collection("tenants").insertMany(List.of(
                new Document("_id", "acme")
                        .append("members", List.of(
                                new Document("name", "ann").append("status", "INVITED").append("legacyRole", "admin"),
                                new Document("name", "bob")))
                        .append("apiKeys", List.of(new Document("name", "timefold-platform"), new Document("name", "ci"))),
                new Document("_id", "globex")
                        .append("members", List.of(new Document("name", "cid")))
                        .append("apiKeys", List.of())
                        .append("deleted", true))));
        context.setFieldInArrayIfMissing("tenants", "members", "status", "ACTIVE");
        context.setFieldInArray("tenants", "apiKeys", eq("name", "timefold-platform"), "type", "PLATFORM");
        context.setFieldInArray("tenants", "apiKeys", not(eq("name", "timefold-platform")), "type", "USER");
        context.removeFieldInArray("tenants", "members", "legacyRole");
        context.setFieldIfMissing("tenants", "deleted", false);
        context.updateEach("tenants", empty(), include("members"),
                tenant -> set("memberCount", tenant.getList("members", Document.class).size()));
    }
}
