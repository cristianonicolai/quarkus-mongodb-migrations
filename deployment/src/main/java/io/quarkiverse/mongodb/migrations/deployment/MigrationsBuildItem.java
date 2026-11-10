package io.quarkiverse.mongodb.migrations.deployment;

import java.util.List;

import io.quarkiverse.mongodb.migrations.runtime.MigrationDescriptor;
import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The application's validated migrations, sorted by order.
 */
final class MigrationsBuildItem extends SimpleBuildItem {

    private final List<MigrationDescriptor> migrations;

    MigrationsBuildItem(List<MigrationDescriptor> migrations) {
        this.migrations = List.copyOf(migrations);
    }

    List<MigrationDescriptor> getMigrations() {
        return migrations;
    }
}
