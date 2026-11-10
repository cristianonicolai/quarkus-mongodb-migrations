package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.rename;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.unset;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.ClientSession;

import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.quarkus.mongodb.reactive.ReactiveMongoDatabase;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Passed to a {@link ReactiveMongoMigration}: the database to migrate, and reactive helpers that work on it. The
 * reactive counterpart of {@link MigrationContext}, targeting the same database.
 * <p>
 * The collection, index, field and array helpers can run again safely: they skip work that is already done.
 * {@code updateMany}, {@code deleteMany} and {@code updateEach} run the update they are given, so its author makes it
 * safe to repeat. To work on other databases, use {@link #forDatabase(String)}, {@code forEachDatabase} or
 * {@link #client()}.
 * <p>
 * In a {@link Migration#transactional() transactional} migration, {@link #session()} returns the transaction's
 * session, the data helpers run in the transaction, and the collection and index helpers fail.
 */
public final class ReactiveMigrationContext {

    private final ReactiveMongoClient client;
    private final ReactiveMongoDatabase database;
    private final ClientSession session;
    private final ChangeCounter changes;

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
        this(client, databaseName, session, new ChangeCounter());
    }

    /**
     * A context for another database of the same migration, sharing its session and change count.
     */
    private ReactiveMigrationContext(ReactiveMongoClient client, String databaseName, ClientSession session,
            ChangeCounter changes) {
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
     * Sets a field to the same value on every document matching the filter. Counts as changed documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#setField(String, Bson, String, Object)
     */
    public Uni<Long> setField(String collection, Bson filter, String field, Object value) {
        return update(collection, filter, set(field, value), new UpdateOptions());
    }

    /**
     * Sets several fields on every document matching the filter. Counts as changed documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#setFields(String, Bson, Map)
     */
    public Uni<Long> setFields(String collection, Bson filter, Map<String, ?> values) {
        return update(collection, filter, DataOperations.setFields(values), new UpdateOptions());
    }

    /**
     * Sets a field on every document where it is missing or null. Safe to run again. Counts as changed documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#setFieldIfMissing(String, String, Object)
     */
    public Uni<Long> setFieldIfMissing(String collection, String field, Object value) {
        return setFieldIfMissing(collection, null, field, value);
    }

    /**
     * Like {@link #setFieldIfMissing(String, String, Object)}, limited to documents matching the filter.
     *
     * @return the number of documents changed
     */
    public Uni<Long> setFieldIfMissing(String collection, Bson filter, String field, Object value) {
        return update(collection, DataOperations.missing(filter, field), set(field, value), new UpdateOptions());
    }

    /**
     * Sets a field on every element of an array of documents. Counts as changed documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#setFieldInArray(String, String, String, Object)
     */
    public Uni<Long> setFieldInArray(String collection, String arrayField, String field, Object value) {
        return arrayUpdate(collection, DataOperations.setInArray(arrayField, null, field, value, registry(collection)));
    }

    /**
     * Sets a field on the elements of an array of documents that match the element filter. Counts as changed
     * documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#setFieldInArray(String, String, Bson, String, Object)
     */
    public Uni<Long> setFieldInArray(String collection, String arrayField, Bson elementFilter, String field,
            Object value) {
        return arrayUpdate(collection,
                DataOperations.setInArray(arrayField, elementFilter, field, value, registry(collection)));
    }

    /**
     * Sets a field on the elements of an array of documents where it is missing or null. Counts as changed documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#setFieldInArrayIfMissing(String, String, String, Object)
     */
    public Uni<Long> setFieldInArrayIfMissing(String collection, String arrayField, String field, Object value) {
        return arrayUpdate(collection, DataOperations.setInArrayIfMissing(arrayField, field, value, registry(collection)));
    }

    /**
     * Removes a field from every element of an array of documents that has it. Counts as changed documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#removeFieldInArray(String, String, String)
     */
    public Uni<Long> removeFieldInArray(String collection, String arrayField, String field) {
        return arrayUpdate(collection, DataOperations.removeInArray(arrayField, field, registry(collection)));
    }

    /**
     * Updates each document matching the filter with an update computed from the document itself. Return
     * {@code null} to leave a document unchanged. Documents are streamed and written in batches. Counts as changed
     * documents.
     *
     * @return the number of documents changed
     * @see MigrationContext#updateEach(String, Bson, Function)
     */
    public Uni<Long> updateEach(String collection, Bson filter, Function<Document, Bson> update) {
        return updateEach(collection, filter, null, update);
    }

    /**
     * Like {@link #updateEach(String, Bson, Function)}, reading only the fields in the projection. It must keep {@code _id}
     * as it is: leave it out or include it, but don't exclude or compute it.
     *
     * @return the number of documents changed
     */
    public Uni<Long> updateEach(String collection, Bson filter, Bson projection, Function<Document, Bson> update) {
        ReactiveMongoCollection<Document> target = database.getCollection(collection);
        try {
            DataOperations.requireIdKept(projection, target.getCodecRegistry());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().failure(e);
        }
        // Sorted by _id, which updates can't change, so no document is visited twice while the collection changes.
        FindOptions options = new FindOptions().sort(Sorts.ascending("_id")).batchSize(DataOperations.BATCH_SIZE);
        if (projection != null) {
            options.projection(projection);
        }
        Multi<Document> documents = session == null ? target.find(filter, options) : target.find(session, filter, options);
        // Memory stays bounded by backpressure: the concatenation requests one batch at a time, so at most one batch of
        // documents is read ahead of the writes.
        return documents
                .onItem().transformToIterable(document -> {
                    // Read before the callback, so changing the document can't redirect the write to another one.
                    Object id = DataOperations.id(document);
                    Bson documentUpdate = update.apply(document);
                    return documentUpdate == null
                            ? List.<WriteModel<Document>> of()
                            : List.<WriteModel<Document>> of(new UpdateOneModel<>(eq("_id", id), documentUpdate));
                })
                .group().intoLists().of(DataOperations.BATCH_SIZE)
                .onItem().transformToUniAndConcatenate(batch -> bulkWrite(target, batch))
                .collect().with(Collectors.summingLong(Long::longValue));
    }

    /**
     * A context for another database of the same client. Changes made through it count towards this migration, and in a
     * transactional migration they take part in its transaction. The database doesn't have to exist yet.
     *
     * @see MigrationContext#forDatabase(String)
     */
    public ReactiveMigrationContext forDatabase(String databaseName) {
        return new ReactiveMigrationContext(client, databaseName, session, changes);
    }

    /**
     * Runs an action for each database whose name matches, one at a time, with a context for that database. MongoDB's
     * own databases ({@code admin}, {@code local} and {@code config}) are never included. Changes made through the
     * database contexts count towards this migration, and in a transactional migration they take part in its
     * transaction.
     *
     * @see MigrationContext#forEachDatabase(Predicate, java.util.function.Consumer)
     */
    public Uni<Void> forEachDatabase(Predicate<String> name, Function<ReactiveMigrationContext, Uni<Void>> action) {
        return client.listDatabaseNames().collect().asList()
                .onItem().transformToMulti(names -> Multi.createFrom().iterable(DataOperations.databaseNames(names, name)))
                .onItem().transformToUniAndConcatenate(
                        databaseName -> action.apply(new ReactiveMigrationContext(client, databaseName, session, changes)))
                .collect().last()
                .replaceWithVoid();
    }

    /**
     * Runs an action for each existing collection of the database whose name matches, one at a time. MongoDB's own
     * {@code system.*} collections are never included.
     *
     * @see MigrationContext#forEachCollection(Predicate, java.util.function.Consumer)
     */
    public Uni<Void> forEachCollection(Predicate<String> name, Function<String, Uni<Void>> action) {
        return database.listCollectionNames().collect().asList()
                .onItem().transformToMulti(names -> Multi.createFrom().iterable(DataOperations.collectionNames(names, name)))
                .onItem().transformToUniAndConcatenate(collection -> action.apply(collection))
                .collect().last()
                .replaceWithVoid();
    }

    /**
     * Updates every document matching the filter, running in the transaction of a transactional migration and counting
     * the changed documents automatically.
     *
     * @return the number of documents changed
     * @see MigrationContext#updateMany(String, Bson, Bson)
     */
    public Uni<Long> updateMany(String collection, Bson filter, Bson update) {
        return update(collection, filter, update, new UpdateOptions());
    }

    /**
     * Updates every document matching the filter with an aggregation pipeline, running in the transaction of a
     * transactional migration and counting the changed documents automatically.
     *
     * @return the number of documents changed
     * @see MigrationContext#updateMany(String, Bson, List)
     */
    public Uni<Long> updateMany(String collection, Bson filter, List<? extends Bson> pipeline) {
        ReactiveMongoCollection<Document> target = database.getCollection(collection);
        Uni<UpdateResult> result = session == null
                ? target.updateMany(filter, pipeline)
                : target.updateMany(session, filter, pipeline);
        return result.invoke(changes::add).map(r -> r.wasAcknowledged() ? r.getModifiedCount() : 0L);
    }

    /**
     * Deletes every document matching the filter, running in the transaction of a transactional migration and counting
     * the deleted documents automatically.
     *
     * @return the number of documents deleted
     * @see MigrationContext#deleteMany(String, Bson)
     */
    public Uni<Long> deleteMany(String collection, Bson filter) {
        ReactiveMongoCollection<Document> target = database.getCollection(collection);
        Uni<DeleteResult> result = session == null ? target.deleteMany(filter) : target.deleteMany(session, filter);
        return result.invoke(changes::add).map(r -> r.wasAcknowledged() ? r.getDeletedCount() : 0L);
    }

    private Uni<Long> arrayUpdate(String collection, DataOperations.ArrayUpdate arrayUpdate) {
        return update(collection, arrayUpdate.documentFilter(), arrayUpdate.update(), arrayUpdate.options());
    }

    private Uni<Long> update(String collection, Bson filter, Bson update, UpdateOptions options) {
        ReactiveMongoCollection<Document> target = database.getCollection(collection);
        Uni<UpdateResult> result = session == null
                ? target.updateMany(filter, update, options)
                : target.updateMany(session, filter, update, options);
        return result.invoke(changes::add).map(r -> r.wasAcknowledged() ? r.getModifiedCount() : 0L);
    }

    private Uni<Long> bulkWrite(ReactiveMongoCollection<Document> target, List<WriteModel<Document>> batch) {
        Uni<BulkWriteResult> result = session == null ? target.bulkWrite(batch) : target.bulkWrite(session, batch);
        return result.invoke(changes::add).map(r -> r.wasAcknowledged() ? (long) r.getModifiedCount() : 0L);
    }

    private CodecRegistry registry(String collection) {
        return database.getCollection(collection).getCodecRegistry();
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
