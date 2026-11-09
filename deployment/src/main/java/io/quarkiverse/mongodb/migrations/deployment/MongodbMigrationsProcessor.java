package io.quarkiverse.mongodb.migrations.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class MongodbMigrationsProcessor {

    private static final String FEATURE = "mongodb-migrations";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
