package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.empty;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.expr;
import static com.mongodb.client.model.Filters.not;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Updates.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.QuarkusExtensionTest;

public class MigrationContextDataOperationsTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.mongodb.database", "data-operations-test");

    @Inject
    MongoClient client;

    @Inject
    ReactiveMongoClient reactiveClient;

    @Test
    void setFieldAndSetFieldsUpdateMatchingDocuments(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> tenants = context.collection("tenants");
        tenants.insertMany(List.of(
                new Document("_id", 1).append("plan", "basic"),
                new Document("_id", 2).append("plan", "basic"),
                new Document("_id", 3).append("plan", "enterprise")));

        assertEquals(2, context.setField("tenants", eq("plan", "basic"), "tier", "small"));
        assertEquals(3, context.setFields("tenants", empty(), Map.of("deleted", false, "version", 2)));

        assertEquals("small", tenants.find(eq("_id", 1)).first().getString("tier"));
        assertNull(tenants.find(eq("_id", 3)).first().getString("tier"));
        assertEquals(3, tenants.countDocuments(Document.parse("{deleted: false, version: 2}")));
        assertEquals(OptionalLong.of(5), context.documentsChanged());
    }

    @Test
    void setFieldIfMissingOnlyFillsMissingOrNullValues(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> plans = context.collection("plans");
        plans.insertMany(List.of(
                new Document("_id", "kept").append("maxThreads", 8).append("mapsSettings", new Document()),
                new Document("_id", "null").append("maxThreads", null),
                new Document("_id", "missing")));

        assertEquals(2, context.setFieldIfMissing("plans", "maxThreads", 1));
        assertEquals(0, context.setFieldIfMissing("plans", "maxThreads", 1), "safe to run again");
        assertEquals(8, plans.find(eq("_id", "kept")).first().getInteger("maxThreads"));
        assertEquals(1, plans.find(eq("_id", "missing")).first().getInteger("maxThreads"));

        assertEquals(1, context.setFieldIfMissing("plans", exists("mapsSettings"), "mapsSettings.allowTraffic", false));
        assertFalse(plans.find(eq("_id", "missing")).first().containsKey("mapsSettings"), "filtered out");
    }

    @Test
    void setFieldInArrayUpdatesEveryElementAndSkipsDocumentsWithoutTheArray(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> tenants = context.collection("tenants");
        tenants.insertMany(List.of(
                new Document("_id", 1).append("members", List.of(new Document("name", "a"), new Document("name", "b"))),
                new Document("_id", 2).append("members", List.of()),
                new Document("_id", 3)));

        assertEquals(1, context.setFieldInArray("tenants", "members", "notified", true));

        List<Document> members = tenants.find(eq("_id", 1)).first().getList("members", Document.class);
        assertTrue(members.stream().allMatch(member -> member.getBoolean("notified")));
        assertFalse(tenants.find(eq("_id", 3)).first().containsKey("members"));
    }

    @Test
    void setFieldInArrayWithAnElementFilterOnlyUpdatesMatchingElements(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> tenants = context.collection("tenants");
        tenants.insertOne(new Document("_id", 1).append("apiKeys", List.of(
                new Document("name", "timefold-platform"),
                new Document("name", "ci"),
                new Document("name", "local"))));

        context.setFieldInArray("tenants", "apiKeys", eq("name", "timefold-platform"), "type", "PLATFORM");
        context.setFieldInArray("tenants", "apiKeys", not(eq("name", "timefold-platform")), "type", "USER");

        assertEquals(List.of("PLATFORM", "USER", "USER"), tenants.find().first().getList("apiKeys", Document.class)
                .stream().map(key -> key.getString("type")).toList());
    }

    @Test
    void setFieldInArrayIfMissingKeepsExistingValues(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> tenants = context.collection("tenants");
        tenants.insertMany(List.of(
                new Document("_id", 1).append("members", List.of(
                        new Document("name", "a").append("status", "INVITED"),
                        new Document("name", "b"))),
                new Document("_id", 2).append("members", List.of(new Document("name", "c").append("status", "INVITED"))),
                new Document("_id", 3)));

        assertEquals(1, context.setFieldInArrayIfMissing("tenants", "members", "status", "ACTIVE"));
        assertEquals(0, context.setFieldInArrayIfMissing("tenants", "members", "status", "ACTIVE"), "safe to run again");

        assertEquals(List.of("INVITED", "ACTIVE"), tenants.find(eq("_id", 1)).first().getList("members", Document.class)
                .stream().map(member -> member.getString("status")).toList());
        assertEquals("INVITED", tenants.find(eq("_id", 2)).first().getList("members", Document.class).get(0)
                .getString("status"));
    }

    @Test
    void removeFieldInArrayRemovesTheFieldFromElementsThatHaveIt(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> tenants = context.collection("tenants");
        tenants.insertMany(List.of(
                new Document("_id", 1).append("members", List.of(
                        new Document("name", "a").append("legacyRole", "admin"),
                        new Document("name", "b"))),
                new Document("_id", 2).append("members", List.of(new Document("name", "c")))));

        assertEquals(1, context.removeFieldInArray("tenants", "members", "legacyRole"));

        assertTrue(tenants.find(eq("_id", 1)).first().getList("members", Document.class).stream()
                .noneMatch(member -> member.containsKey("legacyRole")));
    }

    @Test
    void elementFiltersMustBeOnElementFields(TestInfo info) {
        MigrationContext context = context(info);

        assertThrows(IllegalArgumentException.class,
                () -> context.setFieldInArray("tenants", "members", expr(new Document("$eq", List.of(1, 1))), "a", 1));
        assertThrows(IllegalArgumentException.class,
                () -> context.setFieldInArray("tenants", "members", empty(), "a", 1));
    }

    @Test
    void updateEachComputesAnUpdatePerDocumentInBatches(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> numbers = context.collection("numbers");
        numbers.insertMany(IntStream.range(0, 2500)
                .mapToObj(i -> new Document("_id", i).append("n", i).append("ignored", "x")).toList());

        long changed = context.updateEach("numbers", empty(), include("n"),
                document -> document.getInteger("n") % 2 == 0 ? set("doubled", document.getInteger("n") * 2) : null);

        assertEquals(1250, changed, "more than one batch, odd numbers skipped");
        assertEquals(1250, numbers.countDocuments(exists("doubled")));
        assertEquals(4000, numbers.find(eq("_id", 2000)).first().getInteger("doubled"));
        assertEquals(OptionalLong.of(1250), context.documentsChanged());
    }

    @Test
    void updateEachNeedsTheIdInTheProjection(TestInfo info) {
        MigrationContext context = context(info);
        context.collection("numbers").insertOne(new Document("_id", 1).append("n", 1));

        assertThrows(IllegalArgumentException.class, () -> context.updateEach("numbers", empty(),
                Document.parse("{_id: 0, n: 1}"), document -> set("n", 2)));
    }

    @Test
    void updateEachWritesToTheDocumentItReadEvenIfTheCallbackChangesTheId(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> numbers = context.collection("numbers");
        numbers.insertMany(List.of(new Document("_id", 1).append("n", 1), new Document("_id", 2).append("n", 2)));

        assertEquals(1, context.updateEach("numbers", eq("_id", 1), document -> {
            document.put("_id", 2);
            return set("touched", true);
        }));

        assertTrue(numbers.find(eq("_id", 1)).first().getBoolean("touched"));
        assertFalse(numbers.find(eq("_id", 2)).first().containsKey("touched"), "not redirected to another document");
    }

    @Test
    void updateEachRejectsProjectionsThatReplaceTheId(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> numbers = context.collection("numbers");
        numbers.insertMany(List.of(new Document("_id", 1).append("n", 1), new Document("_id", 2).append("n", 2)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> context.updateEach(
                "numbers", eq("_id", 1), Document.parse("{_id: {$literal: 2}, n: 1}"), document -> set("touched", true)));

        assertTrue(failure.getMessage().contains("must keep _id"), failure.getMessage());
        assertEquals(0, numbers.countDocuments(exists("touched")), "nothing was written");
        assertEquals(1, context.updateEach("numbers", eq("_id", 1), Document.parse("{_id: 1, n: 1}"),
                document -> set("touched", true)), "including _id is fine");
    }

    @Test
    void reactiveUpdateEachProtectsTheIdTheSameWay(TestInfo info) {
        String database = databaseName(info);
        MongoCollection<Document> numbers = client.getDatabase(database).getCollection("numbers");
        numbers.insertMany(List.of(new Document("_id", 1).append("n", 1), new Document("_id", 2).append("n", 2)));
        ReactiveMigrationContext context = new ReactiveMigrationContext(reactiveClient, database);

        assertThrows(IllegalArgumentException.class, () -> context.updateEach("numbers", eq("_id", 1),
                Document.parse("{_id: {$literal: 2}}"), document -> set("touched", true)).await().indefinitely());
        assertEquals(1L, context.updateEach("numbers", eq("_id", 1), document -> {
            document.put("_id", 2);
            return set("touched", true);
        }).await().indefinitely());

        assertTrue(numbers.find(eq("_id", 1)).first().getBoolean("touched"));
        assertFalse(numbers.find(eq("_id", 2)).first().containsKey("touched"));
    }

    @Test
    void reactiveHelpersBehaveTheSame(TestInfo info) {
        String database = databaseName(info);
        ReactiveMigrationContext context = new ReactiveMigrationContext(reactiveClient, database);
        MongoCollection<Document> tenants = client.getDatabase(database).getCollection("tenants");
        tenants.insertMany(List.of(
                new Document("_id", 1).append("members", List.of(
                        new Document("status", "INVITED"), new Document())),
                new Document("_id", 2)));
        client.getDatabase(database).getCollection("numbers")
                .insertMany(IntStream.range(0, 1500).mapToObj(i -> new Document("_id", i)).toList());

        assertEquals(2L, context.setField("tenants", empty(), "deleted", false).await().indefinitely());
        assertEquals(2L, context.setFieldIfMissing("tenants", "plan", "basic").await().indefinitely());
        assertEquals(1L, context.setFieldInArrayIfMissing("tenants", "members", "status", "ACTIVE").await().indefinitely());
        assertEquals(1500L, context.updateEach("numbers", empty(), document -> set("seen", true)).await().indefinitely());
        assertEquals(OptionalLong.of(2 + 2 + 1 + 1500), context.documentsChanged());
    }

    @Test
    void helpersRunInTheTransactionOfATransactionalContext(TestInfo info) {
        String database = databaseName(info);
        MongoCollection<Document> orders = client.getDatabase(database).getCollection("orders");
        orders.insertMany(List.of(new Document("_id", 1), new Document("_id", 2)));

        try (ClientSession session = client.startSession()) {
            session.startTransaction();
            MigrationContext context = new MigrationContext(client, database, session);
            assertEquals(2, context.setField("orders", empty(), "status", "open"));
            assertEquals(2, context.updateEach("orders", empty(), document -> set("checked", true)));
            assertEquals(2, context.updateMany("orders", empty(), set("reviewed", true)));
            assertEquals(1, context.deleteMany("orders", eq("_id", 1)));
            session.abortTransaction();
        }

        assertEquals(0, orders.countDocuments(exists("status")), "aborted with the transaction");
        assertEquals(0, orders.countDocuments(exists("checked")));
        assertEquals(0, orders.countDocuments(exists("reviewed")));
        assertEquals(2, orders.countDocuments(), "the delete was aborted too");
    }

    @Test
    void forEachDatabaseAndCollectionVisitOnlyMatchingOnes(TestInfo info) {
        List<String> tenants = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        for (String database : List.of(tenants.get(0), tenants.get(1), "not-a-tenant")) {
            for (String collection : List.of("a-events", "b-events", "settings")) {
                client.getDatabase(database).getCollection(collection).insertOne(new Document("runId", 1));
            }
        }
        MigrationContext context = context(info);

        List<String> visited = new ArrayList<>();
        context.forEachDatabase(name -> true, database -> visited.add(database.database().getName()));
        context.forEachDatabase(MigrationContextDataOperationsTest::isUuid, tenant -> tenant.forEachCollection(
                name -> name.endsWith("-events"), collection -> tenant.createIndex(collection, "run_idx", ascending("runId"))));
        context.forEachDatabase(MigrationContextDataOperationsTest::isUuid,
                tenant -> tenant.setField("settings", empty(), "migrated", true));

        assertTrue(visited.containsAll(tenants) && visited.contains("not-a-tenant"));
        assertTrue(visited.stream().noneMatch(name -> List.of("admin", "local", "config").contains(name)),
                "MongoDB's own databases are skipped: " + visited);
        for (String tenant : tenants) {
            MigrationContext tenantContext = new MigrationContext(client, tenant);
            assertTrue(tenantContext.indexExists("a-events", "run_idx"));
            assertTrue(tenantContext.indexExists("b-events", "run_idx"));
            assertFalse(tenantContext.indexExists("settings", "run_idx"));
        }
        assertFalse(new MigrationContext(client, "not-a-tenant").indexExists("a-events", "run_idx"));
        assertEquals(OptionalLong.of(2), context.documentsChanged(), "changes in each database count for the migration");
    }

    @Test
    void updateManyAndDeleteManyCountTheirChanges(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> runs = context.collection("runs");
        runs.insertMany(List.of(
                new Document("_id", 1).append("tenantId", 7).append("status", "done"),
                new Document("_id", 2).append("tenantId", 8).append("status", "failed"),
                new Document("_id", 3).append("tenantId", 9).append("status", "failed")));

        assertEquals(3, context.updateMany("runs", empty(), List.of(set("tenantId", new Document("$toString", "$tenantId")))));
        assertEquals(3, context.updateMany("runs", empty(), List.of(set("previousStatus", "$status"))));
        assertEquals(2, context.updateMany("runs", eq("status", "failed"), set("retry", true)));
        assertEquals(1, context.deleteMany("runs", eq("_id", 3)));

        Document run = runs.find(eq("_id", 1)).first();
        assertEquals("7", run.getString("tenantId"), "converted to a string");
        assertEquals("done", run.getString("previousStatus"), "copied from another field");
        assertEquals(2, runs.countDocuments());
        assertEquals(OptionalLong.of(3 + 3 + 2 + 1), context.documentsChanged());
    }

    @Test
    void reactiveIteratorsAndWritesBehaveTheSame(TestInfo info) {
        String tenant = UUID.randomUUID().toString();
        client.getDatabase(tenant).getCollection("x-events").insertMany(List.of(
                new Document("_id", 1).append("n", 1), new Document("_id", 2).append("n", 2)));
        ReactiveMigrationContext context = new ReactiveMigrationContext(reactiveClient, databaseName(info));

        context.forEachDatabase(tenant::equals, database -> database.forEachCollection(
                name -> name.endsWith("-events"),
                collection -> database.updateMany(collection, empty(), List.of(set("label", new Document("$toString", "$n"))))
                        .chain(() -> database.deleteMany(collection, eq("_id", 2)))
                        .replaceWithVoid()))
                .await().indefinitely();

        MongoCollection<Document> events = client.getDatabase(tenant).getCollection("x-events");
        assertEquals("1", events.find().first().getString("label"));
        assertEquals(1, events.countDocuments());
        assertEquals(OptionalLong.of(2 + 1), context.documentsChanged());
    }

    private static boolean isUuid(String name) {
        try {
            UUID.fromString(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private MigrationContext context(TestInfo info) {
        return new MigrationContext(client, databaseName(info));
    }

    /**
     * A database per test, named after it. MongoDB limits database names to 64 bytes.
     */
    private static String databaseName(TestInfo info) {
        String name = info.getTestMethod().orElseThrow().getName();
        return "ops-" + name.substring(0, Math.min(name.length(), 50));
    }
}
