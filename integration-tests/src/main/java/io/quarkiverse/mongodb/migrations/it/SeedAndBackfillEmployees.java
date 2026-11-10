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
@Migration(id = "seed-and-backfill-employees", order = 3)
public class SeedAndBackfillEmployees implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.recordChanges(context.collection("employees").insertMany(List.of(
                new Document("_id", "ann")
                        .append("roles", List.of(
                                new Document("name", "admin").append("status", "PENDING").append("legacyCode", "A1"),
                                new Document("name", "viewer")))
                        .append("devices", List.of(new Document("name", "laptop"), new Document("name", "phone"))),
                new Document("_id", "bob")
                        .append("roles", List.of(new Document("name", "viewer")))
                        .append("devices", List.of())
                        .append("deleted", true))));
        context.setFieldInArrayIfMissing("employees", "roles", "status", "ACTIVE");
        context.setFieldInArray("employees", "devices", eq("name", "laptop"), "type", "COMPANY");
        context.setFieldInArray("employees", "devices", not(eq("name", "laptop")), "type", "PERSONAL");
        context.removeFieldInArray("employees", "roles", "legacyCode");
        context.setFieldIfMissing("employees", "deleted", false);
        context.updateEach("employees", empty(), include("roles"),
                employee -> set("roleCount", employee.getList("roles", Document.class).size()));
    }
}
