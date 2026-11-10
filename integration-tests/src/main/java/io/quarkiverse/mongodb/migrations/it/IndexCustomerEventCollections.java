package io.quarkiverse.mongodb.migrations.it;

import static com.mongodb.client.model.Indexes.ascending;

import java.util.List;

import org.bson.Document;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;

/**
 * Exercises forEachDatabase and forEachCollection on per-customer databases.
 */
@Migration(id = "index-customer-event-collections", order = 6)
public class IndexCustomerEventCollections implements MongoMigration {

    static final List<String> CUSTOMER_DATABASES = List.of("it-customer-north", "it-customer-south");

    @Override
    public void apply(MigrationContext context) {
        for (String customer : CUSTOMER_DATABASES) {
            context.client().getDatabase(customer).getCollection("order-events").insertOne(new Document("orderId", 1));
            context.client().getDatabase(customer).getCollection("settings").insertOne(new Document("_id", "settings"));
        }
        context.forEachDatabase(name -> name.startsWith("it-customer-"), customer -> customer.forEachCollection(
                name -> name.endsWith("-events"),
                collection -> customer.createIndex(collection, "order_idx", ascending("orderId"))));
        context.forEachDatabase(name -> name.startsWith("it-customer-"),
                customer -> customer.setFieldIfMissing("settings", "version", 1));
    }
}
