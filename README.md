[![License](https://img.shields.io/github/license/toolarium/toolarium-leader-election)](https://github.com/toolarium/toolarium-leader-election/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.toolarium/toolarium-leader-election/1.3.0)](https://search.maven.org/artifact/com.github.toolarium/toolarium-leader-election/1.3.0/jar)
[![javadoc](https://javadoc.io/badge2/com.github.toolarium/toolarium-leader-election/javadoc.svg)](https://javadoc.io/doc/com.github.toolarium/toolarium-leader-election)

# toolarium-leader-election

A Java library for leader election across distributed nodes. It provides a unified API with multiple pluggable strategies: **FILE**, **NETWORK** (JGroups), **KUBERNETES**, and **DATABASE** (JDBC). The strategy can be selected explicitly or determined automatically based on the runtime environment.


## Strategies

| Strategy | Mechanism | Extra dependency required |
|---|---|---|
| `FILE` | OS-level file lock on a shared filesystem | none |
| `NETWORK` | JGroups cluster membership (UDP multicast) | `org.jgroups:jgroups` |
| `KUBERNETES` | Kubernetes lease / endpoints lock | `io.kubernetes:client-java` + `client-java-extended` |
| `DATABASE` | JDBC optimistic locking against a shared table | JDBC driver for your database |

A strategy must always be specified explicitly; the factory will throw if none is provided.


## Adding the library

**Gradle**
```gradle
implementation 'com.github.toolarium:toolarium-leader-election:1.3.0'
```

**Maven**
```xml
<dependency>
    <groupId>com.github.toolarium</groupId>
    <artifactId>toolarium-leader-election</artifactId>
    <version>1.3.0</version>
</dependency>
```

The library only brings `slf4j-api` as a transitive dependency. You must provide an SLF4J binding (e.g., Logback) at runtime.


## Strategy-specific dependencies

### FILE: no extra dependency

The FILE strategy uses standard JDK `java.nio.channels.FileLock`. No additional library is needed. All nodes must share the same filesystem path.

### NETWORK (JGroups)

```gradle
implementation 'org.jgroups:jgroups:5.5.6.Final'
```

```xml
<dependency>
    <groupId>org.jgroups</groupId>
    <artifactId>jgroups</artifactId>
    <version>5.5.6.Final</version>
</dependency>
```

### KUBERNETES

```gradle
implementation 'io.kubernetes:client-java:27.0.0'
implementation 'io.kubernetes:client-java-extended:27.0.0'
```

```xml
<dependency>
    <groupId>io.kubernetes</groupId>
    <artifactId>client-java</artifactId>
    <version>27.0.0</version>
</dependency>
<dependency>
    <groupId>io.kubernetes</groupId>
    <artifactId>client-java-extended</artifactId>
    <version>27.0.0</version>
</dependency>
```

### DATABASE

Only a JDBC driver for your target database is needed. All database logic uses `java.sql.*` from the JDK.

**PostgreSQL**
```gradle
implementation 'org.postgresql:postgresql:42.7.13'
```

**Oracle**
```gradle
implementation 'com.oracle.database.jdbc:ojdbc11:23.26.3.0.0'
```

**MySQL**
```gradle
implementation 'com.mysql:mysql-connector-j:9.3.0'
```

**MariaDB**
```gradle
implementation 'org.mariadb.jdbc:mariadb-java-client:3.5.3'
```

**SQL Server**
```gradle
implementation 'com.microsoft.sqlserver:mssql-jdbc:12.10.0.jre11'
```

**H2 (embedded / testing)**
```gradle
implementation 'com.h2database:h2:2.4.240'
```


## Usage

### Fluent Builder API

The recommended way to create a leader elector is the fluent builder. It is a two-step API: first configure the common identity and timing on `LeaderElectionFactory.builder()`, then select a strategy to obtain a strategy-specific sub-builder with its own options. A strategy must always be specified explicitly.

```java
// FILE strategy; basePath is optional (defaults to java.io.tmpdir)
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("my-ns").name("my-service").identity("node-1")
    .timeout(Duration.ofSeconds(10)).renewDeadline(Duration.ofSeconds(7)).retryPeriod(Duration.ofSeconds(3))
    .file()
        .basePath("/shared/nfs/locks")
        .build();
```

```java
// NETWORK strategy
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("my-ns").name("my-service").identity("node-1")
    .network().build();
```

```java
// KUBERNETES strategy
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("my-ns").name("my-service").identity("node-1")
    .kubernetes().build();
```

```java
// DATABASE strategy; minimal setup (dialect auto-detected on first connection)
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("my-ns").name("my-service").identity("node-1")
    .database()
        .dataSource(ds)
        .build();
```

```java
// DATABASE strategy; explicit dialect structure (e.g. Oracle)
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("my-ns").name("my-service").identity("node-1")
    .database()
        .dataSource(ds)
        .databaseStructure(new OracleLeaderElectionDatabaseStructure())
        .build();
```

The builder `timeout()`, `renewDeadline()`, `retryPeriod()`, and `closeTimeout()` are common to all strategies. Call `.file()`, `.database()`, `.network()`, or `.kubernetes()` to select a strategy; those timing values are applied to the strategy-specific configuration automatically.

### LeaderElectionInformation

`LeaderElectionInformation` identifies the election group and this node within it.

| Constructor | When to use |
|---|---|
| `new LeaderElectionInformation("namespace", "name", "identity")` | Kubernetes strategy (required) or multi-group deployments |
| `new LeaderElectionInformation("groupName")` | FILE, NETWORK, and DATABASE strategies; the value becomes both the group name and the node identity |

For the DATABASE strategy the election group key is `"namespace.name"` (without the identity), so all nodes sharing the same namespace+name form one election group.

### ILeaderElector API

| Method | Description |
|---|---|
| `elect()` | Participate in the election and block until the first result is known. Returns `true` if this node is elected leader. Idempotent; safe to call more than once. |
| `elect(Duration timeout)` | Same as `elect()` but gives up and returns `false` if the timeout elapses before the first result is known. |
| `runIfLeader(String name, ILeaderTask task)` | Execute a task on the calling thread only if this node is currently leader. The task receives an `ILeaderLease` to check and extend the lease mid-execution. Returns `true` if the task ran, `false` if this node was not the leader. |
| `runIfLeader(String name, Runnable task)` | Convenience overload; wraps a `Runnable` as an `ILeaderTask`. |
| `isLeader()` | Returns `true` if this node currently holds the lease. Accurate immediately after `elect()` returns. |
| `getLeaderElectorTimestamp()` | Returns the `Instant` when this node last confirmed its leadership, or `null` if not leader. |
| `getId()` | Returns the unique instance identifier for this elector. |
| `close()` | Release the lease and stop the election loop. Registered as a JVM shutdown hook automatically. |

Both `elect()` variants throw `InterruptedException` if the calling thread is interrupted while waiting.

### runIfLeader and ILeaderLease

`runIfLeader` executes work on the calling thread only when this node holds leadership. If leadership is lost while the task runs, the task thread is interrupted automatically.

```java
// Simple Runnable; no lease needed
elector.runIfLeader("do-work", () -> {
    processQueue();
});

// ILeaderTask; access the lease inside the task
elector.runIfLeader("do-work", lease -> {
    while (!Thread.currentThread().isInterrupted()) {
        processQueue();

        // check that leadership is still held
        lease.verify(); // throws LeaderElectionException if lost

        // or extend the lease deadline explicitly
        if (!lease.tryExtend()) {
            break; // leadership lost
        }
    }
});
```

#### ILeaderLease API

| Method | Description |
|---|---|
| `isValid()` | Returns `true` if this node still holds the lease. |
| `verify()` | Throws `LeaderElectionException` if leadership has been lost. Convenient for checkpoint calls inside a long-running task. |
| `tryExtend()` | Attempts to push the lease deadline further out. Returns `true` on success. For FILE and DATABASE strategies this performs an actual renewal; for NETWORK and KUBERNETES it is a no-op that returns `isLeader()`. |
| `getLeaderSince()` | Returns the `Instant` when the last successful renewal or extension was recorded. |
| `getRemainingLeadTime()` | Approximate time until the lease expires, computed from `getLeaderSince()` and `timeout`. |
| `isExpiringSoon(Duration threshold)` | Returns `true` if the remaining lead time is less than `threshold`, useful for deciding when to call `tryExtend()`. |
| `getId()` | The elector's unique instance identifier. |
| `getLeaderElectionInformation()` | The `ILeaderElectionInformation` for this election group. |
| `getLeaderElectionConfiguration()` | The `ILeaderElectionConfiguration` in effect. |

#### Post-elect() behavior per strategy

`elect()` always blocks until the first result is known, regardless of strategy:

| Strategy | How `elect()` unblocks |
|---|---|
| `DATABASE` | The first election tick fires immediately in the scheduler thread (`initialDelay = 0`). `elect()` blocks until that tick completes and returns `isLeader()`. `elect(Duration)` returns `false` if the timeout elapses before the first tick finishes; use a timeout of at least one `retryPeriod` (or a few seconds) to give the tick time to complete, especially against remote databases or pools with an acquisition timeout. |
| `FILE` | Blocks until the file lock is acquired or confirmed unavailable (~200 ms). |
| `NETWORK` | Leadership determined from the initial JGroups membership view. Returns immediately when elected; otherwise blocks until the result is confirmed (up to `timeout`). |
| `KUBERNETES` | Blocks until the background update thread delivers the first leadership callback. |

```java
if (leaderElector.elect(Duration.ofSeconds(5))) {
    runTask();
}
```

### Explicit strategy selection (direct factory API)

```java
ILeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.FILE,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    new LeaderElectionConfiguration(10));
```

### FILE strategy with custom path

Via builder (recommended):

```java
ILeaderElector leaderElector = LeaderElectionFactory.builder()
    .namespace("namespace").name("my-service").identity("node-1")
    .timeout(Duration.ofSeconds(10)).renewDeadline(Duration.ofSeconds(7)).retryPeriod(Duration.ofSeconds(3))
    .file().basePath("/shared/nfs/locks").build();
```

Or directly:

```java
FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration(
    "/shared/nfs/locks",
    Duration.ofSeconds(10),
    Duration.ofSeconds(7),
    Duration.ofSeconds(3));

ILeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.FILE,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    config);
```

### NETWORK strategy (JGroups)

```java
ILeaderElector leaderElector = LeaderElectionFactory.builder()
    .namespace("namespace").name("my-service").identity("node-1")
    .network().build();

leaderElector.elect();
```

### DATABASE strategy

Pass any standard `javax.sql.DataSource`, a connection pool like HikariCP is recommended for production.

Via builder (recommended):

```java
PGSimpleDataSource ds = new PGSimpleDataSource();
ds.setUrl("jdbc:postgresql://db-host:5432/mydb");
ds.setUser("user");
ds.setPassword("pass");

ILeaderElector leaderElector = LeaderElectionFactory.builder()
    .namespace("namespace").name("my-service").identity("node-1")
    .database().dataSource(ds).build();

leaderElector.elect();
```

Or directly:

```java
DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(10);
config.setDataSource(ds);

ILeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.DATABASE,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    config);

leaderElector.elect();
```

The library creates and manages the required `LeaderElection` table automatically. On startup it inspects the table schema and, if a v1.0.0 installation is detected (column named `timestamp`), renames it to `lease_ts` automatically. On new installations a secondary index on the `instance` column is also created; existing installations can add it manually with `CREATE INDEX idx_leaderelection_instance ON LeaderElection(instance)`.

> **Externally managed schemas (Flyway, Liquibase, no DDL rights)**
> If the database user has no DDL privileges, or the table is managed by a migration tool, set `allowDdl=false` on the configuration. The library will then fail fast with a descriptive error, including the exact DDL to apply, instead of attempting `CREATE TABLE` or `ALTER TABLE` at startup:
>
> ```java
> config.setAllowDdl(false);  // disable runtime DDL; elect() throws if the table or column is missing
> ```
>
> Expected columns: `id VARCHAR(36)`, `name VARCHAR(512)`, `instance VARCHAR(255)`, `username VARCHAR(255)`, `lease_ts BIGINT NOT NULL`, `force_release_deadline BIGINT NULL` (primary key on `name`).

> **Connection pool requirement**
> Each election tick borrows one connection from the `DataSource`. Ensure the pool is configured with a **connection-acquisition timeout** (e.g. HikariCP `connectionTimeout`, Agroal `acquisition-timeout`). An unbounded wait blocks the scheduler thread indefinitely, delaying step-down detection.
> The minimum recommended `renewDeadline` is **3 seconds**: each tick issues up to 3 sequential statements, each with a 1-second minimum JDBC timeout; at shorter values the total per-tick latency can exceed the renew deadline.

For databases with higher latency (e.g., Oracle), use the explicit `Duration` constructor to control `timeout`, `renewDeadline`, and `retryPeriod` independently:

```java
DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
    Duration.ofSeconds(30),
    Duration.ofSeconds(20),
    Duration.ofSeconds(5));
```

If the database becomes temporarily unavailable, the DATABASE strategy applies exponential back-off (capped at 60 s) so scheduler threads do not hammer a down database.

#### Database-specific SQL structures

By default the library uses `JDBCLeaderElectionDatabaseStructure`, which is compatible with PostgreSQL, H2, and most ANSI-SQL databases. For databases that need dialect-specific DDL, supply the matching structure class:

| Database | Structure class |
|---|---|
| Generic / PostgreSQL / H2 | `JDBCLeaderElectionDatabaseStructure` *(default)* |
| MySQL | `MySQLLeaderElectionDatabaseStructure` |
| MariaDB | `MariaDBLeaderElectionDatabaseStructure` |
| Oracle | `OracleLeaderElectionDatabaseStructure` |
| SQL Server | `SQLServerLeaderElectionDatabaseStructure` |

```java
// Oracle, via builder
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("ns").name("app").identity("node-1")
    .database()
        .dataSource(ds)
        .databaseStructure(new OracleLeaderElectionDatabaseStructure())
        .build();

// MySQL, directly on config
DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(10);
config.setDataSource(ds);
config.setLeaderElectionDatabaseStructure(new MySQLLeaderElectionDatabaseStructure());
```

#### Custom table name

All structure classes validate and reject SQL-unsafe names:

```java
JDBCLeaderElectionDatabaseStructure structure = new JDBCLeaderElectionDatabaseStructure();
structure.setTableName("myschema.LeaderElection"); // schema-qualified names are supported
config.setLeaderElectionDatabaseStructure(structure);
```


## Configuration

`LeaderElectionConfiguration` controls the timing parameters:

| Parameter | Default | Description |
|---|---|---|
| `timeout` | 10 s | How long a lease is valid. Another node can take over after this elapses. |
| `renewDeadline` | 5 s | The leader must renew its lease within this duration. Must be < `timeout`. |
| `retryPeriod` | 5 s | How often candidates attempt to acquire or renew the lease. Must be ≤ `renewDeadline`. |
| `closeTimeout` | 5 s | Maximum time to wait for a clean shutdown when `close()` is called. |

The convenience constructor `new LeaderElectionConfiguration(timeoutInSeconds)` computes `renewDeadline` and `retryPeriod` automatically from the timeout value. The no-arg constructor defaults to (10 s / 5 s / 5 s).

```java
// Override close timeout if your shutdown budget is tight
config.setCloseTimeout(Duration.ofSeconds(2));
```


## Administration API

`ILeaderElectionAdministration` provides a management view of all electors currently holding leadership across the cluster. Obtain it from the singleton factory:

```java
ILeaderElectionAdministration admin = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
```

### Listing active leaders

```java
Collection<ILeaderElectorView> leaders = admin.getElectors();
for (ILeaderElectorView view : leaders) {
    System.out.println(view.getId()
        + " | " + view.getLeaderElectionInformation().getElectionGroupName()
        + " | last seen: " + view.getLeaderElectorTimestamp()
        + " | remaining: " + view.getRemainingLeadTime());
}
```

For the **DATABASE** strategy `getElectors()` returns leaders held by **all nodes in the cluster**; records are read directly from the shared database table. For all other strategies only electors running in this JVM are returned.

### ILeaderElectorView API

| Method | Description |
|---|---|
| `getId()` | The unique identifier of this leader elector (stable UUID). |
| `getLeaderElectionInformation()` | Namespace, name, and identity of this elector. |
| `getLeaderElectionConfiguration()` | Timing configuration (timeout, renewDeadline, retryPeriod, closeTimeout). |
| `getLeaderElectorTimestamp()` | The `Instant` when leadership was last confirmed. |
| `getRemainingLeadTime()` | Approximate time until the renew deadline is reached (default method). |

### Force-releasing a leader

```java
// gracefully release a leader so another candidate can take over immediately
boolean released = admin.forceRelease("elector-uuid");
```

For a **local** elector `forceRelease` calls `close()`; the elector stops and relinquishes its lock or database row. For a **remote DATABASE** leader the row is deleted from the shared database table directly so another node can claim leadership without waiting for the lease to expire.

`forceRelease(null)`, `forceRelease("")`, and `forceRelease("unknown-id")` return `false` without throwing.


## Testing

### Unit tests

```bash
cb test
```

Runs all unit tests. No Docker required.

### Integration tests

```bash
cb test -Pintegration
```

Runs the full test suite including container-based integration tests. Requires Docker (or a compatible runtime such as Rancher Desktop / nerdctl).

| Integration test | Container |
|---|---|
| `PostgreSQLLeaderElectionTest` | PostgreSQL |
| `MySQLLeaderElectionTest` | MySQL |
| `MariaDBLeaderElectionTest` | MariaDB |
| `OracleLeaderElectionTest` | Oracle |
| `SQLServerLeaderElectionTest` | SQL Server |
| `KubernetesLeaderElectionTest` | k3s (lightweight Kubernetes) |

Each integration test starts its own container via [Testcontainers](https://testcontainers.com/), runs the full leader-election lifecycle against a real database or Kubernetes cluster, then tears the container down. Oracle has a longer cold-start time; allow several minutes for that container to become ready.


## Built With

* [cb](https://github.com/toolarium/common-build), the toolarium common build

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository].
