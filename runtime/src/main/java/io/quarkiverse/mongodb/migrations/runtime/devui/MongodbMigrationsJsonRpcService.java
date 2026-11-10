package io.quarkiverse.mongodb.migrations.runtime.devui;

import java.util.List;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.mongodb.migrations.runtime.MigrationsStatus;
import io.quarkiverse.mongodb.migrations.runtime.MongoMigratorImpl;

/**
 * Backs the Dev UI pages. Registered only in dev mode. Methods block, so Dev UI runs them on a worker thread.
 */
public class MongodbMigrationsJsonRpcService {

    private static final Logger LOG = Logger.getLogger(MongodbMigrationsJsonRpcService.class);

    @Inject
    MongoMigratorImpl migrator;

    public MigrationsStatus getStatus() {
        return migrator.status();
    }

    /**
     * The label shown on the Status page link of the card.
     */
    public String getPendingLabel() {
        int pending = migrator.status().pending();
        return pending == 0 ? "up to date" : pending + " pending";
    }

    public MigrateResult migrate() {
        try {
            return new MigrateResult(true, migrator.migrate(), null);
        } catch (RuntimeException e) {
            LOG.warn("Migration run started from the Dev UI failed", e);
            return new MigrateResult(false, List.of(), describe(e));
        }
    }

    /**
     * The failure and its causes on one line, since the migration failure wraps the error thrown by the migration.
     */
    private static String describe(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!message.isEmpty()) {
                message.append(": ");
            }
            message.append(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        }
        return message.toString();
    }

    /**
     * @param ran the ids of the migrations that ran
     * @param error why the run failed, or {@code null} when it succeeded
     */
    public record MigrateResult(boolean success, List<String> ran, String error) {
    }
}
