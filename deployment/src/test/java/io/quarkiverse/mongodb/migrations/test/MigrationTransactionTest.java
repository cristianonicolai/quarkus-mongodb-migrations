package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Indexes.ascending;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

public class MigrationTransactionTest {

    private static final String DATABASE = "migration-transaction-test";
    private static final List<String> ROLLBACKS = new CopyOnWriteArrayList<>();
    private static volatile boolean failBlocking = true;
    private static volatile boolean failReactive = true;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(BlockingBackfill.class, ReactiveBackfill.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE);

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void failedTransactionalMigrationsLeaveNoChangesAndNoHistory() {
        MongoDatabase database = client.getDatabase(DATABASE);
        database.getCollection("orders").insertOne(new Document("_id", "existing").append("state", "open"));

        MigrationException blockingFailure = assertThrows(MigrationException.class, migrator::migrate);
        assertTrue(blockingFailure.getMessage().contains("blocking-backfill"), blockingFailure.getMessage());
        assertEquals(1, database.getCollection("orders").countDocuments(), "the inserts were aborted");
        assertEquals("open", database.getCollection("orders").find().first().getString("state"),
                "the field rename was aborted");
        assertEquals(List.of(), applied(database));
        assertEquals(List.of(), ROLLBACKS, "rollback is not called: the transaction was aborted instead");
        assertEquals(1, database.getCollection("migrationFailures").countDocuments(), "the failure is still recorded");

        failBlocking = false;
        MigrationException reactiveFailure = assertThrows(MigrationException.class, migrator::migrate);
        assertTrue(reactiveFailure.getMessage().contains("reactive-backfill"), reactiveFailure.getMessage());
        assertEquals(3, database.getCollection("orders").countDocuments(), "the blocking migration committed");
        assertEquals(List.of("blocking-backfill"), applied(database));
        assertEquals(0, database.getCollection("invoices").countDocuments(), "the reactive insert was aborted");
        // Failures are recorded outside the aborted transaction, so they survive it.
        List<String> failedMigrations = database.getCollection("migrationFailures").find()
                .sort(Sorts.ascending("failedAt", "_id"))
                .map(failure -> failure.getString("migrationId") + ": "
                        + failure.get("error", Document.class).getString("message"))
                .into(new ArrayList<>());
        assertEquals(List.of("blocking-backfill: blocking failure", "reactive-backfill: reactive failure"),
                failedMigrations);

        failReactive = false;
        assertEquals(List.of("reactive-backfill"), migrator.migrate());
        assertEquals(1, database.getCollection("invoices").countDocuments());
        assertEquals(List.of("blocking-backfill", "reactive-backfill"), applied(database));
        assertTrue(database.getCollection("migrationHistory").find().first().getBoolean("transactional"));
        assertEquals(List.of(), ROLLBACKS);
    }

    @Test
    void sessionIsOnlyAvailableInTransactionalMigrations() {
        MigrationContext context = new MigrationContext(client, DATABASE);

        MigrationException failure = assertThrows(MigrationException.class, context::session);

        assertTrue(failure.getMessage().contains("transactional = true"), failure.getMessage());
    }

    @Test
    void schemaHelpersRefuseToRunInATransaction() {
        try (ClientSession session = client.startSession()) {
            MigrationContext context = new MigrationContext(client, DATABASE, session);

            MigrationException failure = assertThrows(MigrationException.class,
                    () -> context.createIndex("orders", "state_idx", ascending("state")));

            assertTrue(failure.getMessage().contains("createIndex changes the schema"), failure.getMessage());
            assertFalse(context.indexExists("orders", "state_idx"));
        }
    }

    private static List<String> applied(MongoDatabase database) {
        return database.getCollection("migrationHistory").find().sort(Sorts.ascending("order"))
                .map(entry -> entry.getString("_id")).into(new ArrayList<>());
    }

    @Migration(id = "blocking-backfill", order = 1, transactional = true)
    public static class BlockingBackfill implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            context.collection("orders").insertMany(context.session(), List.of(
                    new Document("_id", "a").append("state", "open"),
                    new Document("_id", "b").append("state", "open")));
            context.renameField("orders", "state", "status");
            if (failBlocking) {
                throw new IllegalStateException("blocking failure");
            }
        }

        @Override
        public void rollback(MigrationContext context) {
            ROLLBACKS.add("blocking-backfill");
        }
    }

    @Migration(id = "reactive-backfill", order = 2, transactional = true)
    public static class ReactiveBackfill implements ReactiveMongoMigration {

        @Override
        public Uni<Void> apply(ReactiveMigrationContext context) {
            return context.collection("invoices").insertOne(context.session(), new Document("_id", "first"))
                    .chain(() -> failReactive
                            ? Uni.createFrom().failure(new IllegalStateException("reactive failure"))
                            : Uni.createFrom().voidItem());
        }

        @Override
        public Uni<Void> rollback(ReactiveMigrationContext context) {
            ROLLBACKS.add("reactive-backfill");
            return Uni.createFrom().voidItem();
        }
    }
}
