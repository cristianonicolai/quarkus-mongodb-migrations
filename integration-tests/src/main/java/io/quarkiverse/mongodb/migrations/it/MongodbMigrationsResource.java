package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Sorts.ascending;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.indexExists;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mongodb.client.MongoClient;

@Path("/mongodb-migrations")
@ApplicationScoped
public class MongodbMigrationsResource {

    @Inject
    MongoClient client;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    @GET
    @Path("/applied")
    public String applied() {
        List<String> ids = new ArrayList<>();
        for (Document entry : client.getDatabase(database).getCollection("migrationHistory").find()
                .sort(ascending("order"))) {
            ids.add(entry.getString("_id"));
        }
        return String.join(",", ids);
    }

    @GET
    @Path("/users/count")
    public long userCount() {
        return client.getDatabase(database).getCollection("users").countDocuments();
    }

    @GET
    @Path("/users/email-index")
    public boolean emailIndexExists() {
        return indexExists(client.getDatabase(database), "users", "email_idx");
    }
}
