package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Indexes.ascending;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The way the documentation suggests testing a migration: migrate up to just before it, seed data in the old shape,
 * then run it and check the result.
 * <p>
 * The tests share one database and one migration history, and the first one expects every migration to be pending
 * when it starts. Keep the other tests from running migrations, or it depends on the order the tests run in.
 */
public class MigrationTestingSupportTest {

    private static final String DATABASE = "migration-testing-support-test";

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CreateOrders.class, BackfillItemQuantity.class,
                    AddOrderStatus.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE);

    @Inject
    MongoMigrator migrator;

    @Test
    void migrateToRunsMigrationsUpToTheGivenOneSoOldDataCanBeSeeded() {
        assertEquals(List.of("create-orders", "backfill-item-quantity", "add-order-status"), migrator.pending());

        assertEquals(List.of("create-orders"), migrator.migrateTo("create-orders"));
        assertEquals(List.of("backfill-item-quantity", "add-order-status"), migrator.pending());

        MigrationContext context = migrator.context();
        assertTrue(context.indexExists("orders", "customer_idx"));
        context.collection("orders").insertOne(new Document("_id", 1).append("items", List.of(
                new Document("sku", "book").append("quantity", 2),
                new Document("sku", "pen"))));

        assertEquals(List.of("backfill-item-quantity"), migrator.migrateTo("backfill-item-quantity"));
        Document order = context.collection("orders").find().first();
        assertEquals(List.of(2, 1), order.getList("items", Document.class).stream()
                .map(item -> item.getInteger("quantity")).toList());
        assertFalse(order.containsKey("status"), "later migrations didn't run");
        assertEquals(1L, migrator.status().migration("backfill-item-quantity").documentsChanged());

        assertEquals(List.of(), migrator.migrateTo("create-orders"), "already applied");

        // Running a migration again changes nothing: it is safe to retry.
        MigrationContext again = migrator.context();
        new BackfillItemQuantity().apply(again);
        assertEquals(OptionalLong.of(0), again.documentsChanged());

        assertEquals(List.of("add-order-status"), migrator.migrate());
        assertEquals(List.of(), migrator.pending());
    }

    @Test
    void migrateToAnUnknownMigrationFails() {
        MigrationException failure = assertThrows(MigrationException.class, () -> migrator.migrateTo("unknown"));

        assertEquals("No migration with id unknown", failure.getMessage());
    }

    @Test
    void contextsWorkOnTheDatabaseMigrationsUse() {
        assertEquals(DATABASE, migrator.context().database().getName());
        assertEquals(DATABASE, migrator.reactiveContext().database().getName());
    }

    @Migration(id = "create-orders", order = 1)
    public static class CreateOrders implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.createCollection("orders");
            context.createIndex("orders", "customer_idx", ascending("customerId"));
        }
    }

    @Migration(id = "backfill-item-quantity", order = 2)
    public static class BackfillItemQuantity implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.setFieldInArrayIfMissing("orders", "items", "quantity", 1);
        }
    }

    @Migration(id = "add-order-status", order = 3)
    public static class AddOrderStatus implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.setFieldIfMissing("orders", "status", "open");
        }
    }
}
