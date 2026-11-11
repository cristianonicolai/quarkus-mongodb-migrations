package io.quarkiverse.mongodb.migrations;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;

/**
 * Counts the documents a migration reports as changed. Thread safe, since reactive callbacks may run on any thread.
 * Unacknowledged writes don't report counts, so they are skipped.
 */
final class ChangeCounter {

    private final AtomicLong count = new AtomicLong();
    private volatile boolean reported;

    void add(long documents) {
        if (documents < 0) {
            throw new IllegalArgumentException("A number of changed documents can't be negative: " + documents);
        }
        count.addAndGet(documents);
        reported = true;
    }

    /**
     * Modified documents, plus the one inserted by an upsert.
     */
    void add(UpdateResult result) {
        if (result.wasAcknowledged()) {
            add(result.getModifiedCount() + (result.getUpsertedId() != null ? 1 : 0));
        }
    }

    void add(DeleteResult result) {
        if (result.wasAcknowledged()) {
            add(result.getDeletedCount());
        }
    }

    void add(InsertOneResult result) {
        if (result.wasAcknowledged()) {
            add(1);
        }
    }

    void add(InsertManyResult result) {
        if (result.wasAcknowledged()) {
            add(result.getInsertedIds().size());
        }
    }

    void add(BulkWriteResult result) {
        if (result.wasAcknowledged()) {
            add(changed(result));
        }
    }

    /**
     * The documents a bulk write inserted, modified, deleted or upserted; 0 when it wasn't acknowledged.
     */
    static long changed(BulkWriteResult result) {
        return result.wasAcknowledged()
                ? (long) result.getInsertedCount() + result.getModifiedCount() + result.getDeletedCount()
                        + result.getUpserts().size()
                : 0;
    }

    /**
     * The total, or empty when nothing was reported: unknown, rather than zero.
     */
    OptionalLong total() {
        return reported ? OptionalLong.of(count.get()) : OptionalLong.empty();
    }
}
