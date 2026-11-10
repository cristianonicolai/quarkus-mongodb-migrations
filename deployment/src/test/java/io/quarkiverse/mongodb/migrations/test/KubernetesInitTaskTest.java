package io.quarkiverse.mongodb.migrations.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mongodb.migrations.Migration;
import io.quarkiverse.mongodb.migrations.MigrationContext;
import io.quarkiverse.mongodb.migrations.MongoMigration;
import io.quarkus.builder.Version;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.ProdBuildResults;
import io.quarkus.test.ProdModeTestResults;
import io.quarkus.test.QuarkusProdModeTest;

public class KubernetesInitTaskTest {

    @RegisterExtension
    static final QuarkusProdModeTest test = new QuarkusProdModeTest()
            .withApplicationRoot(jar -> jar.addClasses(Noop.class))
            .setApplicationName("orders")
            .setApplicationVersion("0.1")
            .setForcedDependencies(List.of(Dependency.of("io.quarkus", "quarkus-kubernetes", Version.getVersion())));

    @ProdBuildResults
    ProdModeTestResults results;

    @Test
    void generatesAJobThatRunsMigrationsBeforeTheApplication() throws IOException {
        String manifest = Files.readString(results.getBuildDir().resolve("kubernetes").resolve("kubernetes.yml"));

        String job = resource(manifest, "Job");
        assertTrue(job.contains("name: orders-mongodb-migrations-init"), job);
        assertTrue(job.contains("QUARKUS_INIT_AND_EXIT"), job);
        assertTrue(enabledValue(job).contains("\"true\""), job);

        String deployment = resource(manifest, "Deployment");
        assertTrue(enabledValue(deployment).contains("\"false\""), deployment);
    }

    private static String resource(String manifest, String kind) {
        return Arrays.stream(manifest.split("(?m)^---$"))
                .filter(document -> document.contains("kind: " + kind + "\n"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " in:\n" + manifest));
    }

    /**
     * The line after {@code QUARKUS_MONGODB_MIGRATIONS_ENABLED}, which holds its value.
     */
    private static String enabledValue(String resource) {
        String[] lines = resource.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].contains("QUARKUS_MONGODB_MIGRATIONS_ENABLED")) {
                return lines[i + 1];
            }
        }
        throw new AssertionError("QUARKUS_MONGODB_MIGRATIONS_ENABLED not set in:\n" + resource);
    }

    @Migration(id = "noop", order = 1)
    public static class Noop implements MongoMigration {
        @Override
        public void apply(MigrationContext context) {
        }
    }
}
