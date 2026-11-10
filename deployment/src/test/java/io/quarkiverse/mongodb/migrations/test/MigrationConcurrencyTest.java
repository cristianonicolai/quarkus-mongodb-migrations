package io.quarkiverse.mongodb.migrations.test;

import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.runtime.MigrationLock;
import io.quarkus.test.QuarkusExtensionTest;

public class MigrationConcurrencyTest {

    private static final String DATABASE = "migration-concurrency-test";
    private static final Duration LEASE = Duration.ofSeconds(1);
    private static final AtomicInteger RUNS = new AtomicInteger();
    private static final CountDownLatch STARTED = new CountDownLatch(1);

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(SlowMigration.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.lock-lease-duration", "1s");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void concurrentRunsApplyEachMigrationOnceAndKeepTheLockPastTheLease() throws Exception {
        // Each migrate() call takes the lock with its own owner, like two instances starting at the same time.
        // They run on pool threads, not Quarkus threads, which also checks migrate() works from any thread.
        CompletableFuture<List<String>> first = CompletableFuture.supplyAsync(migrator::migrate);
        assertTrue(STARTED.await(10, TimeUnit.SECONDS), () -> "the migration started; first run: " + first);
        CompletableFuture<List<String>> second = CompletableFuture.supplyAsync(migrator::migrate);

        // Past the lease: only background renewal keeps the lock from being taken over.
        Thread.sleep(LEASE.toMillis() + 500);
        MongoDatabase database = client.getDatabase(DATABASE);
        try (MigrationLock probe = new MigrationLock(database.getCollection("migrationLock"), LEASE, "probe")) {
            assertFalse(probe.tryAcquire(), "the lock is still held while the migration runs");
        }

        List<String> ran = new ArrayList<>(first.get(30, TimeUnit.SECONDS));
        ran.addAll(second.get(30, TimeUnit.SECONDS));
        assertEquals(List.of("slow-migration"), ran, "exactly one run applied the migration");
        assertEquals(1, RUNS.get());

        assertTrue(collectionExists(database, "migrationLock"));
        assertEquals(0, database.getCollection("migrationLock").countDocuments(), "the lock is released after the run");
    }

    @Migration(id = "slow-migration", order = 1)
    public static class SlowMigration implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            RUNS.incrementAndGet();
            STARTED.countDown();
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
