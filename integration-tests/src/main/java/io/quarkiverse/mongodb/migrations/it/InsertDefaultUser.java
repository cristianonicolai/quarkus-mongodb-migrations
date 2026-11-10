package io.quarkiverse.mongodb.migrations.it;

import jakarta.inject.Inject;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.smallrye.mutiny.Uni;

@Migration(id = "insert-default-user", order = 2)
public class InsertDefaultUser implements ReactiveMongoMigration {

    @Inject
    ReactiveMongoClient client;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    @Override
    public Uni<Void> apply() {
        return client.getDatabase(database).getCollection("users")
                .insertOne(new Document("email", "admin@example.com"))
                .replaceWithVoid();
    }
}
