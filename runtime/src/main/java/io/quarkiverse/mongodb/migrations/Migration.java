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
}
