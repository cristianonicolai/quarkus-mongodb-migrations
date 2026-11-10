package io.quarkiverse.mongodb.migrations.runtime;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * A migration discovered at build time.
 */
public final class MigrationDescriptor {

    private final String id;
    private final int order;
    private final boolean runAlways;
    private final boolean transactional;
    private final boolean reactive;
    private final String className;

    @RecordableConstructor
    public MigrationDescriptor(String id, int order, boolean runAlways, boolean transactional, boolean reactive,
            String className) {
        this.id = id;
        this.order = order;
        this.runAlways = runAlways;
        this.transactional = transactional;
        this.reactive = reactive;
        this.className = className;
    }

    public String getId() {
        return id;
    }

    public int getOrder() {
        return order;
    }

    public boolean isRunAlways() {
        return runAlways;
    }

    public boolean isTransactional() {
        return transactional;
    }

    public boolean isReactive() {
        return reactive;
    }

    public String getClassName() {
        return className;
    }
}
