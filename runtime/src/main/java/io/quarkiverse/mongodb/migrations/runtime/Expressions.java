package io.quarkiverse.mongodb.migrations.runtime;

import java.util.List;

import org.bson.Document;

/**
 * Aggregation expressions used in update pipelines. The driver's builders cover pipeline stages
 * ({@code Aggregates.set}) but not these operators: its typed expression API is still beta and has no {@code $$NOW}.
 */
final class Expressions {

    /** The MongoDB server's current time. */
    static final String NOW = "$$NOW";

    private Expressions() {
    }

    /** The value of a field of the document being updated. */
    static String field(String name) {
        return "$" + name;
    }

    /** A value used as is, never read as a field path or operator, even if it starts with {@code $}. */
    static Document literal(Object value) {
        return new Document("$literal", value);
    }

    /** {@code value}, or {@code fallback} when it is missing or null. */
    static Document ifNull(Object value, Object fallback) {
        return new Document("$ifNull", List.of(value, fallback));
    }

    /** {@code then} when {@code condition} is true, otherwise {@code otherwise}. */
    static Document cond(Object condition, Object then, Object otherwise) {
        return new Document("$cond", List.of(condition, then, otherwise));
    }

    static Document and(Object... conditions) {
        return new Document("$and", List.of(conditions));
    }

    static Document ne(Object left, Object right) {
        return new Document("$ne", List.of(left, right));
    }

    static Document gt(Object left, Object right) {
        return new Document("$gt", List.of(left, right));
    }

    /** Sum of numbers, or a date plus milliseconds. */
    static Document add(Object left, Object right) {
        return new Document("$add", List.of(left, right));
    }
}
