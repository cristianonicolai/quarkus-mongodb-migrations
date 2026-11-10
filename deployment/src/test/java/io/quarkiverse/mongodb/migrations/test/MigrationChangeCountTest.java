package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MigrationsStatus;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

public class MigrationChangeCountTest {

    private static final String DATABASE = "migration-change-count-test";
    private static volatile boolean reportAlways = true;
    private static volatile boolean failPartial = true;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(SeedAndBackfill.class, Unreported.class, ReactiveInsert.class,
                    Always.class, Partial.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE);

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void recordsTheDocumentsEachMigrationReportsAsChanged() {
        MongoDatabase database = client.getDatabase(DATABASE);

        assertThrows(MigrationException.class, migrator::migrate);
        MongoCollection<Document> history = database.getCollection("migrationHistory");
        // 3 inserted + 2 renamed by the helper + 1 updated + 1 deleted.
        assertEquals(7L, documentsChanged(history, "seed-and-backfill"));
        assertFalse(history.find(eq("_id", "unreported")).first().containsKey("documentsChanged"),
                "a migration that reports nothing has no count, rather than 0");
        assertEquals(2L, documentsChanged(history, "reactive-insert"));
        assertEquals(5L, documentsChanged(history, "always"));

        Document failure = database.getCollection("migrationFailures").find(eq("migrationId", "partial")).first();
        assertEquals(4L, failure.get("documentsChangedBeforeFailure", Number.class).longValue(),
                "the changes left behind by a failed non-transactional migration");

        MigrationsStatus status = migrator.status();
        assertEquals(7L, status.migrations().get(0).documentsChanged());
        assertNull(status.migrations().get(1).documentsChanged());
        assertEquals(4L, status.migrations().get(4).lastFailure().documentsChangedBeforeFailure());

        reportAlways = false;
        failPartial = false;
        assertEquals(List.of("always", "partial"), migrator.migrate());
        assertFalse(history.find(eq("_id", "always")).first().containsKey("documentsChanged"),
                "a run that reports nothing doesn't keep the previous run's count");
        assertEquals(0L, documentsChanged(history, "partial"), "reporting 0 is kept as 0");
    }

    private static long documentsChanged(MongoCollection<Document> history, String id) {
        return history.find(eq("_id", id)).first().get("documentsChanged", Number.class).longValue();
    }

    @Migration(id = "seed-and-backfill", order = 1)
    public static class SeedAndBackfill implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            MongoCollection<Document> users = context.collection("users");
            context.recordChanges(users.insertMany(List.of(
                    new Document("_id", 1).append("mail", "a@example.com"),
                    new Document("_id", 2).append("mail", "b@example.com"),
                    new Document("_id", 3))));
            context.renameField("users", "mail", "email");
            context.recordChanges(users.updateOne(eq("_id", 3), set("email", "c@example.com")));
            context.recordChanges(users.deleteOne(eq("_id", 1)));
        }
    }

    @Migration(id = "unreported", order = 2)
    public static class Unreported implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            context.collection("users").updateMany(new Document(), set("checked", true));
        }
    }

    @Migration(id = "reactive-insert", order = 3)
    public static class ReactiveInsert implements ReactiveMongoMigration {

        @Override
        public Uni<Void> apply(ReactiveMigrationContext context) {
            return context.collection("invoices")
                    .insertMany(List.of(new Document("_id", "a"), new Document("_id", "b")))
                    .invoke(context::recordChanges)
                    .replaceWithVoid();
        }
    }

    @Migration(id = "always", order = 4, runAlways = true)
    public static class Always implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            if (reportAlways) {
                context.recordChanges(5);
            }
        }
    }

    @Migration(id = "partial", order = 5)
    public static class Partial implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            if (failPartial) {
                context.recordChanges(4);
                throw new IllegalStateException("partial failure");
            }
            context.recordChanges(0);
        }
    }
}
