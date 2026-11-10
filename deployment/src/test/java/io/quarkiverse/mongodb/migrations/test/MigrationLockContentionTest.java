package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The lock as seen through the migrator, when another instance holds it or takes it over.
 */
public class MigrationLockContentionTest {

    private static final String DATABASE = "migration-lock-contention-test";
    private static final List<String> RUNS = new CopyOnWriteArrayList<>();
    private static volatile boolean stealLock;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(First.class, Second.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.lock-acquire-timeout", "1s");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @BeforeEach
    void reset() {
        client.getDatabase(DATABASE).drop();
        RUNS.clear();
        stealLock = false;
    }

    @Test
    void lockHeldElsewhereFailsAfterTheTimeoutWithoutRunningAnything() {
        holdLockAs(client.getDatabase(DATABASE), "other-pod");

        MigrationException failure = assertThrows(MigrationException.class, migrator::migrate);

        assertTrue(failure.getMessage().contains("held by other-pod"), failure.getMessage());
        assertEquals(List.of(), RUNS);
        assertEquals("other-pod", lock(client.getDatabase(DATABASE)).getString("instanceId"),
                "a lock held by someone else is left alone");
    }

    @Test
    void lockLostMidRunStopsBeforeTheNextMigration() {
        stealLock = true;

        MigrationException failure = assertThrows(MigrationException.class, migrator::migrate);

        assertTrue(failure.getMessage().contains("Lost the migration lock"), failure.getMessage());
        assertEquals(List.of("first"), RUNS, "the second migration did not run");
        MongoDatabase database = client.getDatabase(DATABASE);
        List<String> applied = new ArrayList<>();
        database.getCollection("migrationHistory").find().forEach(entry -> applied.add(entry.getString("_id")));
        assertEquals(List.of("first"), applied, "the finished migration is still recorded");
        assertEquals("thief", lock(database).getString("instanceId"), "the new holder's lock is not released");
    }

    private static void holdLockAs(MongoDatabase database, String instanceId) {
        Document lock = new Document("_id", "migrations")
                .append("owner", "foreign-owner")
                .append("instanceId", instanceId)
                .append("acquiredAt", new Date())
                .append("expiresAt", new Date(System.currentTimeMillis() + 600_000));
        database.getCollection("migrationLock").replaceOne(eq("_id", "migrations"), lock,
                new ReplaceOptions().upsert(true));
    }

    private static Document lock(MongoDatabase database) {
        MongoCollection<Document> collection = database.getCollection("migrationLock");
        return collection.find(eq("_id", "migrations")).first();
    }

    @Migration(id = "first", order = 1)
    public static class First implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            RUNS.add("first");
            if (stealLock) {
                // Another instance takes over, as if this one had stopped renewing for longer than the lease.
                holdLockAs(context.database(), "thief");
            }
        }
    }

    @Migration(id = "second", order = 2)
    public static class Second implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            RUNS.add("second");
        }
    }
}
