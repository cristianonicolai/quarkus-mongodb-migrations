package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

public class FailureLogWriteFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Broken.class))
            .overrideConfigKey("quarkus.mongodb.database", "failure-log-write-failure-test")
            // MongoDB rejects '$' in collection names, so every write to the failure log fails.
            .overrideConfigKey("quarkus.mongodb-migrations.failure-collection", "invalid$failures");

    @Inject
    MongoMigrator migrator;

    @Test
    void failingToRecordDoesNotHideTheMigrationFailure() {
        MigrationException failure = assertThrows(MigrationException.class, migrator::migrate);

        assertEquals("Migration broken failed", failure.getMessage());
        assertEquals("broken on purpose", failure.getCause().getMessage());
    }

    @Migration(id = "broken", order = 1)
    public static class Broken implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            throw new IllegalStateException("broken on purpose");
        }
    }
}
