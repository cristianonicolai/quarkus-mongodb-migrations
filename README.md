# Quarkus MongoDB Migrations

A Quarkus extension for managing MongoDB migrations written as plain Java code.

> **Status:** early development. Nothing has been released yet, and the API and configuration described below are
> the intended design and may change.

## Overview

Applications that store data in MongoDB still need to change it over time: create or drop indexes, add TTLs,
backfill fields, reshape documents, or move data between collections. Each change should run exactly once, in order,
and only one instance of the application should run it, even when many replicas start at the same time.

This extension handles that for you. It is built specifically for Quarkus and supports MongoDB only, so it can use
what a Quarkus extension does well instead of working around it.

## Goals

- **Migrations as Java code.** Each migration is a CDI bean, so it can inject the MongoDB client, repositories, or
  any other bean, instead of looking them up statically.
- **Build-time discovery.** Migrations are found at build time from the Jandex index, with no runtime classpath
  scanning, so startup stays fast and native images work without extra configuration.
- **Run once, in order.** Applied migrations are recorded in a MongoDB collection. Pending ones run in a defined
  order, and already applied ones are skipped.
- **Safe with many replicas.** A lock stored in MongoDB, with a lease that expires, ensures only one instance runs
  migrations. If an instance dies mid-run, the lock is eventually released.
- **Familiar Quarkus configuration.** Configured under `quarkus.mongodb-migrations.*` (for example
  `migrate-at-start`), with support for named MongoDB clients.
- **Runs before the application.** Migrations complete during startup, before the rest of the application uses the
  database.
- **Easy to adopt in existing projects.** Migration history from a previous tool can be imported, so already applied
  changes are not run again.

Later, possibly:

- A Dev UI page listing applied and pending migrations.
- A health check.
- Running migrations as a Kubernetes init job before application pods start.
- Reactive MongoDB client support.

## Intended usage

This is a sketch of the planned API, not working code yet.

```java
@Migration(id = "add-email-index", order = 1)
public class AddEmailIndex implements MongoMigration {

    @Inject
    MongoClient mongoClient;

    @Override
    public void apply() {
        mongoClient.getDatabase("app")
                .getCollection("users")
                .createIndex(Indexes.ascending("email"), new IndexOptions().unique(true));
    }
}
```

```properties
quarkus.mongodb-migrations.migrate-at-start=true
```

## Building

Requires JDK 17+ and Maven 3.9+.

```bash
mvn clean install
```

## Project layout

| Module | Purpose |
|---|---|
| `runtime/` | The extension's runtime code, which ends up in the application. |
| `deployment/` | Build-time processing: migration discovery and wiring. |
| `integration-tests/` | Tests that run a real Quarkus application using the extension. |
| `docs/` | Extension documentation in Antora format. |

## License

[Apache License 2.0](LICENSE)
