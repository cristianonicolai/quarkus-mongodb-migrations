package io.quarkiverse.mongodb.migrations.runtime;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.mongodb-migrations")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface MongodbMigrationsConfig {

    /**
     * Whether migrations run at startup when {@code migrate-at-start} is enabled.
     * <p>
     * When a Kubernetes init job is generated, it is set to {@code false} for the application and {@code true} for the
     * job, so only the job runs migrations.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether to run pending migrations when the application starts.
     */
    @WithDefault("false")
    boolean migrateAtStart();

    /**
     * Database that stores the migration history and lock. Defaults to the database configured for the MongoDB
     * client ({@code quarkus.mongodb.database}).
     */
    Optional<String> database();

    /**
     * Collection that records applied migrations.
     */
    @WithDefault("migrationHistory")
    String historyCollection();

    /**
     * Collection that holds the migration lock.
     */
    @WithDefault("migrationLock")
    String lockCollection();

    /**
     * Whether to record each failed migration attempt, with its error and rollback outcome, in
     * {@code failure-collection}. Useful when logs are short-lived, such as those of a Kubernetes init job.
     * <p>
     * Error messages and the first stack frames are stored, so they are visible to anyone who can read that
     * collection.
     */
    @WithDefault("true")
    boolean recordFailures();

    /**
     * Collection that records failed migration attempts, when {@code record-failures} is enabled.
     */
    @WithDefault("migrationFailures")
    String failureCollection();

    /**
     * How long failed attempts are kept before MongoDB removes them, using a TTL index.
     */
    @WithDefault("30d")
    Duration failureRetention();

    /**
     * How long the migration lock is held before it expires. The lock is renewed while migrations run, so this only
     * matters when an instance dies while holding it: other instances can take over once it expires.
     */
    @WithDefault("60s")
    Duration lockLeaseDuration();

    /**
     * How long to wait for the migration lock when another instance holds it, before failing.
     */
    @WithDefault("5m")
    Duration lockAcquireTimeout();

    /**
     * Name of this application instance, stored with the migration lock and shown in logs so you can tell which
     * instance holds it. Defaults to the {@code HOSTNAME} environment variable (the pod name in Kubernetes), or a
     * random id generated at startup when it is not set.
     * <p>
     * It is for diagnostics only: the lock stays correct even if several instances use the same name.
     */
    @WithDefault("${HOSTNAME:${quarkus.uuid}}")
    String instanceId();
}
