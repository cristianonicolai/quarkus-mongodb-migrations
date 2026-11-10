package io.quarkiverse.mongodb.migrations.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Also runs against the native executable, through {@link MongodbMigrationsResourceIT}.
 */
@QuarkusTest
public class MongodbMigrationsResourceTest {

    @Test
    public void runsMigrationsAtStartup() {
        given().when().get("/mongodb-migrations/applied")
                .then().statusCode(200)
                .body(is("create-users-email-index,insert-default-user,seed-and-backfill-tenants,transactional-orders,"
                        + "reactive-transactional-backfill,index-tenant-event-collections,fails-on-demand,"
                        + "fails-on-demand-transactional"));
        given().when().get("/mongodb-migrations/users/count")
                .then().statusCode(200)
                .body(is("1"));
        given().when().get("/mongodb-migrations/users/email-index")
                .then().statusCode(200)
                .body(is("true"));
    }

    @Test
    public void fieldArrayAndUpdateEachHelpersChangedTheTenants() {
        given().when().get("/mongodb-migrations/documents/tenants")
                .then().statusCode(200)
                .body("_id", contains("acme", "globex"))
                .body("find { it._id == 'acme' }.members.status", contains("INVITED", "ACTIVE"))
                .body("find { it._id == 'acme' }.members.legacyRole", everyItem(nullValue()))
                .body("find { it._id == 'acme' }.apiKeys.type", contains("PLATFORM", "USER"))
                .body("find { it._id == 'acme' }.deleted", is(false))
                .body("find { it._id == 'globex' }.deleted", is(true))
                .body("find { it._id == 'globex' }.members.status", contains("ACTIVE"))
                .body("memberCount", contains(2, 1));
    }

    @Test
    public void transactionalMigrationsChangedTheOrders() {
        given().when().get("/mongodb-migrations/documents/orders")
                .then().statusCode(200)
                .body("_id", contains(1, 2))
                .body("status", contains("open", "open"))
                .body("state", everyItem(nullValue()))
                .body("reviewed", contains(true, true))
                .body("totalLabel", contains("10", "20"))
                .body("doubled", contains(20, 40));
    }

    @Test
    public void historyRecordsChangeCountsAndTransactions() {
        given().when().get("/mongodb-migrations/documents/migrationHistory")
                .then().statusCode(200)
                .body("find { it._id == 'seed-and-backfill-tenants' }.documentsChanged", is(10))
                .body("find { it._id == 'transactional-orders' }.documentsChanged", is(8))
                .body("find { it._id == 'transactional-orders' }.transactional", is(true))
                .body("find { it._id == 'reactive-transactional-backfill' }.documentsChanged", is(4))
                .body("find { it._id == 'reactive-transactional-backfill' }.transactional", is(true))
                .body("find { it._id == 'index-tenant-event-collections' }.documentsChanged", is(2))
                .body("find { it._id == 'create-users-email-index' }.documentsChanged", nullValue());
    }

    @Test
    public void perTenantDatabasesWereMigrated() {
        for (String tenant : IndexTenantEventCollections.TENANT_DATABASES) {
            given().when().get("/mongodb-migrations/indexes/" + tenant + "/run-events")
                    .then().statusCode(200)
                    .body(containsString("run_idx"));
            given().when().get("/mongodb-migrations/indexes/" + tenant + "/settings")
                    .then().statusCode(200)
                    .body(not(containsString("run_idx")));
            given().when().get("/mongodb-migrations/documents/" + tenant + "/settings")
                    .then().statusCode(200)
                    .body("version", contains(1));
        }
    }

    @Test
    public void failedMigrationsAreRolledBackOrAbortedAndRecorded() {
        int rollbacksBefore = count("rollbacks");
        given().when().post("/mongodb-migrations/fail/fails-on-demand")
                .then().statusCode(200)
                .body(startsWith("failed: Migration fails-on-demand failed"));
        assertEquals(rollbacksBefore + 1, count("rollbacks"), "rollback ran");

        int transactionalProbesBefore = count("transactionalProbes");
        given().when().post("/mongodb-migrations/fail/fails-on-demand-transactional")
                .then().statusCode(200)
                .body(startsWith("failed: Migration fails-on-demand-transactional failed"));
        assertEquals(transactionalProbesBefore, count("transactionalProbes"), "the insert was aborted");

        given().when().get("/mongodb-migrations/documents/migrationFailures")
                .then().statusCode(200)
                .body("find { it.migrationId == 'fails-on-demand' }.error.message", is("failed on demand"))
                .body("find { it.migrationId == 'fails-on-demand' }.documentsChangedBeforeFailure", is(1))
                .body("find { it.migrationId == 'fails-on-demand-transactional' }.error.message",
                        is("transactional failure on demand"))
                .body("find { it.migrationId == 'fails-on-demand-transactional' }.documentsChangedBeforeFailure",
                        nullValue());
    }

    private static int count(String collection) {
        return given().when().get("/mongodb-migrations/documents/" + collection)
                .then().statusCode(200)
                .extract().path("size()");
    }
}
