package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkiverse.mongodb.migrations.MigrationException;
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
