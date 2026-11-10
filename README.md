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

## Usage

Add the dependency:

```xml
<dependency>
    <groupId>io.quarkiverse.mongodb-migrations</groupId>
    <artifactId>quarkus-mongodb-migrations</artifactId>
    <version>${quarkus-mongodb-migrations.version}</version>
</dependency>
```

### API at a glance

| Type | What it is |
|---|---|
| `@Migration` | Marks a migration class: `id` and `order` (both unique), optional `runAlways` and `transactional`. |
| `MongoMigration` / `ReactiveMongoMigration` | What a migration implements: `apply(context)`, and optionally `rollback(context)`. The reactive one returns `Uni<Void>`. |
| `MigrationContext` / `ReactiveMigrationContext` | Passed to `apply` and `rollback`: `database()`, `collection(name)`, `client()`, `session()` (transactional migrations), the [helpers](#helpers), and `recordChanges(...)`. |
| `MongoMigrator` | Inject it to run pending migrations with `migrate()`. |
| `MigrationHelpers` / `ReactiveMigrationHelpers` | The helpers as static methods taking a database, for other databases. |
| `MigrationException` | Thrown when a migration run fails. |

The examples below use static imports from the driver's `Filters`, `Updates` and `Indexes` builders.

### Writing a migration

Annotate a class with `@Migration` and implement `MongoMigration`. The `id` is recorded once the migration is
applied, and `order` sets the run order. Both must be unique.

`apply` receives a `MigrationContext`: the database to migrate, with helpers that work on it, so a migration needs no
injected client or database name.

```java
import static com.mongodb.client.model.Indexes.ascending;

@Migration(id = "create-users-email-index", order = 1)
public class CreateUsersEmailIndex implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.createIndex("users", "email_idx", ascending("email"), new IndexOptions().unique(true));
        context.renameField("users", "mail", "email");
    }
}
```

For anything the helpers don't cover, `context.database()` and `context.collection(name)` give full access to the
MongoDB driver. `context.client()` returns the client itself, for other databases, admin commands, or sessions you
manage yourself. For a migration that should run in a transaction, see
[Transactional migrations](#transactional-migrations).

For reactive code, implement `ReactiveMongoMigration`. It receives a `ReactiveMigrationContext` with the same helpers,
returning `Uni`:

```java
@Migration(id = "insert-default-user", order = 2)
public class InsertDefaultUser implements ReactiveMongoMigration {

    @Override
    public Uni<Void> apply(ReactiveMigrationContext context) {
        return context.collection("users")
                .insertOne(new Document("email", "admin@example.com"))
                .replaceWithVoid();
    }
}
```

The context works on the database configured for the MongoDB client: `quarkus.mongodb.database`, or the database of
the client named by `quarkus.mongodb-migrations.mongo-client-name`. Migrations are still CDI beans, so they can also
inject repositories, other clients or any other bean.

Set `runAlways = true` for a migration that should run on every migration run, even after it has been applied.

Never change the `id` of an applied migration, or it runs again.

### Counting changed documents

A migration can report how many documents it changed. The count is logged, stored in the history entry as
`documentsChanged`, and shown in the Dev UI:

```
Ran migration backfill-order-status in 1204 ms, 4210 documents changed
```

The field helpers (`renameField`, `removeField`) count their own changes. For changes made with the driver, pass the
result to the context:

```java
context.recordChanges(context.collection("orders").updateMany(eq("status", null), set("status", "open")));
```

`recordChanges` takes an `UpdateResult`, `DeleteResult`, `InsertOneResult`, `InsertManyResult`, `BulkWriteResult`, or
a plain number. In a reactive migration, use it in the pipeline, for example `.invoke(context::recordChanges)`.

MongoDB only reports counts in each write's result, so changes that aren't reported can't be counted. A migration that
reports nothing has no count rather than 0, so it isn't mistaken for one that changed nothing. When a migration that
isn't transactional fails, the documents it reported before failing are stored in its failure record, showing how much
it changed before stopping.

### Transactional migrations

Set `transactional = true` for a migration whose data changes must be all-or-nothing. The extension starts a MongoDB
transaction, runs the migration in it, and writes the migration's history entry in the same transaction, so both
commit together or neither does:

```java
@Migration(id = "backfill-order-status", order = 4, transactional = true)
public class BackfillOrderStatus implements MongoMigration {

    @Override
    public void apply(MigrationContext context) {
        context.collection("orders").updateMany(context.session(), eq("status", null), set("status", "open"));
        context.renameField("orders", "state", "legacyState");
    }
}
```

- Pass `context.session()` to every driver call. The driver can't attach a session implicitly, so calls without it run
  outside the transaction. The field helpers (`renameField`, `removeField`) use the session automatically.
- When the migration fails, the transaction is aborted and `rollback` is not called. The abort discards every write
  made with `context.session()`; writes made without it ran outside the transaction and are kept. The failure is still
  recorded in the failure collection, which is written outside the transaction.
- MongoDB's transaction rules apply: a replica set or sharded cluster is required, transactions are limited in time
  (60 seconds by default) and size, and schema changes are not allowed. The schema helpers (`createIndex`,
  `dropIndex`, `createCollection`, `dropCollection`, `renameCollection`, `createTtlIndex`) refuse to run in a
  transactional migration. Put schema changes in a separate migration.
- Reactive migrations work the same way, with `ReactiveMigrationContext.session()`.

### Failures and rollback

A migration that throws, or whose `Uni` fails, stops the run and is not recorded, so it runs again next time. Before
the run stops, the migration's `rollback` is called with the same context to undo its partial changes. It's optional:
both interfaces provide a default that does nothing. `rollback` is not called for
[transactional migrations](#transactional-migrations): their transaction is aborted instead.

```java
@Override
public void rollback(MigrationContext context) {
    context.dropIndex("users", "email_idx");
}
```

Only the failed migration is rolled back. Migrations that succeeded earlier in the same run stay applied. If
`rollback` itself fails, its error is attached to the migration failure as a suppressed exception.

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
  rollbackError: null,                                   // or { type, message } if the rollback failed too
  documentsChangedBeforeFailure: 120                     // only if it reported changes, and wasn't transactional
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
in `quarkus.mongodb-migrations.database` if set. Each applied migration has a history entry:

```js
{
  _id: "backfill-order-status",       // the migration id
  order: 4,
  className: "com.example.migrations.BackfillOrderStatus",   // kept up to date if the class is renamed
  runAlways: false,
  transactional: true,
  appliedAt: ISODate("…"),            // first run, MongoDB server time
  appliedBy: "orders-init-x7k2p",     // instance-id of the first run
  lastAppliedAt: ISODate("…"),        // latest run; differs from appliedAt only for runAlways migrations
  lastAppliedBy: "orders-init-x7k2p",
  runCount: 1,
  durationMillis: 1204,               // latest run
  documentsChanged: 4210              // latest run, only if the migration reported changes
}
```

### Running as a Kubernetes job

With `quarkus-kubernetes` (or `quarkus-openshift`) in the application, the generated manifests include a Job named
`<application>-mongodb-migrations-init` that runs the migrations and exits. The application's pods wait for the Job
to finish, and migrations are disabled in the pods themselves. Keep `migrate-at-start=true`: the Job uses it.

Without those extensions, the same can be done by hand: run the application with `QUARKUS_INIT_AND_EXIT=true` as a
one-off job, and set `QUARKUS_MONGODB_MIGRATIONS_ENABLED=false` on the regular deployment.

### Dev UI

In dev mode, the Dev UI (`/q/dev-ui`) has a **MongoDB Migrations** card with two pages:

- **Status:** every migration with its state (applied, pending, failed or runs always), when and by which instance it
  was applied, its run count, how many documents it changed, and its latest failure with the rollback outcome. It
  also shows who holds the lock, and has a button to run the pending migrations without restarting the application.
  The card's label shows how many migrations are pending.
- **Migrations:** the migrations found at build time, with their order, id, type and class.

The Dev UI needs an HTTP server in the application, such as the one `quarkus-rest` brings in.

### Helpers

The migration contexts provide steps that are safe to run more than once, on the database being migrated:

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

The same helpers are available as static methods taking the database as their first argument, in `MigrationHelpers`
(blocking, on `MongoDatabase`) and `ReactiveMigrationHelpers` (returning `Uni`, on `ReactiveMongoDatabase`), for
migrations that work on another database.

### Testing a migration

A migration can be tested on its own by creating its context directly, for example against a test database:

```java
MigrationContext context = new MigrationContext(mongoClient, "test-db");
new BackfillOrderStatus().apply(context);
assertEquals(OptionalLong.of(4210), context.documentsChanged());
```

`new MigrationContext(client, database, session)` creates a context for a transactional migration, and
`ReactiveMigrationContext` has the same constructors.

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

## Project layout

| Module | Purpose |
|---|---|
| `runtime/` | The extension's runtime code, which ends up in the application. |
| `deployment/` | Build-time processing: migration discovery, validation and wiring. |
| `integration-tests/` | Tests that run a real Quarkus application using the extension. |
| `docs/` | Extension documentation in Antora format. |

## License

[Apache License 2.0](LICENSE)
