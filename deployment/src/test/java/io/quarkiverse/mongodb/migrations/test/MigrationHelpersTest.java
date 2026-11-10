package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Indexes.ascending;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createCollection;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createIndex;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createTtlIndex;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.dropCollection;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.dropIndex;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.indexExists;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.removeField;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.renameCollection;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.renameField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.ReactiveMigrationHelpers;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoDatabase;
import io.quarkus.test.QuarkusExtensionTest;

public class MigrationHelpersTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .overrideConfigKey("quarkus.mongodb.database", "migration-helpers-test");

    @Inject
    MongoClient client;

    @Inject
    ReactiveMongoClient reactiveClient;

    @Test
    void collectionHelpersAreIdempotent() {
        MongoDatabase database = client.getDatabase("collections");

        createCollection(database, "orders");
        createCollection(database, "orders");
        assertTrue(collectionExists(database, "orders"));

        renameCollection(database, "orders", "purchases");
        renameCollection(database, "orders", "purchases");
        assertFalse(collectionExists(database, "orders"));
        assertTrue(collectionExists(database, "purchases"));

        dropCollection(database, "purchases");
        dropCollection(database, "purchases");
        assertFalse(collectionExists(database, "purchases"));
    }

    @Test
    void indexHelpersAreIdempotent() {
        MongoDatabase database = client.getDatabase("indexes");

        assertFalse(indexExists(database, "users", "email_idx"), "missing collection has no indexes");
        createIndex(database, "users", "email_idx", ascending("email"));
        createIndex(database, "users", "email_idx", ascending("email"));
        assertTrue(indexExists(database, "users", "email_idx"));

        createTtlIndex(database, "users", "created_ttl", "createdAt", Duration.ofDays(1));
        Document ttl = database.getCollection("users").listIndexes().into(new ArrayList<>()).stream()
                .filter(index -> "created_ttl".equals(index.getString("name")))
                .findFirst().orElseThrow();
        assertEquals(86400L, ttl.get("expireAfterSeconds", Number.class).longValue());

        dropIndex(database, "users", "email_idx");
        dropIndex(database, "users", "email_idx");
        assertFalse(indexExists(database, "users", "email_idx"));
    }

    @Test
    void fieldHelpersChangeOnlyDocumentsWithTheField() {
        MongoDatabase database = client.getDatabase("fields");
        database.getCollection("users").insertMany(List.of(
                new Document("mail", "a@example.com").append("legacy", true),
                new Document("email", "b@example.com")));

        assertEquals(1, renameField(database, "users", "mail", "email"));
        assertEquals(0, renameField(database, "users", "mail", "email"));
        assertEquals(2, database.getCollection("users").countDocuments(exists("email")));

        assertEquals(1, removeField(database, "users", "legacy"));
        assertEquals(0, removeField(database, "users", "legacy"));
    }

    @Test
    void reactiveHelpersAreIdempotent() {
        ReactiveMongoDatabase database = reactiveClient.getDatabase("reactive");

        ReactiveMigrationHelpers.createCollection(database, "orders").await().indefinitely();
        ReactiveMigrationHelpers.createCollection(database, "orders").await().indefinitely();
        assertTrue(ReactiveMigrationHelpers.collectionExists(database, "orders").await().indefinitely());

        ReactiveMigrationHelpers.createIndex(database, "orders", "status_idx", ascending("status")).await().indefinitely();
        ReactiveMigrationHelpers.createIndex(database, "orders", "status_idx", ascending("status")).await().indefinitely();
        assertTrue(ReactiveMigrationHelpers.indexExists(database, "orders", "status_idx").await().indefinitely());

        ReactiveMigrationHelpers.dropIndex(database, "orders", "status_idx").await().indefinitely();
        ReactiveMigrationHelpers.dropIndex(database, "orders", "status_idx").await().indefinitely();
        assertFalse(ReactiveMigrationHelpers.indexExists(database, "orders", "status_idx").await().indefinitely());

        ReactiveMigrationHelpers.renameCollection(database, "orders", "purchases").await().indefinitely();
        ReactiveMigrationHelpers.renameCollection(database, "orders", "purchases").await().indefinitely();
        assertTrue(ReactiveMigrationHelpers.collectionExists(database, "purchases").await().indefinitely());

        ReactiveMigrationHelpers.dropCollection(database, "purchases").await().indefinitely();
        assertFalse(ReactiveMigrationHelpers.collectionExists(database, "purchases").await().indefinitely());
    }

    @Test
    void reactiveTtlAndFieldHelpers() {
        ReactiveMongoDatabase database = reactiveClient.getDatabase("reactive-fields");
        database.getCollection("users").insertMany(List.of(
                new Document("mail", "a@example.com").append("legacy", true),
                new Document("email", "b@example.com"))).await().indefinitely();

        ReactiveMigrationHelpers.createTtlIndex(database, "users", "created_ttl", "createdAt", Duration.ofHours(1))
                .await().indefinitely();
        ReactiveMigrationHelpers.createTtlIndex(database, "users", "created_ttl", "createdAt", Duration.ofHours(1))
                .await().indefinitely();
        Document ttl = database.getCollection("users").listIndexes()
                .select().where(index -> "created_ttl".equals(index.getString("name")))
                .collect().first().await().indefinitely();
        assertEquals(3600L, ttl.get("expireAfterSeconds", Number.class).longValue());

        assertEquals(1L, ReactiveMigrationHelpers.renameField(database, "users", "mail", "email").await().indefinitely());
        assertEquals(0L, ReactiveMigrationHelpers.renameField(database, "users", "mail", "email").await().indefinitely());
        assertEquals(1L, ReactiveMigrationHelpers.removeField(database, "users", "legacy").await().indefinitely());
        assertEquals(0L, ReactiveMigrationHelpers.removeField(database, "users", "legacy").await().indefinitely());
    }
}
