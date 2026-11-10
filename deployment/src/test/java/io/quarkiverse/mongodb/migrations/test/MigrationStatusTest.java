package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.runtime.MigrationLock;
import io.quarkiverse.mongodb.migrations.runtime.MigrationsStatus;
import io.quarkiverse.mongodb.migrations.runtime.MongoMigratorImpl;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The status read that backs the Dev UI page.
 */
public class MigrationStatusTest {

    private static final String DATABASE = "migration-status-test";
    private static volatile boolean failSecond = true;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(First.class, Second.class, Always.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.instance-id", "status-test");

    @Inject
    MongoMigratorImpl migrator;

    @Inject
    MongoClient client;

    @Test
    void reportsAppliedPendingFailedMigrationsAndTheLock() throws InterruptedException {
        MigrationsStatus initial = migrator.status();
        assertEquals(List.of("first", "second", "always"), initial.migrations().stream().map(m -> m.id()).toList());
        assertEquals(3, initial.pending());
        assertTrue(initial.migrations().stream().noneMatch(MigrationsStatus.Migration::applied));
        assertNull(initial.lock(), "the lock is free");

        assertThrows(MigrationException.class, migrator::migrate);
        // A failure written by another instance: its ObjectId sorts as newer, but it failed earlier by server time.
        client.getDatabase(DATABASE).getCollection("migrationFailures").insertOne(
                new Document("_id", new ObjectId(new Date(System.currentTimeMillis() + 3_600_000)))
                        .append("migrationId", "second")
                        .append("failedAt", new Date(System.currentTimeMillis() - 3_600_000))
                        .append("error", new Document("type", "java.lang.IllegalStateException")
                                .append("message", "stale failure")));
        MigrationsStatus afterFailure = migrator.status();
        assertEquals(2, afterFailure.pending(), "the failed migration and the runAlways one");

        MigrationsStatus.Migration first = afterFailure.migrations().get(0);
        assertTrue(first.applied());
        assertEquals("status-test", first.appliedBy());
        assertEquals(1, first.runCount());
        assertNotNull(first.appliedAt());
        assertNull(first.lastFailure());

        MigrationsStatus.Migration second = afterFailure.migrations().get(1);
        assertFalse(second.applied());
        assertEquals("second failed on purpose", second.lastFailure().errorMessage(), "the latest by server time");
        assertEquals(IllegalStateException.class.getName(), second.lastFailure().errorType());
        assertEquals("status-test", second.lastFailure().instanceId());

        failSecond = false;
        migrator.migrate();
        MigrationsStatus fixed = migrator.status();
        assertEquals(1, fixed.pending(), "only the runAlways migration");
        assertTrue(fixed.migrations().get(1).applied());
        assertNull(fixed.migrations().get(1).lastFailure(), "a failure is no longer shown once the migration succeeds");
        assertTrue(fixed.migrations().get(2).runAlways());

        try (MigrationLock lock = new MigrationLock(client.getDatabase(DATABASE).getCollection("migrationLock"),
                Duration.ofMinutes(1), "other-pod")) {
            assertTrue(lock.tryAcquire());
            MigrationsStatus locked = migrator.status();
            assertEquals("other-pod", locked.lock().instanceId());
            assertFalse(locked.lock().expired());
            assertNotNull(locked.lock().expiresAt());
        }

        try (MigrationLock crashed = new MigrationLock(client.getDatabase(DATABASE).getCollection("migrationLock"),
                Duration.ofMillis(200), "crashed-pod")) {
            assertTrue(crashed.tryAcquire());
            Thread.sleep(600);
            MigrationsStatus expired = migrator.status();
            assertEquals("crashed-pod", expired.lock().instanceId());
            assertTrue(expired.lock().expired(), "the server reports a lease that ran out as expired");
        }
    }

    @Migration(id = "first", order = 1)
    public static class First implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
        }
    }

    @Migration(id = "second", order = 2)
    public static class Second implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            if (failSecond) {
                throw new IllegalStateException("second failed on purpose");
            }
        }
    }

    @Migration(id = "always", order = 3, runAlways = true)
    public static class Always implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
        }
    }
}
