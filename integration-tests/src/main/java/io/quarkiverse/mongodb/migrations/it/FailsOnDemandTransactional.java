package io.quarkiverse.mongodb.migrations.it;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

/**
 * Succeeds at startup, and fails when the test asks it to, to exercise an aborted transaction.
 */
@Migration(id = "fails-on-demand-transactional", order = 101, runAlways = true, transactional = true)
public class FailsOnDemandTransactional implements MongoMigration {

    static final AtomicBoolean FAIL = new AtomicBoolean();

    @Override
    public void apply(MigrationContext context) {
        context.collection("transactionalProbes").insertOne(context.session(), new Document("migration", "transactional"));
        if (FAIL.get()) {
            throw new IllegalStateException("transactional failure on demand");
        }
    }
}
