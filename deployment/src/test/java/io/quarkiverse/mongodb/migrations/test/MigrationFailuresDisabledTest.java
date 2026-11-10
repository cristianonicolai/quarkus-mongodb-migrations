package io.quarkiverse.mongodb.migrations.test;

import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

public class MigrationFailuresDisabledTest {

    private static final String DATABASE = "migration-failures-disabled-test";

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AlwaysFails.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.record-failures", "false");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void recordsNothingWhenDisabled() {
        assertThrows(MigrationException.class, migrator::migrate);
        assertFalse(collectionExists(client.getDatabase(DATABASE), "migrationFailures"));
    }

    @Migration(id = "always-fails", order = 1)
    public static class AlwaysFails implements MongoMigration {
        @Override
        public void apply() {
            throw new IllegalStateException("always fails");
        }
    }
}
