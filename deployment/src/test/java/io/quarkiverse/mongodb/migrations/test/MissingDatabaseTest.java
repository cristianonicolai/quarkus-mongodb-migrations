package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkus.test.QuarkusExtensionTest;

public class MissingDatabaseTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Noop.class))
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true")
            .assertException(failure -> {
                String messages = Throwables.messages(failure);
                assertTrue(messages.contains("No database configured for MongoDB migrations"), messages);
                assertTrue(messages.contains("quarkus.mongodb.database"), messages);
            });

    @Test
    void startupFailsWithAClearMessage() {
        // Not invoked: startup fails, and the failure is checked above.
    }

    @Migration(id = "noop", order = 1)
    public static class Noop implements MongoMigration {
        @Override
        public void apply() {
        }
    }
}
