package io.quarkiverse.mongodb.migrations.it;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.ReactiveMigrationContext;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.smallrye.mutiny.Uni;

@Migration(id = "insert-default-user", order = 2)
public class InsertDefaultUser implements ReactiveMongoMigration {

    @Override
    public Uni<Void> apply(ReactiveMigrationContext context) {
        return context.collection("users")
                .insertOne(new Document("email", "admin@example.com"))
                .replaceWithVoid();
    }
}
