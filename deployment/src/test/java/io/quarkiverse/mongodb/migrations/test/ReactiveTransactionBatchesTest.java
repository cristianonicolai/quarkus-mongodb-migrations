package io.quarkiverse.mongodb.migrations.test;

import static com.mongodb.client.model.Filters.empty;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.Sorts;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.reactivestreams.client.ClientSession;

import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import mutiny.zero.flow.adapters.AdaptersToFlow;

/**
 * The reactive updateEach and writeEach read with a cursor and write in batches on the same session. In a transaction,
 * MongoDB doesn't support parallel operations on a session, so reading the next batch must not overlap a write, also
 * when the helpers are called on a Vert.x context, where the reactive client emits on the event loop. A command listener
 * checks that no command of a session starts while another of it is still running.
 */
public class ReactiveTransactionBatchesTest {

    private static final int DOCUMENTS = 3500;

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(SessionOverlapListener.class))
            .overrideConfigKey("quarkus.mongodb.database", "reactive-transaction-batches-test");

    @Inject
    MongoClient client;

    @Inject
    ReactiveMongoClient reactiveClient;

    @Inject
    Vertx vertx;

    @Test
    void updateEachInATransaction(TestInfo info) throws Exception {
        String database = seed(info);
        assertEquals(DOCUMENTS, inTransaction(database, false,
                context -> context.updateEach("numbers", empty(), number -> set("seen", true))));
        assertEquals(DOCUMENTS, client.getDatabase(database).getCollection("numbers").countDocuments(exists("seen")));
    }

    @Test
    void updateEachInATransactionOnAVertxContext(TestInfo info) throws Exception {
        String database = seed(info);
        assertEquals(DOCUMENTS, inTransaction(database, true,
                context -> context.updateEach("numbers", empty(), number -> set("seen", true))));
        assertEquals(DOCUMENTS, client.getDatabase(database).getCollection("numbers").countDocuments(exists("seen")));
    }

    @Test
    void writeEachInATransaction(TestInfo info) throws Exception {
        String database = seed(info);
        assertEquals(DOCUMENTS, inTransaction(database, false, ReactiveTransactionBatchesTest::copy));
        assertEveryNumberCopiedOnce(database);
    }

    @Test
    void writeEachInATransactionOnAVertxContext(TestInfo info) throws Exception {
        String database = seed(info);
        assertEquals(DOCUMENTS, inTransaction(database, true, ReactiveTransactionBatchesTest::copy));
        assertEveryNumberCopiedOnce(database);
    }

    private void assertEveryNumberCopiedOnce(String database) {
        assertEquals(IntStream.range(0, DOCUMENTS).boxed().toList(),
                client.getDatabase(database).getCollection("copies").find().sort(Sorts.ascending("n"))
                        .map(copy -> copy.getInteger("n")).into(new ArrayList<>()));
    }

    private static Uni<Long> copy(ReactiveMigrationContext context) {
        return context.writeEach("numbers", empty(), "copies",
                number -> List.of(new InsertOneModel<>(new Document("n", number.getInteger("n")))));
    }

    /**
     * Runs the helper in a transaction and commits it, on the test thread or on a Vert.x event-loop context.
     */
    private long inTransaction(String database, boolean onVertxContext, Function<ReactiveMigrationContext, Uni<Long>> helper)
            throws Exception {
        ClientSession session = reactiveClient.startSession().await().indefinitely();
        // Counted per session, as differences: the driver reuses session ids, and other tests may use the client.
        BsonDocument sessionId = session.getServerSession().getIdentifier();
        int overlapsBefore = SessionOverlapListener.overlaps(sessionId);
        int getMoresBefore = SessionOverlapListener.getMores(sessionId);
        try {
            session.startTransaction();
            ReactiveMigrationContext context = new ReactiveMigrationContext(reactiveClient, database, session);
            long changed;
            if (onVertxContext) {
                CompletableFuture<Long> result = new CompletableFuture<>();
                vertx.getOrCreateContext().runOnContext(ignored -> helper.apply(context).subscribe()
                        .with(result::complete, result::completeExceptionally));
                changed = result.get(60, TimeUnit.SECONDS);
            } else {
                changed = helper.apply(context).await().indefinitely();
            }
            await(session.commitTransaction());
            assertEquals(overlapsBefore, SessionOverlapListener.overlaps(sessionId),
                    "no command overlapped another on the session");
            assertTrue(SessionOverlapListener.getMores(sessionId) > getMoresBefore, "the cursor was read in several batches");
            return changed;
        } finally {
            session.close();
        }
    }

    private String seed(TestInfo info) {
        String database = "rtb-" + info.getTestMethod().orElseThrow().getName();
        client.getDatabase(database).drop();
        client.getDatabase(database).getCollection("numbers").insertMany(IntStream.range(0, DOCUMENTS)
                .mapToObj(i -> new Document("_id", i).append("n", i)).toList());
        // Collections can't always be created inside a transaction.
        client.getDatabase(database).createCollection("copies");
        return database;
    }

    /**
     * Counts commands started on a session while another command of the same session is still running.
     */
    public static class SessionOverlapListener implements CommandListener {
        private static final Map<BsonDocument, AtomicInteger> OVERLAPS = new ConcurrentHashMap<>();
        private static final Map<BsonDocument, AtomicInteger> GET_MORES = new ConcurrentHashMap<>();
        private final Map<BsonDocument, AtomicInteger> running = new ConcurrentHashMap<>();
        private final Map<Integer, BsonDocument> sessions = new ConcurrentHashMap<>();

        static int overlaps(BsonDocument session) {
            return count(OVERLAPS, session).get();
        }

        static int getMores(BsonDocument session) {
            return count(GET_MORES, session).get();
        }

        private static AtomicInteger count(Map<BsonDocument, AtomicInteger> counts, BsonDocument session) {
            return counts.computeIfAbsent(session, key -> new AtomicInteger());
        }

        @Override
        public void commandStarted(CommandStartedEvent event) {
            if (!event.getCommand().containsKey("lsid")) {
                return;
            }
            BsonDocument session = event.getCommand().getDocument("lsid");
            if (event.getCommandName().equals("getMore")) {
                count(GET_MORES, session).incrementAndGet();
            }
            sessions.put(event.getRequestId(), session);
            if (count(running, session).incrementAndGet() > 1) {
                count(OVERLAPS, session).incrementAndGet();
            }
        }

        @Override
        public void commandSucceeded(CommandSucceededEvent event) {
            finished(event.getRequestId());
        }

        @Override
        public void commandFailed(CommandFailedEvent event) {
            finished(event.getRequestId());
        }

        private void finished(int requestId) {
            BsonDocument session = sessions.remove(requestId);
            if (session != null) {
                running.get(session).decrementAndGet();
            }
        }
    }

    private static void await(Publisher<Void> publisher) {
        Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher)).await().indefinitely();
    }
}
