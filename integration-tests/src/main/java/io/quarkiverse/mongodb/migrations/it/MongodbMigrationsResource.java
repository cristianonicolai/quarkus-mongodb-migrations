package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Sorts.ascending;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.indexExists;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongodb.migrations.MigrationException;
import io.quarkiverse.mongodb.migrations.MongoMigrator;

/**
 * Lets the tests inspect what the migrations did, also when the application runs as a native executable.
 */
@Path("/mongodb-migrations")
@ApplicationScoped
public class MongodbMigrationsResource {

    @Inject
    MongoClient client;

    @Inject
    MongoMigrator migrator;

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

    /**
     * The documents of a collection of the application's database, as a JSON array sorted by {@code _id}.
     */
    @GET
    @Path("/documents/{collection}")
    @Produces(MediaType.APPLICATION_JSON)
    public String documents(@PathParam("collection") String collection) {
        return documents(database, collection);
    }

    /**
     * The documents of a collection of any database, as a JSON array sorted by {@code _id}.
     */
    @GET
    @Path("/documents/{database}/{collection}")
    @Produces(MediaType.APPLICATION_JSON)
    public String documents(@PathParam("database") String databaseName, @PathParam("collection") String collection) {
        List<String> documents = new ArrayList<>();
        for (Document document : client.getDatabase(databaseName).getCollection(collection).find().sort(ascending("_id"))) {
            documents.add(document.toJson());
        }
        return "[" + String.join(",", documents) + "]";
    }

    /**
     * The index names of a collection of any database, comma separated.
     */
    @GET
    @Path("/indexes/{database}/{collection}")
    public String indexes(@PathParam("database") String databaseName, @PathParam("collection") String collection) {
        List<String> names = new ArrayList<>();
        for (Document index : client.getDatabase(databaseName).getCollection(collection).listIndexes()) {
            names.add(index.getString("name"));
        }
        return String.join(",", names);
    }

    /**
     * Runs the migrations with one of the on-demand migrations set to fail, and reports the outcome.
     */
    @POST
    @Path("/fail/{migration}")
    public String fail(@PathParam("migration") String migration) {
        AtomicBoolean fail = switch (migration) {
            case "fails-on-demand" -> FailsOnDemand.FAIL;
            case "fails-on-demand-transactional" -> FailsOnDemandTransactional.FAIL;
            default -> throw new NotFoundException(migration);
        };
        fail.set(true);
        try {
            migrator.migrate();
            return "succeeded";
        } catch (MigrationException e) {
            return "failed: " + e.getMessage();
        } finally {
            fail.set(false);
        }
    }
}
