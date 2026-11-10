package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * quarkus.mongodb-migrations.database only says where the history and lock go, not which database migrations change.
 */
public class MigratedDatabaseMissingTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(UsesContext.class, Throwables.class))
            .overrideConfigKey("quarkus.mongodb-migrations.database", "migrated-database-missing-metadata");

    @Inject
    MongoMigrator migrator;

    @Test
    void migrationFailsToStartWithAClearMessage() {
        MigrationException failure = assertThrows(MigrationException.class, migrator::migrate);

        String messages = Throwables.messages(failure);
        assertTrue(messages.contains("Migration uses-context could not start"), messages);
        assertTrue(messages.contains("No database configured for migrations to work on"), messages);
        assertTrue(messages.contains("quarkus.mongodb.database"), messages);
    }

    @Migration(id = "uses-context", order = 1)
    public static class UsesContext implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.createCollection("never-created");
        }
    }
}
