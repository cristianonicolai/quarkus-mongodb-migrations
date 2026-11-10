package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Indexes.ascending;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * The way the README suggests testing a migration: migrate up to just before it, seed data in the old shape, then run
 * it and check the result.
 * <p>
 * The tests share one database and one migration history, and the first one expects every migration to be pending
 * when it starts. Keep the other tests from running migrations, or it depends on the order the tests run in.
 */
public class MigrationTestingSupportTest {

    private static final String DATABASE = "migration-testing-support-test";

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CreateTenants.class, BackfillMemberStatus.class,
                    AddPlanDefault.class))
            .overrideConfigKey("quarkus.mongodb.database", DATABASE);

    @Inject
    MongoMigrator migrator;

    @Test
    void migrateToRunsMigrationsUpToTheGivenOneSoOldDataCanBeSeeded() {
        assertEquals(List.of("create-tenants", "backfill-member-status", "add-plan-default"), migrator.pending());

        assertEquals(List.of("create-tenants"), migrator.migrateTo("create-tenants"));
        assertEquals(List.of("backfill-member-status", "add-plan-default"), migrator.pending());

        MigrationContext context = migrator.context();
        assertTrue(context.indexExists("tenants", "name_idx"));
        context.collection("tenants").insertOne(new Document("_id", "acme").append("members", List.of(
                new Document("name", "ann").append("status", "INVITED"),
                new Document("name", "bob"))));

        assertEquals(List.of("backfill-member-status"), migrator.migrateTo("backfill-member-status"));
        Document tenant = context.collection("tenants").find().first();
        assertEquals(List.of("INVITED", "ACTIVE"), tenant.getList("members", Document.class).stream()
                .map(member -> member.getString("status")).toList());
        assertFalse(tenant.containsKey("plan"), "later migrations didn't run");
        assertEquals(1L, migrator.status().migration("backfill-member-status").documentsChanged());

        assertEquals(List.of(), migrator.migrateTo("create-tenants"), "already applied");

        // Running a migration again changes nothing: it is safe to retry.
        MigrationContext again = migrator.context();
        new BackfillMemberStatus().apply(again);
        assertEquals(OptionalLong.of(0), again.documentsChanged());

        assertEquals(List.of("add-plan-default"), migrator.migrate());
        assertEquals(List.of(), migrator.pending());
    }

    @Test
    void migrateToAnUnknownMigrationFails() {
        MigrationException failure = assertThrows(MigrationException.class, () -> migrator.migrateTo("unknown"));

        assertEquals("No migration with id unknown", failure.getMessage());
    }

    @Test
    void contextsWorkOnTheDatabaseMigrationsUse() {
        assertEquals(DATABASE, migrator.context().database().getName());
        assertEquals(DATABASE, migrator.reactiveContext().database().getName());
    }

    @Migration(id = "create-tenants", order = 1)
    public static class CreateTenants implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.createCollection("tenants");
            context.createIndex("tenants", "name_idx", ascending("name"));
        }
    }

    @Migration(id = "backfill-member-status", order = 2)
    public static class BackfillMemberStatus implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.setFieldInArrayIfMissing("tenants", "members", "status", "ACTIVE");
        }
    }

    @Migration(id = "add-plan-default", order = 3)
    public static class AddPlanDefault implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
            context.setFieldIfMissing("tenants", "plan", "basic");
        }
    }
}
