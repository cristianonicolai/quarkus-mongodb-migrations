package io.quarkiverse.mongodb.migrations.it;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

/**
 * Succeeds at startup, and fails when the test asks it to, to exercise rollback and failure records.
 */
@Migration(id = "fails-on-demand", order = 100, runAlways = true)
public class FailsOnDemand implements MongoMigration {

    static final AtomicBoolean FAIL = new AtomicBoolean();

    @Override
    public void apply(MigrationContext context) {
        context.recordChanges(context.collection("probes").insertOne(new Document("migration", "fails-on-demand")));
        if (FAIL.get()) {
            throw new IllegalStateException("failed on demand");
        }
    }

    @Override
    public void rollback(MigrationContext context) {
        context.collection("rollbacks").insertOne(new Document("migration", "fails-on-demand"));
    }
}
