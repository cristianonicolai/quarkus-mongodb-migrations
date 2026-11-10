package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.rename;
import static com.mongodb.client.model.Updates.unset;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.bson.conversions.Bson;

import com.mongodb.MongoNamespace;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.UpdateResult;

import io.quarkus.mongodb.CollectionListOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoDatabase;
import io.smallrye.mutiny.Uni;

/**
 * Reactive counterparts of {@link MigrationHelpers}. Each helper can run again safely: it skips work that is already
 * done, so a migration that failed halfway can be retried.
 * <p>
 * Intended to be statically imported, for example {@code createIndex(db, "users", "email_idx", ascending("email"))}.
 */
public final class ReactiveMigrationHelpers {

    private ReactiveMigrationHelpers() {
    }

    /**
     * Whether the collection exists in the database.
     */
    public static Uni<Boolean> collectionExists(ReactiveMongoDatabase database, String collection) {
        return database.listCollections(new CollectionListOptions().filter(eq("name", collection)))
                .collect().first()
                .map(Objects::nonNull);
    }

    /**
     * Creates the collection unless it already exists.
     */
    public static Uni<Void> createCollection(ReactiveMongoDatabase database, String collection) {
        return collectionExists(database, collection)
                .chain(exists -> exists ? Uni.createFrom().voidItem() : database.createCollection(collection));
    }

    /**
     * Drops the collection, if it exists.
     */
    public static Uni<Void> dropCollection(ReactiveMongoDatabase database, String collection) {
        return database.getCollection(collection).drop();
    }

    /**
     * Renames a collection, unless the source no longer exists (because it was already renamed).
     */
    public static Uni<Void> renameCollection(ReactiveMongoDatabase database, String from, String to) {
        return collectionExists(database, from)
                .chain(exists -> exists
                        ? database.getCollection(from).renameCollection(new MongoNamespace(database.getName(), to))
                        : Uni.createFrom().voidItem());
    }

    /**
     * Whether an index with this name exists on the collection.
     */
    public static Uni<Boolean> indexExists(ReactiveMongoDatabase database, String collection, String index) {
        return collectionExists(database, collection)
                .chain(exists -> exists
                        ? database.getCollection(collection).listIndexes()
                                .select().where(existing -> index.equals(existing.getString("name")))
                                .collect().first()
                                .map(Objects::nonNull)
                        : Uni.createFrom().item(false));
    }

    /**
     * Creates an index with the given name, unless an index with that name already exists.
     */
    public static Uni<Void> createIndex(ReactiveMongoDatabase database, String collection, String index, Bson keys) {
        return createIndex(database, collection, index, keys, new IndexOptions());
    }

    /**
     * Creates an index with the given name and options, unless an index with that name already exists. The name
     * overrides any name set in {@code options}.
     */
    public static Uni<Void> createIndex(ReactiveMongoDatabase database, String collection, String index, Bson keys,
            IndexOptions options) {
        return indexExists(database, collection, index)
                .chain(exists -> exists
                        ? Uni.createFrom().voidItem()
                        : database.getCollection(collection).createIndex(keys, options.name(index)).replaceWithVoid());
    }

    /**
     * Creates a TTL index on a date field, unless an index with that name already exists. Documents are removed once
     * the field's value is older than {@code expireAfter}.
     */
    public static Uni<Void> createTtlIndex(ReactiveMongoDatabase database, String collection, String index, String field,
            Duration expireAfter) {
        return createIndex(database, collection, index, Indexes.ascending(field),
                new IndexOptions().expireAfter(expireAfter.toSeconds(), TimeUnit.SECONDS));
    }

    /**
     * Drops the index with the given name, if it exists.
     */
    public static Uni<Void> dropIndex(ReactiveMongoDatabase database, String collection, String index) {
        return indexExists(database, collection, index)
                .chain(exists -> exists
                        ? database.getCollection(collection).dropIndex(index)
                        : Uni.createFrom().voidItem());
    }

    /**
     * Renames a field in every document that has it.
     *
     * @return the number of documents changed
     */
    public static Uni<Long> renameField(ReactiveMongoDatabase database, String collection, String from, String to) {
        return database.getCollection(collection).updateMany(exists(from), rename(from, to))
                .map(UpdateResult::getModifiedCount);
    }

    /**
     * Removes a field from every document that has it.
     *
     * @return the number of documents changed
     */
    public static Uni<Long> removeField(ReactiveMongoDatabase database, String collection, String field) {
        return database.getCollection(collection).updateMany(exists(field), unset(field))
                .map(UpdateResult::getModifiedCount);
    }
}
