package io.quarkiverse.mongodb.migrations.runtime;

import static com.mongodb.client.model.Aggregates.set;
import static com.mongodb.client.model.Filters.eq;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.collectionExists;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createTtlIndex;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.NOW;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.REMOVE;
import static io.quarkiverse.mongodb.migrations.runtime.Expressions.literal;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.UpdateOptions;

/**
 * Records failed migration attempts, one document per attempt, removed by a TTL index after the retention period.
 */
final class FailureLog {

    private static final Logger LOG = Logger.getLogger(FailureLog.class);
    static final String TTL_INDEX = "failedAt_ttl";
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_STACK_FRAMES = 20;

    private final MongoDatabase database;
    private final String collectionName;
    private final Duration retention;
    private final String instanceId;
    private boolean ttlIndexReady;

    FailureLog(MongoDatabase database, String collectionName, Duration retention, String instanceId) {
        this.database = database;
        this.collectionName = collectionName;
        this.retention = retention;
        this.instanceId = instanceId;
    }

    /**
     * Records a failed attempt. Never throws: failing to record must not hide the migration failure itself.
     *
     * @param error the error thrown by the migration
     * @param rollbackError the error thrown by its rollback, or {@code null} if the rollback succeeded
     * @param documentsChangedBeforeFailure the documents the migration reported as changed before it failed, if any
     */
    void record(MigrationDescriptor migration, Throwable error, Throwable rollbackError, Duration duration,
            OptionalLong documentsChangedBeforeFailure) {
        try {
            MongoCollection<Document> collection = database.getCollection(collectionName);
            ensureTtlIndex(collection);
            // Written as a pipeline upsert so failedAt uses the server clock, like the history and the lock.
            // Values are wrapped in $literal so nothing in them is read as a field path or operator.
            collection.updateOne(eq("_id", new ObjectId()), List.of(set(
                    new Field<>("migrationId", literal(migration.getId())),
                    new Field<>("className", literal(migration.getClassName())),
                    new Field<>("failedAt", NOW),
                    new Field<>("instanceId", literal(instanceId)),
                    new Field<>("durationMillis", duration.toMillis()),
                    new Field<>("error", literal(describe(error, true))),
                    new Field<>("rollbackError", rollbackError == null ? null : literal(describe(rollbackError, false))),
                    new Field<>("documentsChangedBeforeFailure", documentsChangedBeforeFailure.isPresent()
                            ? documentsChangedBeforeFailure.getAsLong()
                            : REMOVE))),
                    new UpdateOptions().upsert(true));
        } catch (Throwable e) {
            LOG.warnf(e, "Failed to record the failure of migration %s", migration.getId());
        }
    }

    /**
     * Creates the TTL index, or updates its expiry when the retention changed since it was created.
     */
    private void ensureTtlIndex(MongoCollection<Document> collection) {
        if (ttlIndexReady) {
            return;
        }
        long expireAfterSeconds = retention.toSeconds();
        Document existing = null;
        if (collectionExists(database, collectionName)) {
            for (Document index : collection.listIndexes()) {
                if (TTL_INDEX.equals(index.getString("name"))) {
                    existing = index;
                }
            }
        }
        if (existing == null) {
            createTtlIndex(database, collectionName, TTL_INDEX, "failedAt", retention);
        } else if (existing.get("expireAfterSeconds", Number.class).longValue() != expireAfterSeconds) {
            // The driver has no builder for collMod.
            database.runCommand(new Document("collMod", collectionName)
                    .append("index", new Document("name", TTL_INDEX).append("expireAfterSeconds", expireAfterSeconds)));
        }
        ttlIndexReady = true;
    }

    private static Document describe(Throwable error, boolean withStackTrace) {
        String message = String.valueOf(error.getMessage());
        Document document = new Document("type", error.getClass().getName())
                .append("message", message.length() <= MAX_MESSAGE_LENGTH
                        ? message
                        : message.substring(0, MAX_MESSAGE_LENGTH) + "…");
        if (withStackTrace) {
            document.append("stackTrace", Arrays.stream(error.getStackTrace())
                    .limit(MAX_STACK_FRAMES)
                    .map(StackTraceElement::toString)
                    .collect(Collectors.joining("\n")));
        }
        return document;
    }
}
