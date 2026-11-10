package io.quarkiverse.mongodb.migrations.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class MongodbMigrationsResourceTest {

    @Test
    public void runsMigrationsAtStartup() {
        given().when().get("/mongodb-migrations/applied")
                .then().statusCode(200)
                .body(is("create-users-email-index,insert-default-user"));
        given().when().get("/mongodb-migrations/users/count")
                .then().statusCode(200)
                .body(is("1"));
        given().when().get("/mongodb-migrations/users/email-index")
                .then().statusCode(200)
                .body(is("true"));
    }
}
