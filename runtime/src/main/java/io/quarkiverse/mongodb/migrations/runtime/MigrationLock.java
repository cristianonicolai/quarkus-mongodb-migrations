package io.quarkiverse.mongodb.migrations.runtime;

import static com.mongodb.client.model.Aggregates.set;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.NOW;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.add;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.cond;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.field;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.gt;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.literal;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.ne;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;

import io.quarkiverse.mongodb.migrations.MigrationException;

/**
 * A lease-based lock stored as a single document in MongoDB.
 * <p>
 * Expiry is computed with the server clock ({@code $$NOW}), so instances with skewed clocks agree on when the lock
 * expires. While held, the lease is renewed in the background, so long-running migrations keep the lock.
 */
public final class MigrationLock implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MigrationLock.class);
    private static final String LOCK_ID = "migrations";
    private static final Duration MAX_RETRY_INTERVAL = Duration.ofSeconds(1);

    private final MongoCollection<Document> collection;
    private final Duration leaseDuration;
    private final String instanceId;
    /** Unique per lock, so the lock stays correct even if several instances share an instance id. */
    private final String owner = UUID.randomUUID().toString();

    private ScheduledExecutorService renewer;
    private volatile boolean lost;
    private volatile String holder;

    /**
     * @param instanceId name of this instance, stored with the lock for diagnostics
     */
    public MigrationLock(MongoCollection<Document> collection, Duration leaseDuration, String instanceId) {
        this.collection = collection;
        this.leaseDuration = leaseDuration;
        this.instanceId = instanceId;
    }

    /**
     * Waits until the lock is acquired, then keeps renewing it until {@link #close()}.
     *
     * @throws MigrationException if the lock is not acquired within {@code timeout}
     */
    public void acquire(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean loggedWaiting = false;
        while (!tryAcquire()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new MigrationException("Could not acquire the migration lock within " + timeout
                        + ". It is held by " + holder + ", which is running migrations or died while holding the"
                        + " lock before it expired.");
            }
            if (!loggedWaiting) {
                LOG.infof("Waiting for the migration lock held by %s", holder);
                loggedWaiting = true;
            }
            sleep(Math.min(remaining, MAX_RETRY_INTERVAL.toNanos()));
        }
        startRenewing();
    }

    /**
     * Acquires the lock if it is free, expired, or already held by this instance.
     *
     * @return whether the lock is now held
     */
    public boolean tryAcquire() {
        // MongoDB rejects $expr in an upsert filter, so the "held by someone else" check lives in the update
        // pipeline: if another owner's lease has not expired, every field keeps its value; otherwise we take over.
        Document heldByOther = Expressions.and(ne(field("owner"), literal(owner)), gt(field("expiresAt"), NOW));
        Bson takeOver = set(
                keepIf(heldByOther, "owner", literal(owner)),
                keepIf(heldByOther, "instanceId", literal(instanceId)),
                keepIf(heldByOther, "acquiredAt", NOW),
                keepIf(heldByOther, "expiresAt", expiresAt()));
        try {
            Document lock = collection.findOneAndUpdate(eq("_id", LOCK_ID), List.of(takeOver),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            holder = lock == null ? null : lock.getString("instanceId");
            return lock != null && owner.equals(lock.getString("owner"));
        } catch (MongoException e) {
            // Another instance created the lock document at the same moment and won.
            if (ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                holder = "another instance";
                return false;
            }
            throw e;
        }
    }

    /**
     * Extends the lease if this instance still holds the lock.
     *
     * @return whether the lock is still held
     */
    public boolean renew() {
        Bson filter = and(eq("_id", LOCK_ID), eq("owner", owner));
        return collection.updateOne(filter, List.of(set(new Field<>("expiresAt", expiresAt())))).getMatchedCount() == 1;
    }

    /**
     * The instance id of the current lock holder, as last seen by {@link #tryAcquire()}.
     */
    public String holder() {
        return holder;
    }

    /**
     * Fails if the lock was lost, for example because renewal failed for longer than the lease.
     */
    public void ensureHeld() {
        if (lost || !renew()) {
            lost = true;
            throw new MigrationException("Lost the migration lock; another instance may be running migrations."
                    + " Consider increasing quarkus.mongodb-migrations.lock-lease-duration.");
        }
    }

    /**
     * Stops renewing and releases the lock if this instance holds it.
     */
    @Override
    public void close() {
        if (renewer != null) {
            renewer.shutdownNow();
        }
        try {
            collection.deleteOne(and(eq("_id", LOCK_ID), eq("owner", owner)));
        } catch (MongoException e) {
            LOG.warn("Failed to release the migration lock; it will expire after the lease duration", e);
        }
    }

    private void startRenewing() {
        long period = Math.max(1, leaseDuration.toMillis() / 3);
        renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mongodb-migrations-lock-renewal");
            thread.setDaemon(true);
            return thread;
        });
        renewer.scheduleAtFixedRate(() -> {
            try {
                if (!renew()) {
                    lost = true;
                }
            } catch (RuntimeException e) {
                LOG.warn("Failed to renew the migration lock", e);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Sets {@code name} to {@code newValue}, unless {@code condition} holds, in which case it keeps its current value.
     */
    private static Field<Document> keepIf(Document condition, String name, Object newValue) {
        return new Field<>(name, cond(condition, field(name), newValue));
    }

    private Document expiresAt() {
        return add(NOW, leaseDuration.toMillis());
    }

    private static void sleep(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("Interrupted while waiting for the migration lock", e);
        }
    }
}
