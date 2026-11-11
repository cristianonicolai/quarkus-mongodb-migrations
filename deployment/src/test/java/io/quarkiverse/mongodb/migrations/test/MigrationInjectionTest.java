package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

/**
 * Migrations are CDI beans: they can inject application beans, with field or constructor injection, and a new instance
 * is created and destroyed for each run.
 */
public class MigrationInjectionTest {

    private static final String DATABASE = "migration-injection-test";
    private static final AtomicInteger CREATED = new AtomicInteger();
    private static final AtomicInteger DESTROYED = new AtomicInteger();

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(StatusLabels.class, FieldInjected.class,
                    ConstructorInjected.class, RunsEveryTime.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE);

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void migrationsInjectApplicationBeans() {
        MongoDatabase database = client.getDatabase(DATABASE);
        database.drop();
        CREATED.set(0);
        DESTROYED.set(0);

        assertEquals(List.of("field-injected", "constructor-injected", "runs-every-time"), migrator.migrate());
        assertEquals("Open", status(database, "field-injected"));
        assertEquals("Shipped", status(database, "constructor-injected"));

        assertEquals(List.of("runs-every-time"), migrator.migrate());
        assertEquals(2, CREATED.get(), "a new instance for each run");
        assertEquals(2, DESTROYED.get(), "destroyed after its run");
    }

    private static String status(MongoDatabase database, String id) {
        return database.getCollection("orders").find(eq("_id", id)).first().getString("status");
    }

    @ApplicationScoped
    public static class StatusLabels {
        String label(String code) {
            return switch (code) {
                case "O" -> "Open";
                case "S" -> "Shipped";
                default -> "Unknown";
            };
        }
    }

    @Migration(id = "field-injected", order = 1)
    public static class FieldInjected implements MongoMigration {

        @Inject
        StatusLabels labels;

        @Override
        public void apply(MigrationContext context) {
            context.collection("orders").insertOne(new Document("_id", "field-injected").append("status", labels.label("O")));
        }
    }

    @Migration(id = "constructor-injected", order = 2)
    public static class ConstructorInjected implements ReactiveMongoMigration {

        private final StatusLabels labels;

        @Inject
        ConstructorInjected(StatusLabels labels) {
            this.labels = labels;
        }

        @Override
        public Uni<Void> apply(ReactiveMigrationContext context) {
            return context.collection("orders")
                    .insertOne(new Document("_id", "constructor-injected").append("status", labels.label("S")))
                    .replaceWithVoid();
        }
    }

    @Migration(id = "runs-every-time", order = 3, runAlways = true)
    public static class RunsEveryTime implements MongoMigration {

        RunsEveryTime() {
            CREATED.incrementAndGet();
        }

        @Override
        public void apply(MigrationContext context) {
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }
}
