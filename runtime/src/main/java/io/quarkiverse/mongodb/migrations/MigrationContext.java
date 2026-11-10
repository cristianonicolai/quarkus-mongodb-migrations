package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.rename;
import static com.mongodb.client.model.Updates.unset;

import java.time.Duration;
import java.util.OptionalLong;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;

/**
 * Passed to a {@link MongoMigration}: the database to migrate, and idempotent helpers that work on it.
 * <p>
 * The database is the one configured for the MongoDB client ({@code quarkus.mongodb.database}, or the database of the
 * client named by {@code quarkus.mongodb-migrations.mongo-client-name}). Every helper can run again safely: it skips
 * work that is already done, so a migration that failed halfway can be retried. To work on another database, use
 * {@link #client()}, optionally with the static {@link MigrationHelpers}.
 * <p>
 * In a {@link Migration#transactional() transactional} migration, {@link #session()} returns the transaction's
 * session, the field helpers run in the transaction, and the schema helpers refuse to run.
 */
public final class MigrationContext {

    private final MongoClient client;
    private final MongoDatabase database;
    private final ClientSession session;
    private final ChangeCounter changes = new ChangeCounter();

    /**
     * Creates a context for a database of the given client, for example to call a migration from a test.
     */
    public MigrationContext(MongoClient client, String databaseName) {
        this(client, databaseName, null);
    }

    /**
     * Creates a context for a transactional migration, whose operations take part in the session's transaction.
     */
    public MigrationContext(MongoClient client, String databaseName, ClientSession session) {
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
    public MongoClient client() {
        return client;
    }

    /**
     * The database to migrate, for anything the helpers don't cover.
     */
    public MongoDatabase database() {
        return database;
    }

    /**
     * A collection of the database to migrate.
     */
    public MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    /**
     * @see MigrationHelpers#collectionExists(MongoDatabase, String)
     */
    public boolean collectionExists(String collection) {
        return MigrationHelpers.collectionExists(database, collection);
    }

    /**
     * @see MigrationHelpers#createCollection(MongoDatabase, String)
     */
    public void createCollection(String collection) {
        requireNoTransaction("createCollection");
        MigrationHelpers.createCollection(database, collection);
    }

    /**
     * @see MigrationHelpers#dropCollection(MongoDatabase, String)
     */
    public void dropCollection(String collection) {
        requireNoTransaction("dropCollection");
        MigrationHelpers.dropCollection(database, collection);
    }

    /**
     * @see MigrationHelpers#renameCollection(MongoDatabase, String, String)
     */
    public void renameCollection(String from, String to) {
        requireNoTransaction("renameCollection");
        MigrationHelpers.renameCollection(database, from, to);
    }

    /**
     * @see MigrationHelpers#indexExists(MongoDatabase, String, String)
     */
    public boolean indexExists(String collection, String index) {
        return MigrationHelpers.indexExists(database, collection, index);
    }

    /**
     * @see MigrationHelpers#createIndex(MongoDatabase, String, String, Bson)
     */
    public void createIndex(String collection, String index, Bson keys) {
        requireNoTransaction("createIndex");
        MigrationHelpers.createIndex(database, collection, index, keys);
    }

    /**
     * @see MigrationHelpers#createIndex(MongoDatabase, String, String, Bson, IndexOptions)
     */
    public void createIndex(String collection, String index, Bson keys, IndexOptions options) {
        requireNoTransaction("createIndex");
        MigrationHelpers.createIndex(database, collection, index, keys, options);
    }

    /**
     * @see MigrationHelpers#createTtlIndex(MongoDatabase, String, String, String, Duration)
     */
    public void createTtlIndex(String collection, String index, String field, Duration expireAfter) {
        requireNoTransaction("createTtlIndex");
        MigrationHelpers.createTtlIndex(database, collection, index, field, expireAfter);
    }

    /**
     * @see MigrationHelpers#dropIndex(MongoDatabase, String, String)
     */
    public void dropIndex(String collection, String index) {
        requireNoTransaction("dropIndex");
        MigrationHelpers.dropIndex(database, collection, index);
    }

    /**
     * @see MigrationHelpers#renameField(MongoDatabase, String, String, String)
     */
    public long renameField(String collection, String from, String to) {
        long changed = session == null
                ? MigrationHelpers.renameField(database, collection, from, to)
                : database.getCollection(collection).updateMany(session, exists(from), rename(from, to))
                        .getModifiedCount();
        changes.add(changed);
        return changed;
    }

    /**
     * @see MigrationHelpers#removeField(MongoDatabase, String, String)
     */
    public long removeField(String collection, String field) {
        long changed = session == null
                ? MigrationHelpers.removeField(database, collection, field)
                : database.getCollection(collection).updateMany(session, exists(field), unset(field)).getModifiedCount();
        changes.add(changed);
        return changed;
    }

    /**
     * Adds documents to the number this migration changed, which is logged and stored in the history. Use it for
     * changes made with the driver directly; the field helpers count their own changes.
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
    private void requireNoTransaction(String helper) {
        if (session != null) {
            throw new MigrationException(helper + " changes the schema, which MongoDB doesn't allow in a transaction."
                    + " Do it in a separate migration that is not transactional.");
        }
    }
}
