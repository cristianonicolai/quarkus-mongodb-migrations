package io.quarkiverse.mongodb.migrations.runtime;

import static com.mongodb.client.model.Aggregates.set;
import static com.mongodb.client.model.Filters.eq;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.NOW;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.add;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.field;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.ifNull;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.literal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;
import org.jboss.logging.Logger;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;

public final class MongoMigratorImpl implements MongoMigrator {

    private static final Logger LOG = Logger.getLogger(MongoMigratorImpl.class);

    private final List<MigrationDescriptor> migrations;
    private final MongoDatabase database;
    private final MongodbMigrationsConfig config;

    /**
     * @param migrations the migrations, sorted by order
     */
    public MongoMigratorImpl(List<MigrationDescriptor> migrations, MongoDatabase database, MongodbMigrationsConfig config) {
        this.migrations = migrations;
        this.database = database;
        this.config = config;
    }

    @Override
    public List<String> migrate() {
        MongoCollection<Document> history = database.getCollection(config.historyCollection());
        try (MigrationLock lock = new MigrationLock(database.getCollection(config.lockCollection()),
                config.lockLeaseDuration(), config.instanceId())) {
            lock.acquire(config.lockAcquireTimeout());
            Map<String, Document> applied = appliedEntries(history);
            syncRenamedEntries(history, applied);
            FailureLog failureLog = config.recordFailures()
                    ? new FailureLog(database, config.failureCollection(), config.failureRetention(), config.instanceId())
                    : null;
            List<String> ran = new ArrayList<>();
            for (MigrationDescriptor migration : migrations) {
                if (applied.containsKey(migration.getId()) && !migration.isRunAlways()) {
                    continue;
                }
                lock.ensureHeld();
                long start = System.nanoTime();
                LOG.infof("Running migration %s (order %d)", migration.getId(), migration.getOrder());
                try {
                    run(migration);
                } catch (MigrationException e) {
                    if (failureLog != null) {
                        Throwable rollbackError = e.getSuppressed().length > 0 ? e.getSuppressed()[0] : null;
                        failureLog.record(migration, e.getCause() != null ? e.getCause() : e, rollbackError,
                                Duration.ofNanos(System.nanoTime() - start));
                    }
                    throw e;
                }
                // Record even if the lock was lost meanwhile: the work is done, and the next ensureHeld() stops the run.
                record(history, migration, Duration.ofNanos(System.nanoTime() - start));
                ran.add(migration.getId());
            }
            if (ran.isEmpty()) {
                LOG.info("No pending migrations");
            } else {
                LOG.infof("Ran %d migration(s): %s", ran.size(), ran);
            }
            return ran;
        }
    }

    private static Map<String, Document> appliedEntries(MongoCollection<Document> history) {
        Map<String, Document> entries = new HashMap<>();
        for (Document entry : history.find()) {
            entries.put(entry.getString("_id"), entry);
        }
        return entries;
    }

    /**
     * Points applied entries at the current class name and order, so the history keeps leading to the code after a
     * migration class is renamed, moved or renumbered. The id is what identifies a migration, so nothing re-runs.
     */
    private void syncRenamedEntries(MongoCollection<Document> history, Map<String, Document> applied) {
        for (MigrationDescriptor migration : migrations) {
            Document entry = applied.get(migration.getId());
            if (entry == null) {
                continue;
            }
            String storedClassName = entry.getString("className");
            Integer storedOrder = entry.getInteger("order");
            if (migration.getClassName().equals(storedClassName) && Objects.equals(migration.getOrder(), storedOrder)) {
                continue;
            }
            history.updateOne(eq("_id", migration.getId()), Updates.combine(
                    Updates.set("className", migration.getClassName()),
                    Updates.set("order", migration.getOrder())));
            LOG.infof("Updated history of migration %s to class %s (was %s) and order %d (was %s)", migration.getId(),
                    migration.getClassName(), storedClassName, migration.getOrder(), storedOrder);
        }
    }

    private static void run(MigrationDescriptor migration) {
        Class<?> migrationClass = loadClass(migration.getClassName());
        try (InstanceHandle<?> handle = Arc.container().instance(migrationClass)) {
            Object instance = handle.get();
            try {
                if (migration.isReactive()) {
                    ((ReactiveMongoMigration) instance).apply().await().indefinitely();
                } else {
                    ((MongoMigration) instance).apply();
                }
            } catch (RuntimeException e) {
                MigrationException failure = new MigrationException("Migration " + migration.getId() + " failed", e);
                rollback(migration, instance, failure);
                throw failure;
            }
        }
    }

    /**
     * Rolls back a failed migration. A rollback failure is attached to the migration failure rather than replacing it.
     */
    private static void rollback(MigrationDescriptor migration, Object instance, MigrationException failure) {
        try {
            if (migration.isReactive()) {
                ((ReactiveMongoMigration) instance).rollback().await().indefinitely();
            } else {
                ((MongoMigration) instance).rollback();
            }
            LOG.infof("Rolled back failed migration %s", migration.getId());
        } catch (RuntimeException e) {
            LOG.errorf(e, "Rollback of failed migration %s also failed", migration.getId());
            failure.addSuppressed(e);
        }
    }

    /**
     * Records a successful run. {@code appliedAt} and {@code appliedBy} keep the first run; the {@code last*} fields,
     * {@code runCount} and {@code durationMillis} track the latest one, which only differs for {@code runAlways}
     * migrations. Times come from the MongoDB server clock, like the lock's, so they are comparable across instances.
     */
    private void record(MongoCollection<Document> history, MigrationDescriptor migration, Duration duration) {
        Document instanceId = literal(config.instanceId());
        history.updateOne(eq("_id", migration.getId()), List.of(set(
                new Field<>("order", migration.getOrder()),
                new Field<>("className", literal(migration.getClassName())),
                new Field<>("runAlways", migration.isRunAlways()),
                new Field<>("appliedAt", ifNull(field("appliedAt"), NOW)),
                new Field<>("appliedBy", ifNull(field("appliedBy"), instanceId)),
                new Field<>("lastAppliedAt", NOW),
                new Field<>("lastAppliedBy", instanceId),
                new Field<>("runCount", add(ifNull(field("runCount"), 0), 1)),
                new Field<>("durationMillis", duration.toMillis()))),
                new UpdateOptions().upsert(true));
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new MigrationException("Migration class " + className + " not found", e);
        }
    }
}
