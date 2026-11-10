package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createIndex;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.indexExists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

public class MigrationRunTest {

    private static final String DATABASE = "migration-run-test";
    private static final List<String> RUNS = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CountRuns.class, InsertDefaultUser.class, CreateUsersIndex.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true")
            .overrideConfigKey("quarkus.mongodb-migrations.instance-id", "$test-instance");

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

    @Test
    void runsMigrationsInOrderAtStartupAndRecordsThem() {
        assertEquals(List.of("create-users-index", "insert-default-user", "count-runs"), RUNS.subList(0, 3));

        MongoDatabase database = client.getDatabase(DATABASE);
        assertTrue(indexExists(database, "users", "email_idx"));
        assertEquals(1, database.getCollection("users").countDocuments());

        List<String> applied = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        for (Document entry : database.getCollection("migrationHistory").find().sort(Sorts.ascending("order"))) {
            applied.add(entry.getString("_id"));
            classNames.add(entry.getString("className"));
        }
        assertEquals(List.of("create-users-index", "insert-default-user", "count-runs"), applied);
        assertEquals(List.of(CreateUsersIndex.class.getName(), InsertDefaultUser.class.getName(),
                CountRuns.class.getName()), classNames);
    }

    @Test
    void reRunsOnlyRunAlwaysMigrations() {
        MongoCollection<Document> history = client.getDatabase(DATABASE).getCollection("migrationHistory");
        Document before = history.find(eq("_id", "count-runs")).first();

        assertEquals(List.of("count-runs"), migrator.migrate());
        assertEquals(1, client.getDatabase(DATABASE).getCollection("users").countDocuments());

        Document after = history.find(eq("_id", "count-runs")).first();
        assertEquals(before.getDate("appliedAt"), after.getDate("appliedAt"), "the first run time is kept");
        assertEquals(before.getInteger("runCount") + 1, after.getInteger("runCount"));
        assertFalse(after.getDate("lastAppliedAt").before(before.getDate("lastAppliedAt")));

        Document runOnce = history.find(eq("_id", "create-users-index")).first();
        assertEquals(1, runOnce.getInteger("runCount"));
        assertEquals(runOnce.getDate("appliedAt"), runOnce.getDate("lastAppliedAt"));
        // A leading $ checks that the instance id is stored as a value, not read as a field path.
        assertEquals("$test-instance", runOnce.getString("appliedBy"));
        assertEquals("$test-instance", runOnce.getString("lastAppliedBy"));
        assertEquals("$test-instance", after.getString("lastAppliedBy"));
    }

    @Test
    void renamedClassUpdatesHistoryWithoutReRunning() {
        MongoCollection<Document> history = client.getDatabase(DATABASE).getCollection("migrationHistory");
        // Simulate an entry written before the class was renamed and renumbered.
        history.updateOne(eq("_id", "create-users-index"),
                combine(set("className", "com.example.OldCreateUsersIndex"), set("order", 99)));
        Date appliedAt = history.find(eq("_id", "create-users-index")).first().getDate("appliedAt");

        assertEquals(List.of("count-runs"), migrator.migrate());

        Document entry = history.find(eq("_id", "create-users-index")).first();
        assertEquals(CreateUsersIndex.class.getName(), entry.getString("className"));
        assertEquals(1, entry.getInteger("order"));
        assertEquals(appliedAt, entry.getDate("appliedAt"));
    }

    @Migration(id = "create-users-index", order = 1)
    public static class CreateUsersIndex implements MongoMigration {

        @Inject
        MongoClient client;

        @Override
        public void apply() {
            createIndex(client.getDatabase(DATABASE), "users", "email_idx", ascending("email"),
                    new IndexOptions().unique(true));
            RUNS.add("create-users-index");
        }
    }

    @Migration(id = "insert-default-user", order = 2)
    public static class InsertDefaultUser implements ReactiveMongoMigration {

        @Inject
        ReactiveMongoClient client;

        @Override
        public Uni<Void> apply() {
            return client.getDatabase(DATABASE).getCollection("users")
                    .insertOne(new Document("email", "admin@example.com"))
                    .invoke(() -> RUNS.add("insert-default-user"))
                    .replaceWithVoid();
        }
    }

    @Migration(id = "count-runs", order = 3, runAlways = true)
    public static class CountRuns implements MongoMigration {

        @Override
        public void apply() {
            RUNS.add("count-runs");
        }
    }
}
