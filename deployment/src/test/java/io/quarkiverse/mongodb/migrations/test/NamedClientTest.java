package io.quarkiverse.mongodb.migrations.test;

import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkus.mongodb.MongoClientName;
import io.quarkus.test.QuarkusExtensionTest;

public class NamedClientTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Noop.class))
            .overrideConfigKey("quarkus.mongodb.database", "named-client-app")
            .overrideConfigKey("quarkus.mongodb.metadata.database", "named-client-metadata")
            .overrideConfigKey("quarkus.mongodb-migrations.mongo-client-name", "metadata")
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true");

    @Inject
    @MongoClientName("metadata")
    MongoClient metadataClient;

    @Inject
    MongoClient defaultClient;

    @Test
    void storesHistoryWithTheNamedClientAndItsDatabase() {
        assertEquals("noop", metadataClient.getDatabase("named-client-metadata").getCollection("migrationHistory")
                .find().first().getString("_id"));
        assertFalse(collectionExists(defaultClient.getDatabase("named-client-app"), "migrationHistory"));
    }

    @Test
    void migrationsWorkOnTheNamedClientDatabase() {
        assertTrue(collectionExists(metadataClient.getDatabase("named-client-metadata"), "created-by-migration"));
        assertFalse(collectionExists(defaultClient.getDatabase("named-client-app"), "created-by-migration"));
    }

    @Migration(id = "noop", order = 1)
    public static class Noop implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.createCollection("created-by-migration");
        }
    }
}
