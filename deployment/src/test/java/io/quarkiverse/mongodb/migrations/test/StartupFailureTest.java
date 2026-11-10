package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkus.test.QuarkusExtensionTest;

public class StartupFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Broken.class))
            .overrideConfigKey("quarkus.mongodb.database", "startup-failure-test")
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true")
            .assertException(failure -> {
                String messages = Throwables.messages(failure);
                assertTrue(messages.contains("Migration broken failed"), messages);
                assertTrue(messages.contains("broken on purpose"), messages);
            });

    @Test
    void failingMigrationStopsTheApplicationFromStarting() {
        // Not invoked: startup fails, and the failure is checked above.
    }

    @Migration(id = "broken", order = 1)
    public static class Broken implements MongoMigration {
        @Override
        public void apply() {
            throw new IllegalStateException("broken on purpose");
        }
    }
}
