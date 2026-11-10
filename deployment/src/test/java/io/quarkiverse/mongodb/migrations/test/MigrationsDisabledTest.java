package io.quarkiverse.mongodb.migrations.test;

import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The application side of a Kubernetes init job: migrations are disabled in the pods even with migrate-at-start.
 */
public class MigrationsDisabledTest {

    private static final String DATABASE = "migrations-disabled-test";
    private static final List<String> RUNS = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Recorded.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true")
            .overrideConfigKey("quarkus.mongodb-migrations.enabled", "false");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void doesNotMigrateAtStartupButCanStillBeCalled() {
        assertEquals(List.of(), RUNS);
        assertFalse(collectionExists(client.getDatabase(DATABASE), "migrationHistory"));

        assertEquals(List.of("recorded"), migrator.migrate());
        assertEquals(List.of("recorded"), RUNS);
    }

    @Migration(id = "recorded", order = 1)
    public static class Recorded implements MongoMigration {
        @Override
        public void apply() {
            RUNS.add("recorded");
        }
    }
}
