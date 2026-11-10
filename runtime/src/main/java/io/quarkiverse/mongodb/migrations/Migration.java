package io.quarkiverse.mongodb.migrations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a MongoDB migration.
 * <p>
 * The class must implement either {@link MongoMigration} (blocking) or {@link ReactiveMongoMigration} (reactive). It
 * becomes a CDI bean, so it can inject the Quarkus {@code MongoClient}, {@code ReactiveMongoClient}, or any other bean.
 * <p>
 * Migrations run in ascending {@link #order()}. Each one runs once, unless {@link #runAlways()} is set. Ids and order
 * numbers must be unique across the application; the build fails otherwise.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Migration {

    /**
     * Unique id of the migration, recorded in the history collection once the migration is applied.
     * <p>
     * Never change the id of a migration that has already been applied, or it will run again.
     */
    String id();

    /**
     * Position of the migration in the run order. Migrations run in ascending order.
     */
    int order();

    /**
     * Whether the migration runs on every migration run, even when it has already been applied.
     */
    boolean runAlways() default false;

    /**
     * Whether the migration runs in a MongoDB multi-document transaction, started by the extension.
     * <p>
     * The migration's changes and its history entry are committed together, or not at all: when the migration fails,
     * the transaction is aborted and {@code rollback} is not called. Pass the context's {@code session()} to every
     * driver call so it takes part in the transaction; the context's field helpers do this for you.
     * <p>
     * Meant for data changes that must be all-or-nothing. MongoDB's transaction rules apply: a replica set or sharded
     * cluster is required, transactions are time and size limited (60 seconds by default), and schema changes such as
     * creating or dropping indexes and collections are not allowed, so the context's schema helpers refuse to run.
     */
    boolean transactional() default false;
}
