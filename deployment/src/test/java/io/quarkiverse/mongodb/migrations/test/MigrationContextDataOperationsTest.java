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
        MongoCollection<Document> customers = context.collection("customers");
        customers.insertMany(List.of(
                new Document("_id", 1).append("plan", "basic"),
                new Document("_id", 2).append("plan", "basic"),
                new Document("_id", 3).append("plan", "enterprise")));

        assertEquals(2, context.setField("customers", eq("plan", "basic"), "tier", "small"));
        assertEquals(3, context.setFields("customers", empty(), Map.of("deleted", false, "version", 2)));

        assertEquals("small", customers.find(eq("_id", 1)).first().getString("tier"));
        assertNull(customers.find(eq("_id", 3)).first().getString("tier"));
        assertEquals(3, customers.countDocuments(Document.parse("{deleted: false, version: 2}")));
        assertEquals(OptionalLong.of(5), context.documentsChanged());
    }

    @Test
    void setFieldIfMissingOnlyFillsMissingOrNullValues(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> users = context.collection("users");
        users.insertMany(List.of(
                new Document("_id", "kept").append("pageSize", 8).append("preferences", new Document()),
                new Document("_id", "null").append("pageSize", null),
                new Document("_id", "missing")));

        assertEquals(2, context.setFieldIfMissing("users", "pageSize", 1));
        assertEquals(0, context.setFieldIfMissing("users", "pageSize", 1), "safe to run again");
        assertEquals(8, users.find(eq("_id", "kept")).first().getInteger("pageSize"));
        assertEquals(1, users.find(eq("_id", "missing")).first().getInteger("pageSize"));

        assertEquals(1, context.setFieldIfMissing("users", exists("preferences"), "preferences.newsletter", false));
        assertFalse(users.find(eq("_id", "missing")).first().containsKey("preferences"), "filtered out");
    }

    @Test
    void setFieldInArrayUpdatesEveryElementAndSkipsDocumentsWithoutTheArray(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> customers = context.collection("customers");
        customers.insertMany(List.of(
                new Document("_id", 1).append("contacts", List.of(new Document("name", "a"), new Document("name", "b"))),
                new Document("_id", 2).append("contacts", List.of()),
                new Document("_id", 3)));

        assertEquals(1, context.setFieldInArray("customers", "contacts", "notified", true));

        List<Document> contacts = customers.find(eq("_id", 1)).first().getList("contacts", Document.class);
        assertTrue(contacts.stream().allMatch(contact -> contact.getBoolean("notified")));
        assertFalse(customers.find(eq("_id", 3)).first().containsKey("contacts"));
    }

    @Test
    void setFieldInArrayWithAnElementFilterOnlyUpdatesMatchingElements(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> customers = context.collection("customers");
        customers.insertOne(new Document("_id", 1).append("devices", List.of(
                new Document("name", "laptop"),
                new Document("name", "phone"),
                new Document("name", "tablet"))));

        context.setFieldInArray("customers", "devices", eq("name", "laptop"), "type", "COMPANY");
        context.setFieldInArray("customers", "devices", not(eq("name", "laptop")), "type", "PERSONAL");

        assertEquals(List.of("COMPANY", "PERSONAL", "PERSONAL"), customers.find().first().getList("devices", Document.class)
                .stream().map(device -> device.getString("type")).toList());
    }

    @Test
    void setFieldInArrayIfMissingKeepsExistingValues(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> customers = context.collection("customers");
        customers.insertMany(List.of(
                new Document("_id", 1).append("contacts", List.of(
                        new Document("name", "a").append("status", "INVITED"),
                        new Document("name", "b"))),
                new Document("_id", 2).append("contacts", List.of(new Document("name", "c").append("status", "INVITED"))),
                new Document("_id", 3)));

        assertEquals(1, context.setFieldInArrayIfMissing("customers", "contacts", "status", "ACTIVE"));
        assertEquals(0, context.setFieldInArrayIfMissing("customers", "contacts", "status", "ACTIVE"), "safe to run again");

        assertEquals(List.of("INVITED", "ACTIVE"), customers.find(eq("_id", 1)).first().getList("contacts", Document.class)
                .stream().map(contact -> contact.getString("status")).toList());
        assertEquals("INVITED", customers.find(eq("_id", 2)).first().getList("contacts", Document.class).get(0)
                .getString("status"));
    }

    @Test
    void removeFieldInArrayRemovesTheFieldFromElementsThatHaveIt(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> customers = context.collection("customers");
        customers.insertMany(List.of(
                new Document("_id", 1).append("contacts", List.of(
                        new Document("name", "a").append("legacyCode", "admin"),
                        new Document("name", "b"))),
                new Document("_id", 2).append("contacts", List.of(new Document("name", "c")))));

        assertEquals(1, context.removeFieldInArray("customers", "contacts", "legacyCode"));

        assertTrue(customers.find(eq("_id", 1)).first().getList("contacts", Document.class).stream()
                .noneMatch(contact -> contact.containsKey("legacyCode")));
    }

    @Test
    void elementFiltersMustBeOnElementFields(TestInfo info) {
        MigrationContext context = context(info);

        assertThrows(IllegalArgumentException.class,
                () -> context.setFieldInArray("customers", "contacts", expr(new Document("$eq", List.of(1, 1))), "a", 1));
        assertThrows(IllegalArgumentException.class,
                () -> context.setFieldInArray("customers", "contacts", empty(), "a", 1));
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
        MongoCollection<Document> customers = client.getDatabase(database).getCollection("customers");
        customers.insertMany(List.of(
                new Document("_id", 1).append("contacts", List.of(
                        new Document("status", "INVITED"), new Document())),
                new Document("_id", 2)));
        client.getDatabase(database).getCollection("numbers")
                .insertMany(IntStream.range(0, 1500).mapToObj(i -> new Document("_id", i)).toList());

        assertEquals(2L, context.setField("customers", empty(), "deleted", false).await().indefinitely());
        assertEquals(2L, context.setFieldIfMissing("customers", "plan", "basic").await().indefinitely());
        assertEquals(1L, context.setFieldInArrayIfMissing("customers", "contacts", "status", "ACTIVE").await().indefinitely());
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
        List<String> customers = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        for (String database : List.of(customers.get(0), customers.get(1), "not-a-customer")) {
            for (String collection : List.of("a-events", "b-events", "settings")) {
                client.getDatabase(database).getCollection(collection).insertOne(new Document("orderId", 1));
            }
        }
        MigrationContext context = context(info);

        List<String> visited = new ArrayList<>();
        context.forEachDatabase(name -> true, database -> visited.add(database.database().getName()));
        context.forEachDatabase(MigrationContextDataOperationsTest::isUuid, customer -> customer.forEachCollection(
                name -> name.endsWith("-events"),
                collection -> customer.createIndex(collection, "order_idx", ascending("orderId"))));
        context.forEachDatabase(MigrationContextDataOperationsTest::isUuid,
                customer -> customer.setField("settings", empty(), "migrated", true));

        assertTrue(visited.containsAll(customers) && visited.contains("not-a-customer"));
        assertTrue(visited.stream().noneMatch(name -> List.of("admin", "local", "config").contains(name)),
                "MongoDB's own databases are skipped: " + visited);
        for (String customer : customers) {
            MigrationContext customerContext = new MigrationContext(client, customer);
            assertTrue(customerContext.indexExists("a-events", "order_idx"));
            assertTrue(customerContext.indexExists("b-events", "order_idx"));
            assertFalse(customerContext.indexExists("settings", "order_idx"));
        }
        assertFalse(new MigrationContext(client, "not-a-customer").indexExists("a-events", "order_idx"));
        assertEquals(OptionalLong.of(2), context.documentsChanged(), "changes in each database count for the migration");
    }

    @Test
    void forDatabaseSharesTheChangeCountAndTheTransaction(TestInfo info) {
        String audit = UUID.randomUUID().toString();
        MongoCollection<Document> events = client.getDatabase(audit).getCollection("events");
        events.insertMany(List.of(new Document("_id", 1), new Document("_id", 2).append("version", 3)));

        MigrationContext context = context(info);
        assertEquals(1, context.forDatabase(audit).setFieldIfMissing("events", "version", 1));
        assertEquals(OptionalLong.of(1), context.documentsChanged(), "counted for the migration");
        assertEquals(audit, context.forDatabase(audit).database().getName());

        try (ClientSession session = client.startSession()) {
            session.startTransaction();
            MigrationContext transactional = new MigrationContext(client, databaseName(info), session);
            assertEquals(2, transactional.forDatabase(audit).setField("events", empty(), "aborted", true));
            session.abortTransaction();
        }
        assertEquals(0, events.countDocuments(exists("aborted")), "aborted with the migration's transaction");

        ReactiveMigrationContext reactive = new ReactiveMigrationContext(reactiveClient, databaseName(info));
        assertEquals(2L, reactive.forDatabase(audit).setField("events", empty(), "reactive", true).await().indefinitely());
        assertEquals(OptionalLong.of(2), reactive.documentsChanged());
    }

    @Test
    void updateManyAndDeleteManyCountTheirChanges(TestInfo info) {
        MigrationContext context = context(info);
        MongoCollection<Document> orders = context.collection("orders");
        orders.insertMany(List.of(
                new Document("_id", 1).append("customerId", 7).append("status", "done"),
                new Document("_id", 2).append("customerId", 8).append("status", "failed"),
                new Document("_id", 3).append("customerId", 9).append("status", "failed")));

        assertEquals(3,
                context.updateMany("orders", empty(), List.of(set("customerId", new Document("$toString", "$customerId")))));
        assertEquals(3, context.updateMany("orders", empty(), List.of(set("previousStatus", "$status"))));
        assertEquals(2, context.updateMany("orders", eq("status", "failed"), set("retry", true)));
        assertEquals(1, context.deleteMany("orders", eq("_id", 3)));

        Document order = orders.find(eq("_id", 1)).first();
        assertEquals("7", order.getString("customerId"), "converted to a string");
        assertEquals("done", order.getString("previousStatus"), "copied from another field");
        assertEquals(2, orders.countDocuments());
        assertEquals(OptionalLong.of(3 + 3 + 2 + 1), context.documentsChanged());
    }

    @Test
    void reactiveIteratorsAndWritesBehaveTheSame(TestInfo info) {
        String customer = UUID.randomUUID().toString();
        client.getDatabase(customer).getCollection("x-events").insertMany(List.of(
                new Document("_id", 1).append("n", 1), new Document("_id", 2).append("n", 2)));
        ReactiveMigrationContext context = new ReactiveMigrationContext(reactiveClient, databaseName(info));

        context.forEachDatabase(customer::equals, database -> database.forEachCollection(
                name -> name.endsWith("-events"),
                collection -> database.updateMany(collection, empty(), List.of(set("label", new Document("$toString", "$n"))))
                        .chain(() -> database.deleteMany(collection, eq("_id", 2)))
                        .replaceWithVoid()))
                .await().indefinitely();

        MongoCollection<Document> events = client.getDatabase(customer).getCollection("x-events");
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
