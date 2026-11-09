package io.quarkiverse.mongodb.migrations.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class MongodbMigrationsResourceTest {

    @Test
    public void testHelloEndpoint() {
        given()
                .when().get("/mongodb-migrations")
                .then()
                .statusCode(200)
                .body(is("Hello mongodb-migrations"));
    }
}
