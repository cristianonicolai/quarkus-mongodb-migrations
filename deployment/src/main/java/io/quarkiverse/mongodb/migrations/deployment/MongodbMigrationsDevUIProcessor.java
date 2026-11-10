package io.quarkiverse.mongodb.migrations.deployment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkiverse.mongodb.migrations.runtime.MigrationDescriptor;
import io.quarkiverse.mongodb.migrations.runtime.devui.MongodbMigrationsJsonRpcService;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * Adds a MongoDB Migrations card to the Dev UI: a live status page with a button to run pending migrations, and a
 * table of the migrations found at build time.
 */
class MongodbMigrationsDevUIProcessor {

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    CardPageBuildItem card(MigrationsBuildItem migrations) {
        List<Map<String, Object>> rows = migrations.getMigrations().stream().map(MongodbMigrationsDevUIProcessor::row)
                .toList();

        CardPageBuildItem card = new CardPageBuildItem();
        card.addBuildTimeData("migrations", rows, "The migrations found at build time, in run order");
        card.addPage(Page.webComponentPageBuilder()
                .title("Status")
                .icon("font-awesome-solid:list-check")
                .componentLink("qwc-mongodb-migrations-status.js")
                .dynamicLabelJsonRPCMethodName("getPendingLabel"));
        card.addPage(Page.tableDataPageBuilder("Migrations")
                .icon("font-awesome-solid:table-list")
                .buildTimeDataKey("migrations")
                .showColumn("order")
                .showColumn("id")
                .showColumn("type")
                .showColumn("runAlways")
                .showColumn("className")
                .staticLabel(String.valueOf(rows.size())));
        return card;
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    JsonRPCProvidersBuildItem jsonRpcService() {
        return new JsonRPCProvidersBuildItem(MongodbMigrationsJsonRpcService.class);
    }

    private static Map<String, Object> row(MigrationDescriptor migration) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order", migration.getOrder());
        row.put("id", migration.getId());
        row.put("type", migration.isReactive() ? "reactive" : "blocking");
        row.put("runAlways", migration.isRunAlways());
        row.put("className", migration.getClassName());
        return row;
    }
}
