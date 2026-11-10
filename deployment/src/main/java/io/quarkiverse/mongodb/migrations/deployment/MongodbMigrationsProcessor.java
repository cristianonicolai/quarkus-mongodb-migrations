package io.quarkiverse.mongodb.migrations.deployment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkiverse.mongodb.migrations.MongoMigrator;
import io.quarkiverse.mongodb.migrations.ReactiveMongoMigration;
import io.quarkiverse.mongodb.migrations.runtime.MigrationDescriptor;
import io.quarkiverse.mongodb.migrations.runtime.MongoMigratorImpl;
import io.quarkiverse.mongodb.migrations.runtime.MongodbMigrationsBuildTimeConfig;
import io.quarkiverse.mongodb.migrations.runtime.MongodbMigrationsRecorder;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeansRuntimeInitBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.InitTaskBuildItem;
import io.quarkus.deployment.builditem.InitTaskCompletedBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.mongodb.deployment.spi.MongoClientBuildItem;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;

class MongodbMigrationsProcessor {

    private static final String FEATURE = "mongodb-migrations";
    private static final DotName MIGRATION = DotName.createSimple(Migration.class);
    private static final DotName MONGO_MIGRATION = DotName.createSimple(MongoMigration.class);
    private static final DotName REACTIVE_MONGO_MIGRATION = DotName.createSimple(ReactiveMongoMigration.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Registers the client that stores the migration history and lock, so its bean exists even if nothing else
     * injects it.
     */
    @BuildStep
    MongoClientBuildItem historyClient(MongodbMigrationsBuildTimeConfig config) {
        return config.mongoClientName()
                .map(MongoClientBuildItem::ofUnremovable)
                .orElseGet(MongoClientBuildItem::defaultClientUnremovable);
    }

    /**
     * Makes every {@code @Migration} class a dependent bean that is never removed, so migrations can inject clients.
     */
    @BuildStep
    void migrationBeans(BuildProducer<BeanDefiningAnnotationBuildItem> beanDefiningAnnotations,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        beanDefiningAnnotations.produce(new BeanDefiningAnnotationBuildItem(MIGRATION, BuiltinScope.DEPENDENT.getName()));
        unremovableBeans.produce(UnremovableBeanBuildItem.beanClassAnnotation(MIGRATION));
    }

    @BuildStep
    MigrationsBuildItem discoverMigrations(CombinedIndexBuildItem combinedIndex) {
        IndexView index = combinedIndex.getIndex();
        Set<DotName> blocking = implementors(index, MONGO_MIGRATION);
        Set<DotName> reactive = implementors(index, REACTIVE_MONGO_MIGRATION);

        List<String> errors = new ArrayList<>();
        List<MigrationDescriptor> migrations = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(MIGRATION)) {
            if (annotation.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo migrationClass = annotation.target().asClass();
            String className = migrationClass.name().toString();
            String id = annotation.value("id").asString();
            int order = annotation.value("order").asInt();
            AnnotationValue runAlways = annotation.value("runAlways");
            AnnotationValue transactional = annotation.value("transactional");

            if (migrationClass.isInterface() || migrationClass.isAbstract()) {
                errors.add(className + " is annotated with @Migration but is abstract or an interface");
                continue;
            }
            boolean isBlocking = blocking.contains(migrationClass.name());
            boolean isReactive = reactive.contains(migrationClass.name());
            if (isBlocking == isReactive) {
                errors.add(className + " must implement exactly one of " + MongoMigration.class.getName() + " or "
                        + ReactiveMongoMigration.class.getName());
                continue;
            }
            if (id.isBlank()) {
                errors.add(className + " has a blank migration id");
                continue;
            }
            migrations.add(new MigrationDescriptor(id, order, runAlways != null && runAlways.asBoolean(),
                    transactional != null && transactional.asBoolean(), isReactive, className));
        }

        errors.addAll(duplicates(migrations, MigrationDescriptor::getId, "id"));
        errors.addAll(duplicates(migrations, MigrationDescriptor::getOrder, "order"));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid MongoDB migrations:\n - " + String.join("\n - ", errors));
        }

        migrations.sort(Comparator.comparingInt(MigrationDescriptor::getOrder));
        return new MigrationsBuildItem(migrations);
    }

    /**
     * Reactive migrations get their database from the reactive client, which nothing else may inject. Without this,
     * Quarkus would remove that client's bean as unused.
     */
    @BuildStep
    void keepReactiveClient(MigrationsBuildItem migrations, BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        if (migrations.getMigrations().stream().anyMatch(MigrationDescriptor::isReactive)) {
            unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(DotName.createSimple(ReactiveMongoClient.class)));
        }
    }

    /**
     * Migration classes are loaded by name at runtime, which native images only allow for registered classes.
     */
    @BuildStep
    ReflectiveClassBuildItem reflection(MigrationsBuildItem migrations) {
        String[] classNames = migrations.getMigrations().stream()
                .map(MigrationDescriptor::getClassName)
                .toArray(String[]::new);
        return ReflectiveClassBuildItem.builder(classNames).reason(getClass().getName()).build();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem migrator(MongodbMigrationsRecorder recorder, MigrationsBuildItem migrations) {
        return SyntheticBeanBuildItem.configure(MongoMigratorImpl.class)
                .types(MongoMigrator.class)
                .scope(Singleton.class)
                .setRuntimeInit()
                .unremovable()
                .supplier(recorder.migratorSupplier(migrations.getMigrations()))
                .done();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    @Consume(SyntheticBeansRuntimeInitBuildItem.class)
    ServiceStartBuildItem migrateAtStart(MongodbMigrationsRecorder recorder,
            BuildProducer<InitTaskCompletedBuildItem> initTaskCompleted) {
        recorder.migrateAtStart();
        initTaskCompleted.produce(new InitTaskCompletedBuildItem(FEATURE));
        return new ServiceStartBuildItem(FEATURE);
    }

    /**
     * With the Kubernetes or OpenShift extension, generates a Job that runs migrations before the application starts,
     * and disables migrations in the application itself.
     */
    @BuildStep
    InitTaskBuildItem initTask(ApplicationInfoBuildItem app) {
        return InitTaskBuildItem.create()
                .withName(app.getName() + "-" + FEATURE + "-init")
                .withTaskEnvVars(Map.of("QUARKUS_INIT_AND_EXIT", "true", "QUARKUS_MONGODB_MIGRATIONS_ENABLED", "true"))
                .withAppEnvVars(Map.of("QUARKUS_MONGODB_MIGRATIONS_ENABLED", "false"))
                .withSharedEnvironment(true)
                .withSharedFilesystem(true);
    }

    private static Set<DotName> implementors(IndexView index, DotName interfaceName) {
        return index.getAllKnownImplementors(interfaceName).stream()
                .map(ClassInfo::name)
                .collect(Collectors.toSet());
    }

    private static <K> List<String> duplicates(List<MigrationDescriptor> migrations,
            Function<MigrationDescriptor, K> key, String keyName) {
        Map<K, List<String>> classesByKey = migrations.stream()
                .collect(Collectors.groupingBy(key,
                        Collectors.mapping(MigrationDescriptor::getClassName, Collectors.toList())));
        List<String> errors = new ArrayList<>();
        classesByKey.forEach((value, classes) -> {
            if (classes.size() > 1) {
                errors.add("Duplicate migration " + keyName + " " + value + " in "
                        + String.join(", ", classes.stream().sorted().toList()));
            }
        });
        return errors;
    }
}
