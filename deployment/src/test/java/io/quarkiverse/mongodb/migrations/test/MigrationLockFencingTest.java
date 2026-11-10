package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

/**
 * Transactional migrations commit only while their run holds the lock.
 */
public class MigrationLockFencingTest {

    private static final String DATABASE = "migration-lock-fencing-test";
    private static volatile String stealIn;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(BlockingInsert.class, ReactiveInsert.class, SlowInsert.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            // Renewed every 300 ms, so renewals would happen during the slow migration if they weren't paused.
            .overrideConfigKey("quarkus.mongodb-migrations.lock-lease-duration", "900ms");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @BeforeEach
    void reset() {
        client.getDatabase(DATABASE).drop();
        stealIn = null;
    }

    @Test
    void blockingTransactionAbortsWhenTheLockIsTakenOver() {
        stealIn = "blocking-insert";

        assertThrows(MigrationException.class, migrator::migrate);

        MongoDatabase database = client.getDatabase(DATABASE);
        assertEquals(0, database.getCollection("orders").countDocuments(), "the migration's writes were aborted");
        assertEquals(List.of(), applied(database), "and so was its history entry");
        assertEquals("thief", lock(database).getString("instanceId"), "the new holder keeps the lock");
    }

    @Test
    void reactiveTransactionAbortsWhenTheLockIsTakenOver() {
        stealIn = "reactive-insert";

        assertThrows(MigrationException.class, migrator::migrate);

        MongoDatabase database = client.getDatabase(DATABASE);
        assertEquals(0, database.getCollection("orders").countDocuments(eq("by", "reactive-insert")));
        assertEquals(List.of("blocking-insert"), applied(database), "only the migration before it is applied");
        assertEquals("thief", lock(database).getString("instanceId"));
    }

    @Test
    void transactionLongerThanTheLeaseCommitsWhenNobodyTookOver() {
        assertEquals(List.of("blocking-insert", "reactive-insert", "slow-insert"), migrator.migrate());

        MongoDatabase database = client.getDatabase(DATABASE);
        assertEquals(3, database.getCollection("orders").countDocuments());
        assertEquals(0, database.getCollection("migrationLock").countDocuments(), "the lock is released");
    }

    /**
     * Another instance takes the lock, as if this one had stalled for longer than the lease. Written outside the
     * migration's transaction.
     */
    private static void stealLockIfAskedIn(String migration, MongoDatabase database) {
        if (migration.equals(stealIn)) {
            Document lock = new Document("_id", "migrations")
                    .append("owner", "foreign-owner")
                    .append("instanceId", "thief")
                    .append("acquiredAt", new Date())
                    .append("expiresAt", new Date(System.currentTimeMillis() + 600_000));
            database.getCollection("migrationLock").replaceOne(eq("_id", "migrations"), lock,
                    new ReplaceOptions().upsert(true));
        }
    }

    private static List<String> applied(MongoDatabase database) {
        List<String> applied = new ArrayList<>();
        database.getCollection("migrationHistory").find().forEach(entry -> applied.add(entry.getString("_id")));
        return applied;
    }

    private static Document lock(MongoDatabase database) {
        return database.getCollection("migrationLock").find(eq("_id", "migrations")).first();
    }

    @Migration(id = "blocking-insert", order = 1, transactional = true)
    public static class BlockingInsert implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            context.collection("orders").insertOne(context.session(), new Document("by", "blocking-insert"));
            stealLockIfAskedIn("blocking-insert", context.client().getDatabase(DATABASE));
        }
    }

    @Migration(id = "reactive-insert", order = 2, transactional = true)
    public static class ReactiveInsert implements ReactiveMongoMigration {

        @Override
        public Uni<Void> apply(ReactiveMigrationContext context) {
            return context.collection("orders").insertOne(context.session(), new Document("by", "reactive-insert"))
                    .invoke(() -> stealLockIfAskedIn("reactive-insert", BlockingClient.database()))
                    .replaceWithVoid();
        }
    }

    @Migration(id = "slow-insert", order = 3, transactional = true)
    public static class SlowInsert implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            context.collection("orders").insertOne(context.session(), new Document("by", "slow-insert"));
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The blocking client, for the reactive migration to steal the lock with.
     */
    static final class BlockingClient {
        static MongoDatabase database() {
            return io.quarkus.arc.Arc.container().instance(MongoClient.class).get().getDatabase(DATABASE);
        }
    }
}
