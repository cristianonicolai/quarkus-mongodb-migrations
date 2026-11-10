package io.quarkiverse.mongodb.migrations;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.elemMatch;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.type;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.unset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;

import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

/**
 * Builds the filters and updates behind the contexts' data helpers, shared by the blocking and reactive contexts.
 */
final class DataOperations {

    /** Batch size of {@code updateEach}: large enough to be fast, small enough to keep memory flat. */
    static final int BATCH_SIZE = 1000;

    private static final String ELEMENT = "element";

    private DataOperations() {
    }

    /**
     * Rejects a projection that excludes or replaces {@code _id}, such as {@code {_id: 0}} or
     * {@code {_id: {$literal: 2}}}: {@code updateEach} writes each document back by the {@code _id} it read, so a
     * changed one would update another document. Leaving {@code _id} out of the projection, or including it with a
     * truthy value, keeps it as it is.
     */
    static void requireIdKept(Bson projection, CodecRegistry registry) {
        if (projection == null) {
            return;
        }
        BsonValue id = projection.toBsonDocument(Document.class, registry).get("_id");
        boolean kept = id == null
                || (id.isBoolean() && id.asBoolean().getValue())
                || (id.isNumber() && id.asNumber().doubleValue() != 0);
        if (!kept) {
            throw new IllegalArgumentException("updateEach writes each document back by its _id, so the projection must"
                    + " keep _id as it is (leave it out, or include it with _id: 1), not " + id);
        }
    }

    /**
     * The id {@code updateEach} writes each document back by, which a projection must not exclude.
     */
    static Object id(Document document) {
        Object id = document.get("_id");
        if (id == null) {
            throw new IllegalArgumentException("updateEach needs each document's _id; don't exclude it from the projection");
        }
        return id;
    }

    /**
     * The names that match, without MongoDB's own databases.
     */
    static List<String> databaseNames(Iterable<String> names, Predicate<String> filter) {
        List<String> matching = new ArrayList<>();
        for (String name : names) {
            if (!isSystemDatabase(name) && filter.test(name)) {
                matching.add(name);
            }
        }
        return matching;
    }

    /**
     * The names that match, without MongoDB's own {@code system.*} collections.
     */
    static List<String> collectionNames(Iterable<String> names, Predicate<String> filter) {
        List<String> matching = new ArrayList<>();
        for (String name : names) {
            if (!isSystemCollection(name) && filter.test(name)) {
                matching.add(name);
            }
        }
        return matching;
    }

    static boolean isSystemDatabase(String name) {
        return name.equals("admin") || name.equals("local") || name.equals("config");
    }

    static boolean isSystemCollection(String name) {
        return name.startsWith("system.");
    }

    static Bson setFields(Map<String, ?> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("No fields to set");
        }
        return Updates.combine(values.entrySet().stream().map(entry -> set(entry.getKey(), entry.getValue())).toList());
    }

    /**
     * Documents matching {@code filter} (or all, when {@code null}) where {@code field} is missing or null.
     */
    static Bson missing(Bson filter, String field) {
        return filter == null ? eq(field, null) : and(filter, eq(field, null));
    }

    /**
     * An update on array elements.
     *
     * @param documentFilter only documents whose array has an element to update, so documents without the array are
     *        skipped rather than failing the update
     * @param update the update, on {@code array.$[].field} or {@code array.$[element].field}
     * @param options the array filter selecting the elements, if not all of them
     */
    record ArrayUpdate(Bson documentFilter, Bson update, UpdateOptions options) {
    }

    /**
     * Sets a field on the elements matching {@code elementFilter}, or on every element when it is {@code null}.
     */
    static ArrayUpdate setInArray(String arrayField, Bson elementFilter, String field, Object value,
            CodecRegistry registry) {
        if (elementFilter == null) {
            return new ArrayUpdate(type(arrayField, BsonType.ARRAY), set(arrayField + ".$[]." + field, value),
                    new UpdateOptions());
        }
        return matching(arrayField, elementFilter, set(elementPath(arrayField, field), value), registry);
    }

    static ArrayUpdate setInArrayIfMissing(String arrayField, String field, Object value, CodecRegistry registry) {
        return matching(arrayField, eq(field, null), set(elementPath(arrayField, field), value), registry);
    }

    static ArrayUpdate removeInArray(String arrayField, String field, CodecRegistry registry) {
        return matching(arrayField, exists(field), unset(elementPath(arrayField, field)), registry);
    }

    private static ArrayUpdate matching(String arrayField, Bson elementFilter, Bson update, CodecRegistry registry) {
        BsonDocument arrayFilter = prefix(elementFilter.toBsonDocument(Document.class, registry));
        return new ArrayUpdate(elemMatch(arrayField, elementFilter), update,
                new UpdateOptions().arrayFilters(List.of(arrayFilter)));
    }

    private static String elementPath(String arrayField, String field) {
        return arrayField + ".$[" + ELEMENT + "]." + field;
    }

    /**
     * Rewrites a filter on an element's fields ({@code {name: "x"}}) into an array filter on the {@code element}
     * identifier ({@code {"element.name": "x"}}). Logical operators are rewritten recursively.
     */
    static BsonDocument prefix(BsonDocument filter) {
        if (filter.isEmpty()) {
            throw new IllegalArgumentException("The element filter is empty; to update every element, pass no filter");
        }
        BsonDocument prefixed = new BsonDocument();
        for (Map.Entry<String, BsonValue> entry : filter.entrySet()) {
            String key = entry.getKey();
            BsonValue value = entry.getValue();
            if (key.equals("$and") || key.equals("$or") || key.equals("$nor")) {
                BsonArray conditions = new BsonArray();
                for (BsonValue condition : value.asArray()) {
                    conditions.add(prefix(condition.asDocument()));
                }
                prefixed.put(key, conditions);
            } else if (key.startsWith("$")) {
                throw new IllegalArgumentException("Element filters support conditions on element fields, combined with"
                        + " $and, $or and $nor, but not " + key);
            } else {
                prefixed.put(ELEMENT + "." + key, value);
            }
        }
        return prefixed;
    }
}
