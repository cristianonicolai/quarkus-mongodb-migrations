package io.quarkiverse.mongodb.migrations.runtime;

import static com.mongodb.client.model.Aggregates.set;
import static com.mongodb.client.model.Filters.eq;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.NOW;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.REMOVE;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.add;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.field;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.ifNull;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.literal;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.lt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;
import org.reactivestreams.Publisher;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MigrationHelpers;
import io.quarkiverse.mongodb.migrations.MigrationStatus;
import io.quarkiverse.mongodb.migrations.MigrationsStatus;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Uni;
import mutiny.zero.flow.adapters.AdaptersToFlow;

public final class MongoMigratorImpl implements MongoMigrator {

    private static final Logger LOG = Logger.getLogger(MongoMigratorImpl.class);

    private final List<MigrationDescriptor> migrations;
    private final ClassLoader applicationClassLoader;
    private final MongoDatabase database;
    private final MongoClient client;
    private final Supplier<ReactiveMongoClient> reactiveClient;
    private final Supplier<String> migratedDatabaseName;
    private final MongodbMigrationsConfig config;

    /**
     * @param migrations the migrations, sorted by order
     * @param database where the history, lock and failures are stored, on {@code client}
     * @param client the client migrations work with
     * @param reactiveClient the same client, reactive, looked up only when a reactive migration runs
     * @param migratedDatabaseName the database migrations work on; fails when none is configured
     */
    public MongoMigratorImpl(List<MigrationDescriptor> migrations, ClassLoader applicationClassLoader,
            MongoDatabase database, MongoClient client, Supplier<ReactiveMongoClient> reactiveClient,
            Supplier<String> migratedDatabaseName, MongodbMigrationsConfig config) {
        this.migrations = migrations;
        this.applicationClassLoader = applicationClassLoader;
        this.database = database;
        this.client = client;
        this.reactiveClient = reactiveClient;
        this.migratedDatabaseName = migratedDatabaseName;
        this.config = config;
    }

    @Override
    public List<String> migrate() {
        return migrateUpTo(Integer.MAX_VALUE);
    }

    @Override
    public List<String> migrateTo(String id) {
        MigrationDescriptor target = migrations.stream().filter(migration -> migration.getId().equals(id)).findFirst()
                .orElseThrow(() -> new MigrationException("No migration with id " + id));
        return migrateUpTo(target.getOrder());
    }

    @Override
    public MigrationContext context() {
        return new MigrationContext(client, migratedDatabaseName.get());
    }

    @Override
    public ReactiveMigrationContext reactiveContext() {
        return new ReactiveMigrationContext(reactiveClient.get(), migratedDatabaseName.get());
    }

    /**
     * Runs the pending migrations whose order is at most {@code lastOrder}.
     */
    private List<String> migrateUpTo(int lastOrder) {
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
                if (migration.getOrder() > lastOrder) {
                    break;
                }
                if (applied.containsKey(migration.getId()) && !migration.isRunAlways()) {
                    continue;
                }
                lock.ensureHeld();
                long start = System.nanoTime();
                LOG.infof("Running migration %s (order %d)", migration.getId(), migration.getOrder());
                Attempt attempt = new Attempt();
                try {
                    if (migration.isTransactional()) {
                        runInTransaction(migration, history, start, attempt);
                    } else {
                        run(migration, attempt);
                    }
                } catch (Throwable failure) {
                    if (failureLog != null) {
                        Throwable error = failure instanceof MigrationException && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        Throwable rollbackError = failure.getSuppressed().length > 0 ? failure.getSuppressed()[0] : null;
                        // An aborted transaction kept none of its changes, so only report them for other migrations.
                        OptionalLong changedBeforeFailure = migration.isTransactional()
                                ? OptionalLong.empty()
                                : attempt.documentsChanged();
                        failureLog.record(migration, error, rollbackError, Duration.ofNanos(System.nanoTime() - start),
                                changedBeforeFailure);
                    }
                    throw failure;
                }
                Duration duration = Duration.ofNanos(System.nanoTime() - start);
                if (!migration.isTransactional()) {
                    // Record even if the lock was lost meanwhile: the work is done, and the next ensureHeld() stops
                    // the run. Transactional migrations are recorded inside their transaction.
                    record(history, migration, duration, attempt.documentsChanged(), null);
                }
                OptionalLong changed = attempt.documentsChanged();
                if (changed.isPresent()) {
                    LOG.infof("Ran migration %s in %d ms, %d documents changed", migration.getId(), duration.toMillis(),
                            changed.getAsLong());
                } else {
                    LOG.infof("Ran migration %s in %d ms", migration.getId(), duration.toMillis());
                }
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

    @Override
    public MigrationsStatus status() {
        Map<String, Document> applied = appliedEntries(database.getCollection(config.historyCollection()));
        Map<String, Document> latestFailures = latestFailures();
        List<MigrationStatus> statuses = new ArrayList<>();
        for (MigrationDescriptor migration : migrations) {
            Document entry = applied.get(migration.getId());
            statuses.add(new MigrationStatus(migration.getId(), migration.getOrder(), migration.getClassName(),
                    migration.isReactive(), migration.isRunAlways(), migration.isTransactional(), entry != null,
                    entry == null ? null : instant(entry.getDate("appliedAt")),
                    entry == null ? null : entry.getString("appliedBy"),
                    entry == null ? null : instant(entry.getDate("lastAppliedAt")),
                    entry == null ? null : entry.getInteger("runCount"),
                    entry == null ? null : asLong(entry.get("documentsChanged")),
                    failureSince(latestFailures.get(migration.getId()), entry)));
        }
        return new MigrationsStatus(statuses, lock());
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
    private static MigrationStatus.Failure failureSince(Document failure, Document entry) {
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
        return new MigrationStatus.Failure(instant(failedAt), failure.getString("instanceId"),
                error == null ? null : error.getString("type"),
                error == null ? null : error.getString("message"),
                rollbackError == null ? null : rollbackError.getString("message"),
                asLong(failure.get("documentsChangedBeforeFailure")));
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
        return new MigrationsStatus.Lock(lock.getString("instanceId"), instant(lock.getDate("acquiredAt")),
                instant(lock.getDate("expiresAt")), lock.getBoolean("expired", false));
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
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

    private void run(MigrationDescriptor migration, Attempt attempt) {
        String databaseName = migratedDatabaseName(migration);
        Object migrationContext = migration.isReactive()
                ? new ReactiveMigrationContext(reactiveClient(migration), databaseName)
                : new MigrationContext(client, databaseName);
        attempt.context = migrationContext;
        invoke(migration, migrationContext, true);
    }

    /**
     * Runs a migration and writes its history entry in one transaction, so both commit or neither does. A failure
     * aborts the transaction instead of calling rollback.
     */
    private void runInTransaction(MigrationDescriptor migration, MongoCollection<Document> history, long start,
            Attempt attempt) {
        String databaseName = migratedDatabaseName(migration);
        // Collections can't always be created inside a transaction, so the history collection must exist first.
        MigrationHelpers.createCollection(database, config.historyCollection());
        if (migration.isReactive()) {
            runInReactiveTransaction(migration, databaseName, start, attempt);
            return;
        }
        try (ClientSession session = client.startSession()) {
            session.startTransaction();
            try {
                MigrationContext migrationContext = new MigrationContext(client, databaseName, session);
                attempt.context = migrationContext;
                invoke(migration, migrationContext, false);
                record(history, migration, Duration.ofNanos(System.nanoTime() - start), attempt.documentsChanged(),
                        session);
                session.commitTransaction();
            } catch (Throwable failure) {
                if (session.hasActiveTransaction()) {
                    abort(migration, failure, session::abortTransaction);
                }
                throw asMigrationFailure(migration, failure);
            }
        }
    }

    private void runInReactiveTransaction(MigrationDescriptor migration, String databaseName, long start,
            Attempt attempt) {
        ReactiveMongoClient reactive = reactiveClient(migration);
        com.mongodb.reactivestreams.client.ClientSession session = reactive.startSession().await().indefinitely();
        try {
            session.startTransaction();
            try {
                ReactiveMigrationContext migrationContext = new ReactiveMigrationContext(reactive, databaseName, session);
                attempt.context = migrationContext;
                invoke(migration, migrationContext, false);
                reactive.getDatabase(database.getName()).getCollection(config.historyCollection())
                        .updateOne(session, historyFilter(migration),
                                historyUpdate(migration, Duration.ofNanos(System.nanoTime() - start),
                                        attempt.documentsChanged()),
                                new UpdateOptions().upsert(true))
                        .await().indefinitely();
                await(session.commitTransaction());
            } catch (Throwable failure) {
                if (session.hasActiveTransaction()) {
                    abort(migration, failure, () -> await(session.abortTransaction()));
                }
                throw asMigrationFailure(migration, failure);
            }
        } finally {
            session.close();
        }
    }

    /**
     * Aborts a failed transaction. An abort failure is attached to the migration failure rather than replacing it.
     */
    private static void abort(MigrationDescriptor migration, Throwable failure, Runnable abort) {
        try {
            abort.run();
            LOG.infof("Aborted the transaction of failed migration %s", migration.getId());
        } catch (Throwable e) {
            LOG.errorf(e, "Aborting the transaction of failed migration %s also failed", migration.getId());
            failure.addSuppressed(e);
        }
    }

    /**
     * Errors propagate as they are; other failures, such as a failed commit, are reported as migration failures.
     */
    private static RuntimeException asMigrationFailure(MigrationDescriptor migration, Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof MigrationException migrationFailure) {
            return migrationFailure;
        }
        MigrationException wrapped = new MigrationException("Migration " + migration.getId() + " failed", failure);
        for (Throwable suppressed : failure.getSuppressed()) {
            wrapped.addSuppressed(suppressed);
        }
        return wrapped;
    }

    private static void await(Publisher<Void> publisher) {
        Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher)).await().indefinitely();
    }

    /**
     * Calls the migration's apply, and on failure its rollback when {@code rollbackOnFailure} is set.
     */
    private void invoke(MigrationDescriptor migration, Object migrationContext, boolean rollbackOnFailure) {
        Class<?> migrationClass = loadClass(migration.getClassName());
        try (InstanceHandle<?> handle = Arc.container().instance(migrationClass)) {
            if (!handle.isAvailable()) {
                throw new MigrationException("Migration " + migration.getId() + " (" + migration.getClassName()
                        + ") is not available as a CDI bean");
            }
            Object instance = handle.get();
            try {
                if (migration.isReactive()) {
                    ((ReactiveMongoMigration) instance).apply((ReactiveMigrationContext) migrationContext)
                            .await().indefinitely();
                } else {
                    ((MongoMigration) instance).apply((MigrationContext) migrationContext);
                }
            } catch (Error e) {
                // Errors (an AssertionError, OutOfMemoryError, ...) still get a rollback, but propagate unwrapped.
                if (rollbackOnFailure) {
                    rollback(migration, instance, migrationContext, e);
                }
                throw e;
            } catch (RuntimeException e) {
                MigrationException failure = new MigrationException("Migration " + migration.getId() + " failed", e);
                if (rollbackOnFailure) {
                    rollback(migration, instance, migrationContext, failure);
                }
                throw failure;
            }
        }
    }

    /**
     * The database migrations work on. If it isn't configured, the migration didn't start, so there is nothing to
     * roll back.
     */
    private String migratedDatabaseName(MigrationDescriptor migration) {
        try {
            return migratedDatabaseName.get();
        } catch (RuntimeException e) {
            throw new MigrationException("Migration " + migration.getId() + " could not start", e);
        }
    }

    private ReactiveMongoClient reactiveClient(MigrationDescriptor migration) {
        try {
            return reactiveClient.get();
        } catch (RuntimeException e) {
            throw new MigrationException("Migration " + migration.getId() + " could not start", e);
        }
    }

    /**
     * Rolls back a failed migration. A rollback failure is attached to the migration failure rather than replacing it.
     */
    private static void rollback(MigrationDescriptor migration, Object instance, Object migrationContext,
            Throwable failure) {
        try {
            if (migration.isReactive()) {
                ((ReactiveMongoMigration) instance).rollback((ReactiveMigrationContext) migrationContext)
                        .await().indefinitely();
            } else {
                ((MongoMigration) instance).rollback((MigrationContext) migrationContext);
            }
            LOG.infof("Rolled back failed migration %s", migration.getId());
        } catch (Throwable e) {
            LOG.errorf(e, "Rollback of failed migration %s also failed", migration.getId());
            failure.addSuppressed(e);
        }
    }

    /**
     * Records a successful run, in the session's transaction when one is given.
     */
    private void record(MongoCollection<Document> history, MigrationDescriptor migration, Duration duration,
            OptionalLong documentsChanged, ClientSession session) {
        List<Bson> update = historyUpdate(migration, duration, documentsChanged);
        if (session == null) {
            history.updateOne(historyFilter(migration), update, new UpdateOptions().upsert(true));
        } else {
            history.updateOne(session, historyFilter(migration), update, new UpdateOptions().upsert(true));
        }
    }

    private static Bson historyFilter(MigrationDescriptor migration) {
        return eq("_id", migration.getId());
    }

    /**
     * {@code appliedAt} and {@code appliedBy} keep the first run; the {@code last*} fields, {@code runCount} and
     * {@code durationMillis} track the latest one, which only differs for {@code runAlways} migrations. Times come
     * from the MongoDB server clock, like the lock's, so they are comparable across instances. When the run reported
     * no changed documents, {@code documentsChanged} is removed rather than kept from an earlier run.
     */
    private List<Bson> historyUpdate(MigrationDescriptor migration, Duration duration, OptionalLong documentsChanged) {
        Document instanceId = literal(config.instanceId());
        return List.of(set(
                new Field<>("order", migration.getOrder()),
                new Field<>("className", literal(migration.getClassName())),
                new Field<>("runAlways", migration.isRunAlways()),
                new Field<>("transactional", migration.isTransactional()),
                new Field<>("appliedAt", ifNull(field("appliedAt"), NOW)),
                new Field<>("appliedBy", ifNull(field("appliedBy"), instanceId)),
                new Field<>("lastAppliedAt", NOW),
                new Field<>("lastAppliedBy", instanceId),
                new Field<>("runCount", add(ifNull(field("runCount"), 0), 1)),
                new Field<>("durationMillis", duration.toMillis()),
                new Field<>("documentsChanged", documentsChanged.isPresent() ? documentsChanged.getAsLong() : REMOVE)));
    }

    /**
     * The migration being run, so its changed-document count can be read after it succeeds or fails.
     */
    private static final class Attempt {

        private Object context;

        OptionalLong documentsChanged() {
            if (context instanceof MigrationContext blocking) {
                return blocking.documentsChanged();
            }
            if (context instanceof ReactiveMigrationContext reactive) {
                return reactive.documentsChanged();
            }
            return OptionalLong.empty();
        }
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, applicationClassLoader);
        } catch (ClassNotFoundException e) {
            throw new MigrationException("Migration class " + className + " not found", e);
        }
    }
}
