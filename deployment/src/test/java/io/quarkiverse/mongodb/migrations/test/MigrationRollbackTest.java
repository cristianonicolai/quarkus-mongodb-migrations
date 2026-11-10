package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Indexes.ascending;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createIndex;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.dropIndex;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.indexExists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

public class MigrationRollbackTest {

    private static final String DATABASE = "migration-rollback-test";

    private static volatile boolean failReactive;
    private static volatile boolean failBlocking;
    private static volatile boolean failBlockingRollback;
    private static final List<String> ROLLBACKS = new ArrayList<>();

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ReactiveStep.class, BlockingStep.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.instance-id", "rollback-test")
            .overrideConfigKey("quarkus.mongodb-migrations.failure-retention", "7d");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void rollsBackOnlyTheFailedMigration() {
        MongoDatabase database = client.getDatabase(DATABASE);
        // An index left by an earlier retention setting, which the failure log must update to the configured 7 days.
        database.getCollection("migrationFailures").createIndex(ascending("failedAt"),
                new IndexOptions().name("failedAt_ttl").expireAfter(1L, TimeUnit.DAYS));

        failReactive = true;
        MigrationException reactiveFailure = assertThrows(MigrationException.class, migrator::migrate);
        assertTrue(reactiveFailure.getMessage().contains("reactive-step"), reactiveFailure.getMessage());
        assertEquals(List.of("reactive-step"), ROLLBACKS);
        assertEquals(List.of(), applied(database));

        failReactive = false;
        failBlocking = true;
        failBlockingRollback = true;
        MigrationException blockingFailure = assertThrows(MigrationException.class, migrator::migrate);
        assertEquals(List.of("reactive-step", "blocking-step"), ROLLBACKS);
        assertEquals(1, blockingFailure.getSuppressed().length, "the rollback failure is attached");
        assertEquals(List.of("reactive-step"), applied(database), "the earlier migration stays applied");

        failBlocking = false;
        assertEquals(List.of("blocking-step"), migrator.migrate());
        assertTrue(indexExists(database, "items", "name_idx"));

        List<Document> failures = database.getCollection("migrationFailures").find()
                .sort(Sorts.ascending("failedAt")).into(new ArrayList<>());
        assertEquals(2, failures.size(), "one document per failed attempt");

        Document reactive = failures.get(0);
        assertEquals("reactive-step", reactive.getString("migrationId"));
        assertEquals(ReactiveStep.class.getName(), reactive.getString("className"));
        assertEquals("rollback-test", reactive.getString("instanceId"));
        assertTrue(reactive.get("failedAt") instanceof Date);
        Document reactiveError = reactive.get("error", Document.class);
        assertEquals(IllegalStateException.class.getName(), reactiveError.getString("type"));
        assertEquals("reactive failure", reactiveError.getString("message"));
        assertFalse(reactiveError.getString("stackTrace").isBlank());
        assertNull(reactive.get("rollbackError"), "the rollback succeeded");

        Document blocking = failures.get(1);
        assertEquals("blocking-step", blocking.getString("migrationId"));
        assertEquals("blocking failure", blocking.get("error", Document.class).getString("message"));
        assertEquals("rollback failure", blocking.get("rollbackError", Document.class).getString("message"));

        Document ttlIndex = database.getCollection("migrationFailures").listIndexes().into(new ArrayList<>()).stream()
                .filter(index -> "failedAt_ttl".equals(index.getString("name")))
                .findFirst().orElseThrow();
        assertEquals(TimeUnit.DAYS.toSeconds(7), ttlIndex.get("expireAfterSeconds", Number.class).longValue());
    }

    private static List<String> applied(MongoDatabase database) {
        List<String> ids = new ArrayList<>();
        for (Document entry : database.getCollection("migrationHistory").find().sort(Sorts.ascending("order"))) {
            ids.add(entry.getString("_id"));
        }
        return ids;
    }

    @Migration(id = "reactive-step", order = 1)
    public static class ReactiveStep implements ReactiveMongoMigration {

        @Override
        public Uni<Void> apply() {
            return failReactive
                    ? Uni.createFrom().failure(new IllegalStateException("reactive failure"))
                    : Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> rollback() {
            return Uni.createFrom().voidItem().invoke(() -> ROLLBACKS.add("reactive-step"));
        }
    }

    @Migration(id = "blocking-step", order = 2)
    public static class BlockingStep implements MongoMigration {

        @Inject
        MongoClient client;

        @Override
        public void apply() {
            MongoDatabase database = client.getDatabase(DATABASE);
            createIndex(database, "items", "name_idx", ascending("name"));
            if (failBlocking) {
                throw new IllegalStateException("blocking failure");
            }
        }

        @Override
        public void rollback() {
            ROLLBACKS.add("blocking-step");
            dropIndex(client.getDatabase(DATABASE), "items", "name_idx");
            assertFalse(indexExists(client.getDatabase(DATABASE), "items", "name_idx"));
            if (failBlockingRollback) {
                throw new IllegalStateException("rollback failure");
            }
        }
    }
}
