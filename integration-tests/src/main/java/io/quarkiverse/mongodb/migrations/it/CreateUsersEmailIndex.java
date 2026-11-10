package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Indexes.ascending;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

@Migration(id = "create-users-email-index", order = 1)
public class CreateUsersEmailIndex implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.createIndex("users", "email_idx", ascending("email"));
    }

    @Override
    public void rollback(MigrationContext context) {
        context.dropIndex("users", "email_idx");
    }
}
