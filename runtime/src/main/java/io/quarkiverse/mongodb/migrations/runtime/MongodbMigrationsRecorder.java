package io.quarkiverse.mongodb.migrations.runtime;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Default;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkus.arc.Arc;
import io.quarkus.mongodb.MongoClientName;
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

    public Supplier<MongoMigrator> migratorSupplier(List<MigrationDescriptor> migrations) {
        // Runs at startup on a Quarkus thread, whose context class loader can load the application's classes.
        // migrate() may later be called from any thread, so the loader is captured here.
        ClassLoader applicationClassLoader = Thread.currentThread().getContextClassLoader();
        return () -> new MongoMigratorImpl(migrations, applicationClassLoader, database(), config.getValue());
    }

    public void migrateAtStart() {
        MongodbMigrationsConfig runtimeConfig = config.getValue();
        if (runtimeConfig.enabled() && runtimeConfig.migrateAtStart()) {
            Arc.container().instance(MongoMigrator.class).get().migrate();
        }
    }

    private MongoDatabase database() {
        String clientName = MongoConfig
                .nameOrDefault(buildTimeConfig.mongoClientName().orElse(MongoConfig.DEFAULT_CLIENT_NAME));
        Annotation qualifier = MongoConfig.isDefaultClient(clientName)
                ? Default.Literal.INSTANCE
                : MongoClientName.Literal.of(clientName);
        MongoClient client = Arc.container().instance(MongoClient.class, qualifier).get();
        return client.getDatabase(databaseName(clientName));
    }

    private String databaseName(String clientName) {
        if (config.getValue().database().isPresent()) {
            return config.getValue().database().get();
        }
        MongoClientConfig clientConfig = mongoConfig.getValue().clients().get(clientName);
        if (clientConfig != null && clientConfig.database().isPresent()) {
            return clientConfig.database().get();
        }
        throw new ConfigurationException("No database configured for MongoDB migrations. Set "
                + MongoConfig.getPropertyName(clientName, "database") + " or quarkus.mongodb-migrations.database.");
    }
}
