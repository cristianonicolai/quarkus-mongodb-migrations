package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.rename;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.unset;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;

/**
 * Passed to a {@link MongoMigration}: the database to migrate, and helpers that work on it.
 * <p>
 * The database is the one configured for the MongoDB client ({@code quarkus.mongodb.database}, or the database of the
 * client named by {@code quarkus.mongodb-migrations.mongo-client-name}). The collection, index, field and array helpers
 * can run again safely: they skip work that is already done, so a migration that failed halfway can be retried.
 * {@link #updateMany(String, Bson, Bson) updateMany}, {@link #deleteMany(String, Bson) deleteMany} and
 * {@link #updateEach(String, Bson, java.util.function.Function) updateEach} run the update they are given, so its
 * author makes it safe to repeat. To work on other databases, use {@link #forDatabase(String)},
 * {@link #forEachDatabase(Predicate, Consumer)} or {@link #client()}.
 * <p>
 * In a {@link Migration#transactional() transactional} migration, {@link #session()} returns the transaction's
 * session, the data helpers run in the transaction, and the collection and index helpers refuse to run.
 */
public final class MigrationContext {

    private final MongoClient client;
    private final MongoDatabase database;
    private final ClientSession session;
    private final ChangeCounter changes;

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
        this(client, databaseName, session, new ChangeCounter());
    }

    /**
     * A context for another database of the same migration, sharing its session and change count.
     */
    private MigrationContext(MongoClient client, String databaseName, ClientSession session, ChangeCounter changes) {
        this.client = client;
        this.database = client.getDatabase(databaseName);
        this.session = session;
        this.changes = changes;
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
     * Sets a field to the same value on every document matching the filter, for example
     * {@code setField("tenants", empty(), "deleted", false)}. Counts as changed documents.
     *
     * @return the number of documents changed
     */
    public long setField(String collection, Bson filter, String field, Object value) {
        return update(collection, filter, set(field, value), new UpdateOptions());
    }

    /**
     * Sets several fields on every document matching the filter, in one update per document. Counts as changed
     * documents.
     *
     * @return the number of documents changed
     */
    public long setFields(String collection, Bson filter, Map<String, ?> values) {
        return update(collection, filter, DataOperations.setFields(values), new UpdateOptions());
    }

    /**
     * Sets a field on every document where it is missing or null, leaving documents that already have a value alone.
     * Safe to run again. Counts as changed documents.
     *
     * @return the number of documents changed
     */
    public long setFieldIfMissing(String collection, String field, Object value) {
        return setFieldIfMissing(collection, null, field, value);
    }

    /**
     * Like {@link #setFieldIfMissing(String, String, Object)}, limited to documents matching the filter, for example
     * {@code setFieldIfMissing("plans", exists("mapsSettings"), "mapsSettings.allowTraffic", false)}.
     *
     * @return the number of documents changed
     */
    public long setFieldIfMissing(String collection, Bson filter, String field, Object value) {
        return update(collection, DataOperations.missing(filter, field), set(field, value), new UpdateOptions());
    }

    /**
     * Sets a field on every element of an array of documents, for example
     * {@code setFieldInArray("tenants", "members", "status", "ACTIVE")} sets {@code members[*].status}. Documents
     * without the array are skipped. Counts as changed documents.
     *
     * @return the number of documents changed
     */
    public long setFieldInArray(String collection, String arrayField, String field, Object value) {
        return arrayUpdate(collection, DataOperations.setInArray(arrayField, null, field, value, registry()));
    }

    /**
     * Sets a field on the elements of an array of documents that match the element filter, written on the elements'
     * own fields, for example {@code setFieldInArray("tenants", "apiKeys", eq("name", "platform"), "type", "PLATFORM")}.
     * Counts as changed documents.
     *
     * @return the number of documents changed
     */
    public long setFieldInArray(String collection, String arrayField, Bson elementFilter, String field, Object value) {
        return arrayUpdate(collection, DataOperations.setInArray(arrayField, elementFilter, field, value, registry()));
    }

    /**
     * Sets a field on the elements of an array of documents where it is missing or null, leaving elements that already
     * have a value alone. Safe to run again. Counts as changed documents.
     *
     * @return the number of documents changed
     */
    public long setFieldInArrayIfMissing(String collection, String arrayField, String field, Object value) {
        return arrayUpdate(collection, DataOperations.setInArrayIfMissing(arrayField, field, value, registry()));
    }

    /**
     * Removes a field from every element of an array of documents that has it. Counts as changed documents.
     *
     * @return the number of documents changed
     */
    public long removeFieldInArray(String collection, String arrayField, String field) {
        return arrayUpdate(collection, DataOperations.removeInArray(arrayField, field, registry()));
    }

    /**
     * Updates each document matching the filter with an update computed from the document itself, for values that
     * differ per document. Return {@code null} to leave a document unchanged. Documents are streamed and written in
     * batches, so memory stays flat on large collections. Counts as changed documents. It runs the update as given, so make it
     * safe to
     * repeat for a migration that may be retried, for example with a filter that skips documents already migrated.
     *
     * @return the number of documents changed
     */
    public long updateEach(String collection, Bson filter, Function<Document, Bson> update) {
        return updateEach(collection, filter, null, update);
    }

    /**
     * Like {@link #updateEach(String, Bson, Function)}, reading only the fields in the projection. It must keep {@code _id}
     * as it is: leave it out or include it, but don't exclude or compute it.
     *
     * @return the number of documents changed
     */
    public long updateEach(String collection, Bson filter, Bson projection, Function<Document, Bson> update) {
        MongoCollection<Document> target = database.getCollection(collection);
        DataOperations.requireIdKept(projection, target.getCodecRegistry());
        // Sorted by _id, which updates can't change, so no document is visited twice while the collection changes.
        FindIterable<Document> documents = (session == null ? target.find(filter) : target.find(session, filter))
                .sort(Sorts.ascending("_id"))
                .batchSize(DataOperations.BATCH_SIZE);
        if (projection != null) {
            documents.projection(projection);
        }
        long changed = 0;
        List<WriteModel<Document>> batch = new ArrayList<>();
        try (MongoCursor<Document> cursor = documents.iterator()) {
            while (cursor.hasNext()) {
                Document document = cursor.next();
                // Read before the callback, so changing the document can't redirect the write to another one.
                Object id = DataOperations.id(document);
                Bson documentUpdate = update.apply(document);
                if (documentUpdate == null) {
                    continue;
                }
                batch.add(new UpdateOneModel<>(eq("_id", id), documentUpdate));
                if (batch.size() == DataOperations.BATCH_SIZE) {
                    changed += bulkWrite(target, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            changed += bulkWrite(target, batch);
        }
        return changed;
    }

    /**
     * A context for another database of the same client, for example
     * {@code forDatabase("audit").setFieldIfMissing("events", "version", 1)}. Changes made through it count towards this
     * migration, and in a transactional migration they take part in its transaction. The database doesn't have to exist
     * yet.
     */
    public MigrationContext forDatabase(String databaseName) {
        return new MigrationContext(client, databaseName, session, changes);
    }

    /**
     * Runs an action for each database whose name matches, with a context for that database. Useful when data is
     * split across databases, for example one per tenant:
     * {@code forEachDatabase(name -> name.startsWith("tenant-"), tenant -> tenant.setFieldIfMissing("events", "v", 1))}.
     * <p>
     * MongoDB's own databases ({@code admin}, {@code local} and {@code config}) are never included. Changes made through
     * the database contexts count towards this migration, and in a transactional migration they take part in its
     * transaction. Listing databases needs the {@code listDatabases} privilege.
     */
    public void forEachDatabase(Predicate<String> name, Consumer<MigrationContext> action) {
        for (String databaseName : DataOperations.databaseNames(client.listDatabaseNames(), name)) {
            action.accept(new MigrationContext(client, databaseName, session, changes));
        }
    }

    /**
     * Runs an action for each existing collection of the database whose name matches, for example
     * {@code forEachCollection(name -> name.endsWith("-events"), collection -> createIndex(collection, ...))}.
     * MongoDB's own {@code system.*} collections are never included.
     */
    public void forEachCollection(Predicate<String> name, Consumer<String> action) {
        for (String collection : DataOperations.collectionNames(database.listCollectionNames(), name)) {
            action.accept(collection);
        }
    }

    /**
     * Updates every document matching the filter, like the driver's {@code updateMany}, but running in the transaction
     * of a transactional migration and counting the changed documents automatically. It runs the update as given, so make it
     * safe to
     * repeat for a migration that may be retried, for example with a filter that skips documents already migrated.
     *
     * @return the number of documents changed
     */
    public long updateMany(String collection, Bson filter, Bson update) {
        return update(collection, filter, update, new UpdateOptions());
    }

    /**
     * Updates every document matching the filter with an aggregation pipeline, for updates that use the document's
     * own values, such as converting a field's type or copying one field to another:
     * {@code updateMany("runs", empty(), List.of(set("tenantId", new Document("$toString", "$tenantId"))))}. Runs in the
     * transaction of a transactional migration and counts the changed documents automatically. It runs the update as given, so
     * make it safe to
     * repeat for a migration that may be retried, for example with a filter that skips documents already migrated.
     *
     * @return the number of documents changed
     */
    public long updateMany(String collection, Bson filter, List<? extends Bson> pipeline) {
        MongoCollection<Document> target = database.getCollection(collection);
        UpdateResult result = session == null
                ? target.updateMany(filter, pipeline)
                : target.updateMany(session, filter, pipeline);
        changes.add(result);
        return result.wasAcknowledged() ? result.getModifiedCount() : 0;
    }

    /**
     * Deletes every document matching the filter, like the driver's {@code deleteMany}, but running in the transaction
     * of a transactional migration and counting the deleted documents automatically.
     *
     * @return the number of documents deleted
     */
    public long deleteMany(String collection, Bson filter) {
        MongoCollection<Document> target = database.getCollection(collection);
        DeleteResult result = session == null ? target.deleteMany(filter) : target.deleteMany(session, filter);
        changes.add(result);
        return result.wasAcknowledged() ? result.getDeletedCount() : 0;
    }

    private long arrayUpdate(String collection, DataOperations.ArrayUpdate arrayUpdate) {
        return update(collection, arrayUpdate.documentFilter(), arrayUpdate.update(), arrayUpdate.options());
    }

    private long update(String collection, Bson filter, Bson update, UpdateOptions options) {
        MongoCollection<Document> target = database.getCollection(collection);
        UpdateResult result = session == null
                ? target.updateMany(filter, update, options)
                : target.updateMany(session, filter, update, options);
        changes.add(result);
        return result.wasAcknowledged() ? result.getModifiedCount() : 0;
    }

    private long bulkWrite(MongoCollection<Document> target, List<WriteModel<Document>> batch) {
        BulkWriteResult result = session == null ? target.bulkWrite(batch) : target.bulkWrite(session, batch);
        changes.add(result);
        return result.wasAcknowledged() ? result.getModifiedCount() : 0;
    }

    private CodecRegistry registry() {
        return database.getCodecRegistry();
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
