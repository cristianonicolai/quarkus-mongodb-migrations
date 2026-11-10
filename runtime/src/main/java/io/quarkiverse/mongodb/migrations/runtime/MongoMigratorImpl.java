package io.quarkiverse.mongodb.migrations.runtime;

import static com.mongodb.client.model.Aggregates.set;
import static com.mongodb.client.model.Filters.eq;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.NOW;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.add;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.field;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.ifNull;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.literal;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.lt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;
import org.jboss.logging.Logger;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.Sorts;
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
    private final ClassLoader applicationClassLoader;
    private final MongoDatabase database;
    private final MongodbMigrationsConfig config;

    /**
     * @param migrations the migrations, sorted by order
     */
    public MongoMigratorImpl(List<MigrationDescriptor> migrations, ClassLoader applicationClassLoader,
            MongoDatabase database, MongodbMigrationsConfig config) {
        this.migrations = migrations;
        this.applicationClassLoader = applicationClassLoader;
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
                } catch (Throwable failure) {
                    if (failureLog != null) {
                        Throwable error = failure instanceof MigrationException && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        Throwable rollbackError = failure.getSuppressed().length > 0 ? failure.getSuppressed()[0] : null;
                        failureLog.record(migration, error, rollbackError, Duration.ofNanos(System.nanoTime() - start));
                    }
                    throw failure;
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

    /**
     * Reads which migrations are applied or pending, their latest failures, and the current lock holder, without
     * taking the lock. Used by the Dev UI.
     */
    public MigrationsStatus status() {
        Map<String, Document> applied = appliedEntries(database.getCollection(config.historyCollection()));
        Map<String, Document> latestFailures = latestFailures();
        List<MigrationsStatus.Migration> statuses = new ArrayList<>();
        int pending = 0;
        for (MigrationDescriptor migration : migrations) {
            Document entry = applied.get(migration.getId());
            if (entry == null || migration.isRunAlways()) {
                pending++;
            }
            statuses.add(new MigrationsStatus.Migration(migration.getId(), migration.getOrder(), migration.getClassName(),
                    migration.isReactive(), migration.isRunAlways(), entry != null,
                    entry == null ? null : iso(entry.getDate("appliedAt")),
                    entry == null ? null : entry.getString("appliedBy"),
                    entry == null ? null : iso(entry.getDate("lastAppliedAt")),
                    entry == null ? null : entry.getInteger("runCount"),
                    failureSince(latestFailures.get(migration.getId()), entry)));
        }
        return new MigrationsStatus(statuses, pending, lock());
    }

    private Map<String, Document> latestFailures() {
        Map<String, Document> latest = new HashMap<>();
        if (!config.recordFailures()) {
            return latest;
        }
        // Newest first by failedAt, which comes from the server clock. ObjectIds are generated by each instance, so
        // they don't order failures written by different instances; _id only breaks ties within a millisecond.
        for (Document failure : database.getCollection(config.failureCollection()).find()
                .sort(Sorts.descending("failedAt", "_id"))) {
            latest.putIfAbsent(failure.getString("migrationId"), failure);
        }
        return latest;
    }

    /**
     * The failure, unless the migration has succeeded since.
     */
    private static MigrationsStatus.Failure failureSince(Document failure, Document entry) {
        if (failure == null) {
            return null;
        }
        Date failedAt = failure.getDate("failedAt");
        Date lastAppliedAt = entry == null ? null : entry.getDate("lastAppliedAt");
        if (lastAppliedAt != null && failedAt != null && !failedAt.after(lastAppliedAt)) {
            return null;
        }
        Document error = failure.get("error", Document.class);
        Document rollbackError = failure.get("rollbackError", Document.class);
        return new MigrationsStatus.Failure(iso(failedAt), failure.getString("instanceId"),
                error == null ? null : error.getString("type"),
                error == null ? null : error.getString("message"),
                rollbackError == null ? null : rollbackError.getString("message"));
    }

    private MigrationsStatus.Lock lock() {
        // "expired" is computed by the server, against the same clock that set expiresAt.
        Document lock = database.getCollection(config.lockCollection()).aggregate(List.of(
                Aggregates.match(eq("_id", MigrationLock.LOCK_ID)),
                Aggregates.addFields(new Field<>("expired",
                        Expressions.and(field("expiresAt"), lt(field("expiresAt"), NOW))))))
                .first();
        if (lock == null) {
            return null;
        }
        return new MigrationsStatus.Lock(lock.getString("instanceId"), iso(lock.getDate("acquiredAt")),
                iso(lock.getDate("expiresAt")), lock.getBoolean("expired", false));
    }

    private static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
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

    private void run(MigrationDescriptor migration) {
        Class<?> migrationClass = loadClass(migration.getClassName());
        try (InstanceHandle<?> handle = Arc.container().instance(migrationClass)) {
            if (!handle.isAvailable()) {
                throw new MigrationException("Migration " + migration.getId() + " (" + migration.getClassName()
                        + ") is not available as a CDI bean");
            }
            Object instance = handle.get();
            try {
                if (migration.isReactive()) {
                    ((ReactiveMongoMigration) instance).apply().await().indefinitely();
                } else {
                    ((MongoMigration) instance).apply();
                }
            } catch (Error e) {
                // Errors (an AssertionError, OutOfMemoryError, ...) still get a rollback, but propagate unwrapped.
                rollback(migration, instance, e);
                throw e;
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
    private static void rollback(MigrationDescriptor migration, Object instance, Throwable failure) {
        try {
            if (migration.isReactive()) {
                ((ReactiveMongoMigration) instance).rollback().await().indefinitely();
            } else {
                ((MongoMigration) instance).rollback();
            }
            LOG.infof("Rolled back failed migration %s", migration.getId());
        } catch (Throwable e) {
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

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, applicationClassLoader);
        } catch (ClassNotFoundException e) {
            throw new MigrationException("Migration class " + className + " not found", e);
        }
    }
}
