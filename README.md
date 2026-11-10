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
| `MigrationContext` / `ReactiveMigrationContext` | Passed to `apply` and `rollback`: `database()`, `collection(name)`, `client()`, `session()` (transactional migrations), the [helpers](#helpers), `forEachDatabase`/`forEachCollection`, and `recordChanges(...)`. |
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

For anything the [helpers](#helpers) don't cover, the context gives full access to the MongoDB driver:

```java
MongoDatabase database = context.database();                          // the database being migrated
MongoCollection<Document> orders = context.collection("orders");      // one of its collections
orders.aggregate(List.of(match(eq("status", "open")), out("openOrders")));
context.client().getDatabase("admin").runCommand(new Document("ping", 1));   // the client, for anything else
```

For a migration that should run in a transaction, see [Transactional migrations](#transactional-migrations).

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

The [field, array, `updateMany`, `deleteMany` and `updateEach` helpers](#fields) count their own changes. For changes made with the driver, pass
the result to the context:

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
  outside the transaction. The field, array, `updateMany`, `deleteMany` and `updateEach` helpers use the session
  automatically.
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

The migration contexts provide helpers that work on the database being migrated. In a `ReactiveMigrationContext`
they have the same names and arguments, and return a `Uni`.

The collection, index, field and array helpers are safe to run more than once: they skip work that is already done,
so a migration that failed halfway can be retried. `updateMany`, `deleteMany` and `updateEach` run the update you give
them, so make that update safe to repeat, for example with a filter that skips documents already migrated.

#### Collections

```java
if (!context.collectionExists("auditLog")) { ... }       // whether the collection exists
context.createCollection("auditLog");                    // creates it, unless it already exists
context.renameCollection("auditLog", "auditEvents");     // renames it, unless the source is already gone
context.dropCollection("modelExecutionStats");           // drops it, if it exists
```

#### Indexes

Indexes are identified by name, so creating one that already exists is skipped.

```java
if (!context.indexExists("users", "email_idx")) { ... }                       // whether a named index exists
context.createIndex("users", "email_idx", ascending("email"));                 // creates a named index
context.createIndex("users", "email_idx", ascending("email"), new IndexOptions().unique(true));   // with options
context.createTtlIndex("personalAccessTokens", "expires_ttl", "expiresAt", Duration.ofDays(30)); // TTL on a date field
context.dropIndex("users", "email_idx");                                        // drops it, if it exists
```

#### Fields

Each of these returns the number of documents it changed, and adds it to the migration's
[changed-document count](#counting-changed-documents).

```java
// Sets a value on every document matching the filter.
context.setField("tenants", empty(), "deleted", false);

// Sets several values at once.
context.setFields("plans", eq("_id", "trial"), Map.of("maxThreads", 4, "configurable", true));

// Sets a default where the field is missing or null, leaving existing values alone.
context.setFieldIfMissing("tenants", "createdAt", Instant.EPOCH);

// The same, limited to documents matching a filter: here, only plans that have maps settings.
context.setFieldIfMissing("plans", exists("mapsSettings"), "mapsSettings.allowTraffic", false);

// Renames a field in every document that has it.
context.renameField("users", "mail", "email");

// Removes a field from every document that has it.
context.removeField("registeredModels", "insightsQueryName");
```

#### Fields in arrays

These work on arrays of documents, such as a tenant's `members`. Documents without the array are skipped.

```java
// Sets a field on every element.
context.setFieldInArray("tenants", "members", "notified", true);

// Sets a field on the elements that match a filter, written on the element's own fields.
context.setFieldInArray("tenants", "apiKeys", eq("name", "timefold-platform"), "type", "PLATFORM");
context.setFieldInArray("tenants", "apiKeys", not(eq("name", "timefold-platform")), "type", "USER");

// Sets a default on the elements where the field is missing or null, leaving existing values alone.
context.setFieldInArrayIfMissing("tenants", "members", "status", "ACTIVE");

// Removes a field from every element that has it.
context.removeFieldInArray("tenants", "members", "legacyRole");
```

Element filters can combine conditions with `and`, `or` and `nor`. Arrays nested in arrays aren't covered; use the
driver directly with `context.collection(name)` for those.

#### Updates and deletes with the driver's builders

`updateMany` and `deleteMany` take the driver's filters and updates directly, for anything the field helpers don't
cover. Unlike calling the driver yourself, they count their changes and run in the transaction of a transactional
migration automatically.

```java
// Any update. The filter skips orders already migrated, so a retried migration doesn't increment twice.
context.updateMany("orders", and(eq("status", "failed"), ne("retry", true)),
        combine(set("retry", true), inc("attempts", 1)));

// An aggregation pipeline, for updates that use the document's own values:
// converting a field's type...
context.updateMany("RunStatistics", empty(), List.of(set("tenantId", new Document("$toString", "$tenantId"))));
// ...or copying one field to another.
context.updateMany("plans", empty(), List.of(set("maxThreadsQueued", "$maxConcurrentExecutions")));

// Deletes every matching document.
context.deleteMany("auditLog", lt("timestamp", Instant.now().minus(Duration.ofDays(365))));
```

#### A computed value per document

`updateEach` updates each document matching a filter with an update computed from that document, for values that
differ per document. Return `null` to leave a document unchanged. Documents are streamed and written in batches of
1,000, so memory stays flat on large collections.

```java
context.updateEach("registeredModels", exists("registrationKey", false),
        model -> set("registrationKey", UUID.randomUUID().toString()));

// With a projection, to read only the fields needed. It must keep _id: leave it out or include it.
context.updateEach("reports", exists("modelId"), include("modelId", "modelVersion"),
        report -> combine(
                set("dataSets.$[].modelId", report.getString("modelId")),
                set("dataSets.$[].modelVersion", report.getString("modelVersion")),
                unset("modelId"),
                unset("modelVersion")));
```

In a reactive context, `updateEach` returns `Uni<Long>`.

#### In transactional migrations

The field, array, `updateMany`, `deleteMany` and `updateEach` helpers run in the transaction automatically. The collection and index helpers
change the schema, which MongoDB doesn't allow in a transaction, so they refuse to run there.

#### Several databases

When data is split across databases, for example one per tenant, `forEachDatabase` runs an action for each database
whose name matches, with a context for it. `forEachCollection` does the same for the existing collections of a
database. MongoDB's own databases (`admin`, `local`, `config`) and `system.*` collections are never included.

```java
context.forEachDatabase(name -> isTenantId(name), tenant ->
        tenant.forEachCollection(name -> name.endsWith("-events"), collection ->
                tenant.createIndex(collection, "run_id_init_solution_asc", ascending("runId", "initSolution"))));
```

Changes made through the database contexts count towards the migration, and in a transactional migration they take
part in its transaction. Listing databases needs the `listDatabases` privilege. In a reactive context, both take a
function returning `Uni<Void>` and visit one database or collection at a time:

```java
context.forEachDatabase(name -> isTenantId(name), tenant ->
        tenant.setFieldIfMissing("settings", "version", 1).replaceWithVoid());
```

For a single other database, create a context for it from the same client:

```java
MigrationContext audit = new MigrationContext(context.client(), "audit");
audit.setFieldIfMissing("events", "version", 1);
```

The collection, index and `renameField`/`removeField` helpers are also available as static methods taking the
database as their first argument, in `MigrationHelpers` (blocking, on `MongoDatabase`) and `ReactiveMigrationHelpers`
(returning `Uni`, on `ReactiveMongoDatabase`).

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
