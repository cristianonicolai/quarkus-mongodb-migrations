package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.rename;
import static com.mongodb.client.model.Updates.unset;

import java.time.Duration;
import java.util.OptionalLong;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.ClientSession;

import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.mongodb.reactive.ReactiveMongoDatabase;
import io.smallrye.mutiny.Uni;

/**
 * Passed to a {@link ReactiveMongoMigration}: the database to migrate, and idempotent reactive helpers that work on
 * it. The reactive counterpart of {@link MigrationContext}, targeting the same database.
 * <p>
 * To work on another database, use {@link #client()}, optionally with the static {@link ReactiveMigrationHelpers}.
 * <p>
 * In a {@link Migration#transactional() transactional} migration, {@link #session()} returns the transaction's
 * session, the field helpers run in the transaction, and the schema helpers fail.
 */
public final class ReactiveMigrationContext {

    private final ReactiveMongoClient client;
    private final ReactiveMongoDatabase database;
    private final ClientSession session;
    private final ChangeCounter changes = new ChangeCounter();

    /**
     * Creates a context for a database of the given client, for example to call a migration from a test.
     */
    public ReactiveMigrationContext(ReactiveMongoClient client, String databaseName) {
        this(client, databaseName, null);
    }

    /**
     * Creates a context for a transactional migration, whose operations take part in the session's transaction.
     */
    public ReactiveMigrationContext(ReactiveMongoClient client, String databaseName, ClientSession session) {
        this.client = client;
        this.database = client.getDatabase(databaseName);
        this.session = session;
    }

    /**
     * The session of the transaction a {@link Migration#transactional() transactional} migration runs in. Pass it to
     * every driver call, for example {@code collection("orders").insertOne(context.session(), order)}; calls without
     * it run outside the transaction.
     *
     * @throws MigrationException if the migration is not transactional
     */
    public ClientSession session() {
        if (session == null) {
            throw new MigrationException("This migration does not run in a transaction. Set transactional = true on its"
                    + " @Migration annotation, or start a session yourself with client().startSession().");
        }
        return session;
    }

    /**
     * The client the database belongs to, for operations that need more than one database: sessions and multi-document
     * transactions ({@code client().startSession()}), working with other databases, or admin commands.
     */
    public ReactiveMongoClient client() {
        return client;
    }

    /**
     * The database to migrate, for anything the helpers don't cover.
     */
    public ReactiveMongoDatabase database() {
        return database;
    }

    /**
     * A collection of the database to migrate.
     */
    public ReactiveMongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    /**
     * @see ReactiveMigrationHelpers#collectionExists(ReactiveMongoDatabase, String)
     */
    public Uni<Boolean> collectionExists(String collection) {
        return ReactiveMigrationHelpers.collectionExists(database, collection);
    }

    /**
     * @see ReactiveMigrationHelpers#createCollection(ReactiveMongoDatabase, String)
     */
    public Uni<Void> createCollection(String collection) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("createCollection"));
        }
        return ReactiveMigrationHelpers.createCollection(database, collection);
    }

    /**
     * @see ReactiveMigrationHelpers#dropCollection(ReactiveMongoDatabase, String)
     */
    public Uni<Void> dropCollection(String collection) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("dropCollection"));
        }
        return ReactiveMigrationHelpers.dropCollection(database, collection);
    }

    /**
     * @see ReactiveMigrationHelpers#renameCollection(ReactiveMongoDatabase, String, String)
     */
    public Uni<Void> renameCollection(String from, String to) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("renameCollection"));
        }
        return ReactiveMigrationHelpers.renameCollection(database, from, to);
    }

    /**
     * @see ReactiveMigrationHelpers#indexExists(ReactiveMongoDatabase, String, String)
     */
    public Uni<Boolean> indexExists(String collection, String index) {
        return ReactiveMigrationHelpers.indexExists(database, collection, index);
    }

    /**
     * @see ReactiveMigrationHelpers#createIndex(ReactiveMongoDatabase, String, String, Bson)
     */
    public Uni<Void> createIndex(String collection, String index, Bson keys) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("createIndex"));
        }
        return ReactiveMigrationHelpers.createIndex(database, collection, index, keys);
    }

    /**
     * @see ReactiveMigrationHelpers#createIndex(ReactiveMongoDatabase, String, String, Bson, IndexOptions)
     */
    public Uni<Void> createIndex(String collection, String index, Bson keys, IndexOptions options) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("createIndex"));
        }
        return ReactiveMigrationHelpers.createIndex(database, collection, index, keys, options);
    }

    /**
     * @see ReactiveMigrationHelpers#createTtlIndex(ReactiveMongoDatabase, String, String, String, Duration)
     */
    public Uni<Void> createTtlIndex(String collection, String index, String field, Duration expireAfter) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("createTtlIndex"));
        }
        return ReactiveMigrationHelpers.createTtlIndex(database, collection, index, field, expireAfter);
    }

    /**
     * @see ReactiveMigrationHelpers#dropIndex(ReactiveMongoDatabase, String, String)
     */
    public Uni<Void> dropIndex(String collection, String index) {
        if (session != null) {
            return Uni.createFrom().failure(schemaChangeInTransaction("dropIndex"));
        }
        return ReactiveMigrationHelpers.dropIndex(database, collection, index);
    }

    /**
     * @see ReactiveMigrationHelpers#renameField(ReactiveMongoDatabase, String, String, String)
     */
    public Uni<Long> renameField(String collection, String from, String to) {
        Uni<Long> changed = session == null
                ? ReactiveMigrationHelpers.renameField(database, collection, from, to)
                : database.getCollection(collection).updateMany(session, exists(from), rename(from, to))
                        .map(UpdateResult::getModifiedCount);
        return changed.invoke(changes::add);
    }

    /**
     * @see ReactiveMigrationHelpers#removeField(ReactiveMongoDatabase, String, String)
     */
    public Uni<Long> removeField(String collection, String field) {
        Uni<Long> changed = session == null
                ? ReactiveMigrationHelpers.removeField(database, collection, field)
                : database.getCollection(collection).updateMany(session, exists(field), unset(field))
                        .map(UpdateResult::getModifiedCount);
        return changed.invoke(changes::add);
    }

    /**
     * Adds documents to the number this migration changed, which is logged and stored in the history. Use it for
     * changes made with the driver directly, for example {@code .invoke(context::recordChanges)}; the field
     * helpers count their own changes.
     */
    public void recordChanges(long documents) {
        changes.add(documents);
    }

    /**
     * Adds the documents an update modified (or inserted, for an upsert). See {@link #recordChanges(long)}.
     */
    public void recordChanges(UpdateResult result) {
        changes.add(result);
    }

    /**
     * Adds the documents a delete removed. See {@link #recordChanges(long)}.
     */
    public void recordChanges(DeleteResult result) {
        changes.add(result);
    }

    /**
     * Adds the inserted document. See {@link #recordChanges(long)}.
     */
    public void recordChanges(InsertOneResult result) {
        changes.add(result);
    }

    /**
     * Adds the inserted documents. See {@link #recordChanges(long)}.
     */
    public void recordChanges(InsertManyResult result) {
        changes.add(result);
    }

    /**
     * Adds the documents a bulk write inserted, modified, deleted or upserted. See {@link #recordChanges(long)}.
     */
    public void recordChanges(BulkWriteResult result) {
        changes.add(result);
    }

    /**
     * The number of documents recorded as changed so far, or empty if the migration recorded none: unknown, rather
     * than zero.
     */
    public OptionalLong documentsChanged() {
        return changes.total();
    }

    /**
     * MongoDB doesn't allow schema changes in a transaction; failing early gives a clearer error than the server's.
     */
    private static MigrationException schemaChangeInTransaction(String helper) {
        return new MigrationException(helper + " changes the schema, which MongoDB doesn't allow in a transaction."
                + " Do it in a separate migration that is not transactional.");
    }
}
