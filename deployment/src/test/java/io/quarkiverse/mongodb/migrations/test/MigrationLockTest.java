package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.runtime.MigrationLock;
import io.quarkiverse.mongodb.migrations.runtime.MongodbMigrationsConfig;
import io.quarkus.test.QuarkusExtensionTest;

public class MigrationLockTest {

    private static final Duration LEASE = Duration.ofMinutes(1);

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.mongodb.database", "migration-lock-test");

    @Inject
    MongoClient client;

    @Inject
    MongodbMigrationsConfig config;

    @Inject
    MongoMigrator migrator;

    @Test
    void onlyOneInstanceHoldsTheLock(TestInfo info) {
        MongoCollection<Document> collection = collection(info);
        try (MigrationLock first = new MigrationLock(collection, LEASE, "$pod-a");
                MigrationLock second = new MigrationLock(collection, LEASE, "pod-b")) {
            assertTrue(first.tryAcquire());
            assertTrue(first.tryAcquire(), "re-acquiring a held lock succeeds");
            assertFalse(second.tryAcquire());
            assertEquals("$pod-a", second.holder(), "a leading $ is stored as a value, not read as a field path");
            assertEquals("$pod-a", collection.find().first().getString("instanceId"));

            first.close();
            assertTrue(second.tryAcquire());
            assertEquals("pod-b", collection.find().first().getString("instanceId"));
            assertFalse(first.renew());
        }
    }

    @Test
    void sharedInstanceIdDoesNotShareTheLock(TestInfo info) {
        MongoCollection<Document> collection = collection(info);
        try (MigrationLock first = new MigrationLock(collection, LEASE, "api-gateway");
                MigrationLock second = new MigrationLock(collection, LEASE, "api-gateway")) {
            assertTrue(first.tryAcquire());
            assertFalse(second.tryAcquire());
        }
    }

    @Test
    void expiredLockCanBeTakenOver(TestInfo info) throws InterruptedException {
        MongoCollection<Document> collection = collection(info);
        try (MigrationLock crashed = new MigrationLock(collection, Duration.ofMillis(500), "pod-a");
                MigrationLock other = new MigrationLock(collection, LEASE, "pod-b")) {
            assertTrue(crashed.tryAcquire());
            assertFalse(other.tryAcquire());

            Thread.sleep(1000);
            assertTrue(other.tryAcquire());
            assertFalse(crashed.renew());
            assertThrows(MigrationException.class, crashed::ensureHeld);
        }
    }

    @Test
    void acquireFailsAfterTimeoutNamingTheHolder(TestInfo info) {
        MongoCollection<Document> collection = collection(info);
        try (MigrationLock holder = new MigrationLock(collection, LEASE, "pod-a");
                MigrationLock waiter = new MigrationLock(collection, LEASE, "pod-b")) {
            holder.acquire(Duration.ofSeconds(1));
            MigrationException failure = assertThrows(MigrationException.class,
                    () -> waiter.acquire(Duration.ofMillis(300)));
            assertTrue(failure.getMessage().contains("held by pod-a"), failure.getMessage());
        }
    }

    @Test
    void simultaneousFirstAcquisitionHasExactlyOneWinner(TestInfo info) throws Exception {
        // Replicas of a first deployment race to create the lock document. Several rounds, to make a real race likely.
        int contenders = 8;
        MongoCollection<Document> collection = collection(info);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            for (int round = 0; round < 20; round++) {
                collection.drop();
                List<MigrationLock> locks = new ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    locks.add(new MigrationLock(collection, LEASE, "pod-" + i));
                }
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (MigrationLock lock : locks) {
                    attempts.add(executor.submit(() -> {
                        start.await();
                        return lock.tryAcquire();
                    }));
                }
                start.countDown();

                int winners = 0;
                for (Future<Boolean> attempt : attempts) {
                    // get() rethrows anything tryAcquire threw, such as an unhandled duplicate key error.
                    if (attempt.get(10, TimeUnit.SECONDS)) {
                        winners++;
                    }
                }
                assertEquals(1, winners, "round " + round);
                assertEquals(1, collection.countDocuments(), "round " + round);
                System.out.println("RACE-HOLDERS " + locks.stream().map(MigrationLock::holder).toList());
                locks.forEach(MigrationLock::close);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void waiterAcquiresSoonAfterTheHolderReleases(TestInfo info) throws Exception {
        MongoCollection<Document> collection = collection(info);
        try (MigrationLock holder = new MigrationLock(collection, LEASE, "pod-a");
                MigrationLock waiter = new MigrationLock(collection, LEASE, "pod-b")) {
            holder.acquire(Duration.ofSeconds(1));
            CompletableFuture<Void> release = CompletableFuture.runAsync(holder::close,
                    CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS));

            long start = System.nanoTime();
            waiter.acquire(Duration.ofSeconds(10));
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            release.get(1, TimeUnit.SECONDS);
            assertEquals("pod-b", collection.find().first().getString("instanceId"));
            // Released after 0.5 s, and waiters retry at least once a second.
            assertTrue(waited.compareTo(Duration.ofMillis(400)) > 0, "waited " + waited);
            assertTrue(waited.compareTo(Duration.ofMillis(2500)) < 0, "waited " + waited);
        }
    }

    @Test
    void applicationWithoutMigrationsRunsNothing() {
        assertEquals(List.of(), migrator.migrate());
    }

    @Test
    void instanceIdDefaultsToHostnameOrAStartupId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null) {
            assertEquals(hostname, config.instanceId());
        } else {
            assertFalse(config.instanceId().isBlank());
        }
    }

    private MongoCollection<Document> collection(TestInfo info) {
        return client.getDatabase("migration-lock-test").getCollection(info.getTestMethod().orElseThrow().getName());
    }
}
