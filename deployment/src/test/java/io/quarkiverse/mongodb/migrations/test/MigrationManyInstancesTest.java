package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Many instances of a first deployment starting at once, against a database with no lock or history yet.
 */
public class MigrationManyInstancesTest {

    private static final String DATABASE = "migration-many-instances-test";
    private static final int INSTANCES = 10;
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger MAX_ACTIVE = new AtomicInteger();
    private static final List<String> RUNS = Collections.synchronizedList(new ArrayList<>());

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CreateTenants.class, BackfillTenants.class, Always.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE);

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void migrationsRunOneAtATimeAndEachPendingOneOnce() throws Exception {
        // Each migrate() call takes the lock with its own owner, like a separate instance.
        ExecutorService executor = Executors.newFixedThreadPool(INSTANCES);
        List<String> ran = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> instances = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                instances.add(executor.submit(() -> {
                    start.await();
                    return migrator.migrate();
                }));
            }
            start.countDown();
            for (Future<List<String>> instance : instances) {
                ran.addAll(instance.get(60, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, MAX_ACTIVE.get(), "no two migrations ran at the same time");
        assertEquals(1, Collections.frequency(ran, "create-tenants"));
        assertEquals(1, Collections.frequency(ran, "backfill-tenants"));
        assertEquals(INSTANCES, Collections.frequency(ran, "always"), "a runAlways migration runs in every instance");
        assertEquals(ran.size(), RUNS.size(), "each run reported by migrate() actually ran, and nothing else did");

        MongoDatabase database = client.getDatabase(DATABASE);
        assertEquals(3, database.getCollection("migrationHistory").countDocuments());
        assertEquals(INSTANCES, database.getCollection("migrationHistory").find(eq("_id", "always")).first()
                .getInteger("runCount"));
        assertEquals(1, database.getCollection("tenants").countDocuments(), "the seed ran once");
        assertEquals(0, database.getCollection("migrationLock").countDocuments(), "the lock is released");
        assertEquals(0, database.getCollection("migrationFailures").countDocuments());
    }

    /**
     * Records the run and the highest number of migrations running at once, and stays running long enough for an
     * overlap to show.
     */
    private static void run(String id) {
        int active = ACTIVE.incrementAndGet();
        MAX_ACTIVE.accumulateAndGet(active, Math::max);
        try {
            RUNS.add(id);
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            ACTIVE.decrementAndGet();
        }
    }

    @Migration(id = "create-tenants", order = 1)
    public static class CreateTenants implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            run("create-tenants");
            context.collection("tenants").insertOne(new Document("_id", "acme"));
        }
    }

    @Migration(id = "backfill-tenants", order = 2)
    public static class BackfillTenants implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            run("backfill-tenants");
            context.setFieldIfMissing("tenants", "plan", "basic");
        }
    }

    @Migration(id = "always", order = 3, runAlways = true)
    public static class Always implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            run("always");
        }
    }
}
