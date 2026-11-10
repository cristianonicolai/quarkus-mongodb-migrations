package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkus.test.QuarkusExtensionTest;

public class InvalidMigrationsTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(First.class, Second.class, NoInterface.class, BlankId.class))
            .assertException(failure -> {
                String message = messages(failure);
                assertTrue(message.contains("Duplicate migration id same-id in " + First.class.getName() + ", "
                        + Second.class.getName()), message);
                assertTrue(message.contains("Duplicate migration order 1 in " + First.class.getName() + ", "
                        + Second.class.getName()), message);
                assertTrue(message.contains(NoInterface.class.getName() + " must implement exactly one of"), message);
                assertTrue(message.contains(BlankId.class.getName() + " has a blank migration id"), message);
            });

    @Test
    void failsTheBuild() {
        // Not invoked: the build fails, and the failure is checked above.
    }

    private static String messages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Migration(id = "same-id", order = 1)
    public static class First implements MongoMigration {
        @Override
        public void apply() {
        }
    }

    @Migration(id = "same-id", order = 1)
    public static class Second implements MongoMigration {
        @Override
        public void apply() {
        }
    }

    @Migration(id = "no-interface", order = 2)
    public static class NoInterface {
    }

    @Migration(id = " ", order = 3)
    public static class BlankId implements MongoMigration {
        @Override
        public void apply() {
        }
    }
}
