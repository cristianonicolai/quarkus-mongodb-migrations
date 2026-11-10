package io.quarkiverse.mongodb.migrations.test;

import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkus.test.QuarkusExtensionTest;

public class MigrationStorageConfigTest {

    private static final String APP_DATABASE = "storage-config-app";
    private static final String METADATA_DATABASE = "storage-config-metadata";

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Noop.class))
            .overrideConfigKey("quarkus.mongodb.database", APP_DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true")
            .overrideConfigKey("quarkus.mongodb-migrations.database", METADATA_DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.history-collection", "appliedChanges")
            .overrideConfigKey("quarkus.mongodb-migrations.lock-collection", "changeLock");

    @Inject
    MongoClient client;

    @Test
    void storesHistoryAndLockWhereConfigured() {
        MongoDatabase metadata = client.getDatabase(METADATA_DATABASE);
        assertEquals("noop", metadata.getCollection("appliedChanges").find().first().getString("_id"));
        // The lock document is deleted on release, but its collection remains.
        assertTrue(collectionExists(metadata, "changeLock"));

        assertFalse(collectionExists(metadata, "migrationHistory"));
        assertFalse(collectionExists(metadata, "migrationLock"));
        MongoDatabase app = client.getDatabase(APP_DATABASE);
        assertFalse(collectionExists(app, "migrationHistory"));
        assertFalse(collectionExists(app, "migrationLock"));
        assertEquals(0, app.getCollection("appliedChanges").countDocuments());
    }

    @Migration(id = "noop", order = 1)
    public static class Noop implements MongoMigration {
        @Override
        public void apply() {
        }
    }
}
