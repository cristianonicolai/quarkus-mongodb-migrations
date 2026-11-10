package io.quarkiverse.mongodb.migrations.runtime;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Default;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.arc.Arc;
import io.quarkus.mongodb.MongoClientName;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.runtime.MongoClientConfig;
import io.quarkus.mongodb.runtime.MongoConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.configuration.ConfigurationException;

@Recorder
public class MongodbMigrationsRecorder {

    private final MongodbMigrationsBuildTimeConfig buildTimeConfig;
    private final RuntimeValue<MongodbMigrationsConfig> config;
    private final RuntimeValue<MongoConfig> mongoConfig;

    public MongodbMigrationsRecorder(MongodbMigrationsBuildTimeConfig buildTimeConfig,
            RuntimeValue<MongodbMigrationsConfig> config, RuntimeValue<MongoConfig> mongoConfig) {
        this.buildTimeConfig = buildTimeConfig;
        this.config = config;
        this.mongoConfig = mongoConfig;
    }

    public Supplier<MongoMigratorImpl> migratorSupplier(List<MigrationDescriptor> migrations) {
        // Runs at startup on a Quarkus thread, whose context class loader can load the application's classes.
        // migrate() may later be called from any thread, so the loader is captured here.
        ClassLoader applicationClassLoader = Thread.currentThread().getContextClassLoader();
        return () -> {
            String clientName = clientName();
            Annotation qualifier = qualifier(clientName);
            MongoClient client = Arc.container().instance(MongoClient.class, qualifier).get();
            Optional<String> clientDatabase = clientDatabase(clientName);
            MongoDatabase metadataDatabase = client.getDatabase(metadataDatabaseName(clientName, clientDatabase));
            // Migrations work on the client's own database, even when the history and lock live elsewhere.
            // The reactive client is looked up only when a reactive migration runs.
            return new MongoMigratorImpl(migrations, applicationClassLoader, metadataDatabase, client,
                    () -> Arc.container().instance(ReactiveMongoClient.class, qualifier).get(),
                    () -> migratedDatabaseName(clientName, clientDatabase), config.getValue());
        };
    }

    public void migrateAtStart() {
        MongodbMigrationsConfig runtimeConfig = config.getValue();
        if (runtimeConfig.enabled() && runtimeConfig.migrateAtStart()) {
            Arc.container().instance(MongoMigrator.class).get().migrate();
        }
    }

    private String clientName() {
        return MongoConfig.nameOrDefault(buildTimeConfig.mongoClientName().orElse(MongoConfig.DEFAULT_CLIENT_NAME));
    }

    private static Annotation qualifier(String clientName) {
        return MongoConfig.isDefaultClient(clientName) ? Default.Literal.INSTANCE : MongoClientName.Literal.of(clientName);
    }

    private Optional<String> clientDatabase(String clientName) {
        MongoClientConfig clientConfig = mongoConfig.getValue().clients().get(clientName);
        return clientConfig == null ? Optional.empty() : clientConfig.database();
    }

    /**
     * Where the history and lock are stored.
     */
    private String metadataDatabaseName(String clientName, Optional<String> clientDatabase) {
        return config.getValue().database().or(() -> clientDatabase)
                .orElseThrow(() -> new ConfigurationException("No database configured for MongoDB migrations. Set "
                        + MongoConfig.getPropertyName(clientName, "database")
                        + " or quarkus.mongodb-migrations.database."));
    }

    /**
     * The database that migrations work on, through their context.
     */
    private static String migratedDatabaseName(String clientName, Optional<String> clientDatabase) {
        return clientDatabase.orElseThrow(() -> new ConfigurationException("No database configured for migrations to"
                + " work on. Set " + MongoConfig.getPropertyName(clientName, "database")
                + " (quarkus.mongodb-migrations.database only sets where the history and lock are stored)."));
    }
}
