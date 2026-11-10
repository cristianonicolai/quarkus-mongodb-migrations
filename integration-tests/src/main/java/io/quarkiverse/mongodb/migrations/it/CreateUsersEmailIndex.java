package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Indexes.ascending;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createIndex;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;

@Migration(id = "create-users-email-index", order = 1)
public class CreateUsersEmailIndex implements MongoMigration {

    @Inject
    MongoClient client;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    @Override
    public void apply() {
        createIndex(client.getDatabase(database), "users", "email_idx", ascending("email"));
    }
}
