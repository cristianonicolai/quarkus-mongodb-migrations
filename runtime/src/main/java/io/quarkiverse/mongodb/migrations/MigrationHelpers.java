package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.rename;
import static com.mongodb.client.model.Updates.unset;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Idempotent helpers for common blocking migration steps. Each helper can run again safely: it skips work that is
 * already done, so a migration that failed halfway can be retried.
 * <p>
 * Intended to be statically imported, for example {@code createIndex(db, "users", "email_idx", ascending("email"))}.
 */
public final class MigrationHelpers {

    private MigrationHelpers() {
    }

    /**
     * Whether the collection exists in the database.
     */
    public static boolean collectionExists(MongoDatabase database, String collection) {
        return database.listCollectionNames().filter(eq("name", collection)).first() != null;
    }

    /**
     * Creates the collection unless it already exists.
     */
    public static void createCollection(MongoDatabase database, String collection) {
        if (!collectionExists(database, collection)) {
            database.createCollection(collection);
        }
    }

    /**
     * Drops the collection, if it exists.
     */
    public static void dropCollection(MongoDatabase database, String collection) {
        database.getCollection(collection).drop();
    }

    /**
     * Renames a collection, unless the source no longer exists (because it was already renamed).
     */
    public static void renameCollection(MongoDatabase database, String from, String to) {
        if (collectionExists(database, from)) {
            database.getCollection(from).renameCollection(new MongoNamespace(database.getName(), to));
        }
    }

    /**
     * Whether an index with this name exists on the collection.
     */
    public static boolean indexExists(MongoDatabase database, String collection, String index) {
        if (!collectionExists(database, collection)) {
            return false;
        }
        for (Document existing : database.getCollection(collection).listIndexes()) {
            if (index.equals(existing.getString("name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates an index with the given name, unless an index with that name already exists.
     */
    public static void createIndex(MongoDatabase database, String collection, String index, Bson keys) {
        createIndex(database, collection, index, keys, new IndexOptions());
    }

    /**
     * Creates an index with the given name and options, unless an index with that name already exists. The name
     * overrides any name set in {@code options}.
     */
    public static void createIndex(MongoDatabase database, String collection, String index, Bson keys,
            IndexOptions options) {
        if (!indexExists(database, collection, index)) {
            database.getCollection(collection).createIndex(keys, options.name(index));
        }
    }

    /**
     * Creates a TTL index on a date field, unless an index with that name already exists. Documents are removed once
     * the field's value is older than {@code expireAfter}.
     */
    public static void createTtlIndex(MongoDatabase database, String collection, String index, String field,
            Duration expireAfter) {
        createIndex(database, collection, index, Indexes.ascending(field),
                new IndexOptions().expireAfter(expireAfter.toSeconds(), TimeUnit.SECONDS));
    }

    /**
     * Drops the index with the given name, if it exists.
     */
    public static void dropIndex(MongoDatabase database, String collection, String index) {
        if (indexExists(database, collection, index)) {
            database.getCollection(collection).dropIndex(index);
        }
    }

    /**
     * Renames a field in every document that has it.
     *
     * @return the number of documents changed
     */
    public static long renameField(MongoDatabase database, String collection, String from, String to) {
        return database.getCollection(collection).updateMany(exists(from), rename(from, to)).getModifiedCount();
    }

    /**
     * Removes a field from every document that has it.
     *
     * @return the number of documents changed
     */
    public static long removeField(MongoDatabase database, String collection, String field) {
        return database.getCollection(collection).updateMany(exists(field), unset(field)).getModifiedCount();
    }
}
