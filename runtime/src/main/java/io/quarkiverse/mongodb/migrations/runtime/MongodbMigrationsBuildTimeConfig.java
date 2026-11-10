package io.quarkiverse.mongodb.migrations.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.mongodb-migrations")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface MongodbMigrationsBuildTimeConfig {

    /**
     * Name of the MongoDB client that migrations work with and that stores the migration history and lock, as
     * configured under {@code quarkus.mongodb.<name>}. Uses the default client when not set.
     * <p>
     * The migration context works on this client's database. Migrations can still inject any other client.
     */
    Optional<String> mongoClientName();
}
