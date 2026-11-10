# Quarkus MongoDB Migrations

A Quarkus extension for managing MongoDB migrations written as plain Java code.

> **Status:** early development. Nothing has been released yet, and the API and configuration may still change.

## Overview

Applications that store data in MongoDB still need to change it over time: create or drop indexes, add TTLs,
backfill fields, reshape documents, or move data between collections. Each change should run exactly once, in order,
and only one instance of the application should run it, even when many replicas start at the same time.

This extension handles that for you. It is built specifically for Quarkus and supports MongoDB only, so it can use
what a Quarkus extension does well instead of working around it.

## Features

- **Migrations as Java code.** Each migration is a CDI bean, blocking or reactive. It receives a context with the
  database to migrate, idempotent helpers and the client, and can inject repositories or any other bean.
- **Checked at build time.** Migrations are discovered from the Jandex index. Duplicate ids, duplicate order numbers,
  and classes that don't implement a migration interface fail the build.
- **Run once, in order.** Applied migrations are recorded in a MongoDB collection. Pending ones run in order, and
  applied ones are skipped, unless a migration is marked to always run.
- **Safe with many replicas.** A lock stored in MongoDB ensures only one instance runs migrations. Its lease expires
  on the server clock, so if an instance dies mid-run, another one takes over once the lease runs out.
- **Kubernetes init job.** With the Quarkus Kubernetes or OpenShift extension, a Job that runs the migrations is
  generated, and the application waits for it.
- **Idempotent helpers** for creating and dropping indexes and collections, TTL indexes, and renaming or removing
  fields, in blocking and reactive variants.
- **Opt-in transactions.** A migration marked `transactional` runs in a MongoDB transaction, together with its history
  entry, so both commit or neither does.
- **Changed-document counts** reported by migrations are logged and stored in the history.
- **Dev UI card** showing which migrations are applied or pending, their latest failures and the lock holder, with a
  button to run pending migrations.

Planned:

- Importing migration history from a previous tool, so already applied changes are not run again.
- A health check.

## Getting started

Add the dependency:

```xml
<dependency>
    <groupId>io.quarkiverse.mongodb-migrations</groupId>
    <artifactId>quarkus-mongodb-migrations</artifactId>
    <version>${quarkus-mongodb-migrations.version}</version>
</dependency>
```

Write a migration:

```java
import static com.mongodb.client.model.Indexes.ascending;

@Migration(id = "create-users-email-index", order = 1)
public class CreateUsersEmailIndex implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.createIndex("users", "email_idx", ascending("email"), new IndexOptions().unique(true));
    }
}
```

And run pending migrations when the application starts:

```properties
quarkus.mongodb-migrations.migrate-at-start=true
```

## Documentation

The [documentation](docs/modules/ROOT/pages/index.adoc) covers reactive and transactional migrations, failures and
rollback, the helpers, running migrations as a Kubernetes job, the Dev UI, and testing migrations.

## Configuration

| Property | Default | Description |
|---|---|---|
| `quarkus.mongodb-migrations.migrate-at-start` | `false` | Run pending migrations at startup. |
| `quarkus.mongodb-migrations.enabled` | `true` | Whether startup migrations run. Set to `false` in pods when a Kubernetes job runs them. |
| `quarkus.mongodb-migrations.mongo-client-name` | default client | Client that migrations work with and that stores the history and lock. Fixed at build time. |
| `quarkus.mongodb-migrations.database` | client's database | Database that stores the history and lock. Migrations still work on the client's database. |
| `quarkus.mongodb-migrations.history-collection` | `migrationHistory` | Collection that records applied migrations. |
| `quarkus.mongodb-migrations.lock-collection` | `migrationLock` | Collection that holds the lock. |
| `quarkus.mongodb-migrations.record-failures` | `true` | Record each failed attempt in the failure collection. |
| `quarkus.mongodb-migrations.failure-collection` | `migrationFailures` | Collection that records failed attempts. |
| `quarkus.mongodb-migrations.failure-retention` | `30d` | How long failed attempts are kept, enforced by a TTL index. |
| `quarkus.mongodb-migrations.lock-lease-duration` | `60s` | How long the lock lasts if its holder stops renewing it. |
| `quarkus.mongodb-migrations.lock-acquire-timeout` | `5m` | How long to wait for the lock before failing. |
| `quarkus.mongodb-migrations.instance-id` | `${HOSTNAME:${quarkus.uuid}}` | Name of this instance, stored with the lock and shown in logs. The pod name in Kubernetes, otherwise a random id per startup. Diagnostics only. |

## Building

Requires JDK 17+, Maven 3.9+, and Docker (tests start MongoDB with Quarkus Dev Services).

```bash
mvn clean install
```

### Native build

The integration tests can also run against a native executable of the test application. In CI, run the
**Native Tests** workflow manually from the Actions tab, on the branch to test.

On Linux, build the executable in a container, so no local GraalVM is needed:

```bash
mvn clean install -Dnative -Dquarkus.native.container-build
```

On macOS, the `native-macos` profile activates automatically when `-Dnative` is used on a Mac:

```bash
mvn clean install -Dnative
```

It also builds the executable with the Mandrel builder image, so no local GraalVM is needed. That executable is a
Linux one, which macOS can't run, so the profile packages it into a container image (with the
`quarkus-container-image-docker` extension, added only by this profile, and
`integration-tests/src/main/docker/Dockerfile.native`), and the native integration tests run that image with Docker.

On both, add `-Dnative.surefire.skip` to skip the JVM tests and only run the native ones.

## Project layout

| Module | Purpose |
|---|---|
| `runtime/` | The extension's runtime code, which ends up in the application. |
| `deployment/` | Build-time processing: migration discovery, validation and wiring. |
| `integration-tests/` | Tests that run a real Quarkus application using the extension. |
| `docs/` | Extension documentation in Antora format. |

## License

[Apache License 2.0](LICENSE)
