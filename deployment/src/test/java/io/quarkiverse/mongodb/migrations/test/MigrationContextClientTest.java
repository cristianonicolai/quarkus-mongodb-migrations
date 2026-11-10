package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

/**
 * Operations that need the client, not just the migrated database.
 */
public class MigrationContextClientTest {

    private static final String DATABASE = "migration-context-client-test";
    private static final String OTHER_DATABASE = "migration-context-client-other";

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TransactionalBackfill.class, ReactiveAudit.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE)
            .overrideConfigKey("quarkus.mongodb-migrations.migrate-at-start", "true");

    @Inject
    MongoClient client;

    @Test
    void migrationsCanUseTheClientForTransactionsAndOtherDatabases() {
        assertEquals(2, client.getDatabase(DATABASE).getCollection("orders").countDocuments());
        assertEquals(List.of("transactional-backfill", "reactive-audit"),
                client.getDatabase(OTHER_DATABASE).getCollection("audit").find()
                        .map(entry -> entry.getString("migration")).into(new ArrayList<>()));
    }

    @Migration(id = "transactional-backfill", order = 1)
    public static class TransactionalBackfill implements MongoMigration {

        @Override
        public void apply(MigrationContext context) {
            context.createCollection("orders");
            try (ClientSession session = context.client().startSession()) {
                session.withTransaction(() -> context.collection("orders").insertMany(session, List.of(
                        new Document("status", "open"),
                        new Document("status", "closed"))));
            }
            context.client().getDatabase(OTHER_DATABASE).getCollection("audit")
                    .insertOne(new Document("migration", "transactional-backfill"));
        }
    }

    @Migration(id = "reactive-audit", order = 2)
    public static class ReactiveAudit implements ReactiveMongoMigration {

        @Override
        public Uni<Void> apply(ReactiveMigrationContext context) {
            return context.client().getDatabase(OTHER_DATABASE).getCollection("audit")
                    .insertOne(new Document("migration", "reactive-audit"))
                    .replaceWithVoid();
        }
    }
}
