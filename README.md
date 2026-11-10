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

- **Migrations as Java code.** Each migration is a CDI bean, blocking or reactive, so it can inject the Quarkus
  `MongoClient`, `ReactiveMongoClient`, repositories, or any other bean.
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

Planned:

- Importing migration history from a previous tool, so already applied changes are not run again.
- A Dev UI page listing applied and pending migrations.
- A health check.

## Usage

Add the dependency:

```xml
<dependency>
    <groupId>io.quarkiverse.mongodb-migrations</groupId>
    <artifactId>quarkus-mongodb-migrations</artifactId>
    <version>${quarkus-mongodb-migrations.version}</version>
</dependency>
```

### Writing a migration

Annotate a class with `@Migration` and implement `MongoMigration`. The `id` is recorded once the migration is
applied, and `order` sets the run order. Both must be unique.

```java
import static com.mongodb.client.model.Indexes.ascending;
import static io.quarkiverse.mongodb.migrations.MigrationHelpers.createIndex;

@Migration(id = "create-users-email-index", order = 1)
public class CreateUsersEmailIndex implements MongoMigration {

    @Inject
    MongoClient client;

    @Override
    public void apply() {
        createIndex(client.getDatabase("app"), "users", "email_idx", ascending("email"),
                new IndexOptions().unique(true));
    }
}
```

For reactive code, implement `ReactiveMongoMigration` and return a `Uni`:

```java
@Migration(id = "insert-default-user", order = 2)
public class InsertDefaultUser implements ReactiveMongoMigration {

    @Inject
    ReactiveMongoClient client;

    @Override
    public Uni<Void> apply() {
        return client.getDatabase("app").getCollection("users")
                .insertOne(new Document("email", "admin@example.com"))
                .replaceWithVoid();
    }
}
```

Set `runAlways = true` for a migration that should run on every migration run, even after it has been applied.

Never change the `id` of an applied migration, or it runs again.

### Failures and rollback

A migration that throws, or whose `Uni` fails, stops the run and is not recorded, so it runs again next time. Before
the run stops, the migration's `rollback()` is called to undo its partial changes. It's optional: both interfaces
provide a default that does nothing.

```java
@Override
public void rollback() {
    dropIndex(client.getDatabase("app"), "users", "email_idx");
}
```

Only the failed migration is rolled back. Migrations that succeeded earlier in the same run stay applied. If
`rollback()` itself fails, its error is attached to the migration failure as a suppressed exception.

Rollback is best effort, since MongoDB can't undo most changes in a transaction. Writing migrations to be safe to
re-run (as the helpers are) means a retry works even when a rollback couldn't clean up.

Each failed attempt is also recorded in the `migrationFailures` collection, so it can be inspected after the logs are
gone (for example once a Kubernetes job is cleaned up):

```js
{
  migrationId: "backfill-tenant-names",
  className: "com.example.migrations.BackfillTenantNames",
  failedAt: ISODate("…"),           // MongoDB server time
  instanceId: "orders-init-x7k2p",
  durationMillis: 1830,
  error: { type: "…", message: "…", stackTrace: "…" },   // first 20 frames, message capped at 2000 characters
  rollbackError: null                                    // or { type, message } if the rollback failed too
}
```

Entries are removed after `failure-retention` (30 days) by a TTL index. Error messages can contain data from the
failed operation, so anyone who can read the collection can see them. Set `record-failures=false` to turn this off.

### Running migrations

Run pending migrations when the application starts:

```properties
quarkus.mongodb-migrations.migrate-at-start=true
```

Or inject `MongoMigrator` and call `migrate()`, which returns the ids of the migrations that ran.

The history and lock are stored in the database configured for the MongoDB client (`quarkus.mongodb.database`), or
in `quarkus.mongodb-migrations.database` if set.

### Running as a Kubernetes job

With `quarkus-kubernetes` (or `quarkus-openshift`) in the application, the generated manifests include a Job named
`<application>-mongodb-migrations-init` that runs the migrations and exits. The application's pods wait for the Job
to finish, and migrations are disabled in the pods themselves. Keep `migrate-at-start=true`: the Job uses it.

Without those extensions, the same can be done by hand: run the application with `QUARKUS_INIT_AND_EXIT=true` as a
one-off job, and set `QUARKUS_MONGODB_MIGRATIONS_ENABLED=false` on the regular deployment.

### Helpers

`MigrationHelpers` (blocking, on `MongoDatabase`) and `ReactiveMigrationHelpers` (returning `Uni`, on
`ReactiveMongoDatabase`) provide steps that are safe to run more than once:

| Helper | What it does |
|---|---|
| `createCollection` / `dropCollection` | Creates the collection unless it exists, or drops it if it exists. |
| `renameCollection` | Renames a collection, unless the source is already gone. |
| `collectionExists` | Whether a collection exists. |
| `createIndex` | Creates a named index unless an index with that name exists. |
| `createTtlIndex` | Creates a named TTL index on a date field. |
| `dropIndex` | Drops a named index if it exists. |
| `indexExists` | Whether a named index exists. |
| `renameField` / `removeField` | Renames or removes a field in every document that has it. |

## Configuration

| Property | Default | Description |
|---|---|---|
| `quarkus.mongodb-migrations.migrate-at-start` | `false` | Run pending migrations at startup. |
| `quarkus.mongodb-migrations.enabled` | `true` | Whether startup migrations run. Set to `false` in pods when a Kubernetes job runs them. |
| `quarkus.mongodb-migrations.mongo-client-name` | default client | Client that stores the history and lock. Fixed at build time. |
| `quarkus.mongodb-migrations.database` | client's database | Database that stores the history and lock. |
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

## Project layout

| Module | Purpose |
|---|---|
| `runtime/` | The extension's runtime code, which ends up in the application. |
| `deployment/` | Build-time processing: migration discovery, validation and wiring. |
| `integration-tests/` | Tests that run a real Quarkus application using the extension. |
| `docs/` | Extension documentation in Antora format. |

## License

[Apache License 2.0](LICENSE)
