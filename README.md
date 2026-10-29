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
| `KUBERNETES` | Kubernetes Lease lock (`coordination.k8s.io`) | `io.kubernetes:client-java` + `client-java-extended` |
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

### Application example (Quarkus / CDI)

Build a leader elector per election group and let it take part in the election at startup:

```java
@ApplicationScoped
public class LeaderElectionService {

    @Inject
    DataSource dataSource;

    ILeaderElector leaderElector;

    // a LeaderElectionException fails the startup, the pod is restarted
    void onStart(@Observes StartupEvent event) throws Exception {
        leaderElector = LeaderElectionFactory.builder()
                .name("application-initialization")  // the election group
                .database()
                    .dataSource(dataSource)
                    .build();
        leaderElector.elect();
    }

    void onStop(@Observes ShutdownEvent event) {
        if (leaderElector != null) {
            leaderElector.close();
        }
    }
}
```

Then use the elector from a scheduled job without calling `elect()` again:

```java
@ApplicationScoped
public class HourlyJob {

    @Inject
    LeaderElectionService leaderElectionService;

    @Scheduled(every = "1h", concurrentExecution = ConcurrentExecution.SKIP)
    void run() throws LeaderElectionException {
        // runs only on the leader, returns false if another instance leads
        leaderElectionService.leaderElector.runIfLeader("hourly-cleanup", this::cleanup);
    }

    private void cleanup() {
        // ... application logic that must run on exactly one node
    }
}
```

The identity is resolved automatically from the hostname (or the `HOSTNAME` environment variable in Kubernetes), so each pod gets a unique identity without explicit configuration.

### Rarely running job

A job which runs e.g. once a day does not need a leader elector the whole day: build it in the job, elect, run the work on the leader and close it. Between the runs there is no leader elector and no load on the database.

```java
@Scheduled(cron = "0 0 2 * * ?") // every instance starts the job at 02:00
void dailyExport() throws Exception {
    try (ILeaderElector leaderElector = LeaderElectionFactory.builder()
            .name("daily-export")
            .database()
                .dataSource(dataSource)
                .build()) {
        leaderElector.elect(); // the first election round decides the leader
        leaderElector.runIfLeader("daily-export", lease -> {
            if (!exportService.isDone(LocalDate.now())) { // the leader of a later round skips done work
                exportService.export(lease);
            }
        });
    } // close: the lead is released
}
```

If the work takes longer than the lease timeout, call `tryExtend()` inside the task to push the deadline further out. This avoids switching to another instance mid-export:

```java
leaderElector.runIfLeader("daily-export", lease -> {
    for (Batch batch : exportService.getBatches(LocalDate.now())) {
        batch.export();
        lease.tryExtend(); // keep the lease alive while work continues
    }
});
```

A long job keeps the lead: the lease is renewed in the background every `retryPeriod` while the job runs, `tryExtend()` is not needed. The lead is lost only if the renewal fails for `renewDeadline` (e.g. the database is not reachable); then `tryExtend()` fails as well. Split an expensive job into small, committed steps and call `lease.verify()` between them: after a takeover the new leader continues with the next step instead of starting over. Only a short critical step which must not start with little lead time left renews right before:

```java
leaderElector.runIfLeader("daily-export", lease -> {
    for (Batch batch : exportService.getPendingBatches()) {
        // guard: do not start a batch that might outlive the lease
        if (lease.isExpiringSoon(Duration.ofSeconds(5)) && !lease.tryExtend()) {
            return; // lead lost, the new leader continues
        }
        batch.export();
        lease.verify(); // throws if lead was lost during the batch
    }
});
```

Notes:
- `close()` releases the lead immediately: an instance which starts its job after that (scheduler delay, clock difference) becomes the leader and runs the job again. The job must therefore recognise its done work, e.g. by the date of the last run stored in its own table, in the same transaction as the work.
- `build()` and `elect()` access the database: if it is not reachable, the job fails with a `LeaderElectionException` for this run only, the service keeps running.
- Use an own election name for the job; it is not shared with the leader electors built at startup.

### LeaderElectionInformation

`LeaderElectionInformation` identifies the election group and this node within it.

| Constructor | When to use |
|---|---|
| `new LeaderElectionInformation("namespace", "name", "identity")` | Kubernetes strategy (required) or multi-group deployments |
| `new LeaderElectionInformation("groupName")` | FILE, NETWORK, and DATABASE strategies; the value becomes the group name, the identity is resolved automatically |

When no identity is provided (null or blank), it is resolved automatically: the `HOSTNAME` environment variable (the pod name in Kubernetes), the local hostname, or a random UUID as last resort. Each elector gets a unique identity even when multiple electors run in the same JVM.

For the DATABASE strategy the election group key is `"namespace.name"` (without the identity), so all nodes sharing the same namespace+name form one election group. The identity is stored in the `instance` column, so the database record shows a meaningful hostname instead of an internal UUID.

#### Field length limits

All string fields are validated against the database column sizes so that values that would be silently truncated or rejected by the database are caught early with a clear error message. The limits apply to every strategy, not only DATABASE.

| Field | Max length | Database column |
|---|---|---|
| `namespace` + `name` (combined as `"namespace.name"`) | 512 characters | `name VARCHAR(512)` |
| `identity` | 255 characters | `instance VARCHAR(255)` |
| `username` | 255 characters | `username VARCHAR(255)` |

Values that exceed these limits cause an `IllegalArgumentException` at construction time or when the setter is called.

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
// elect() must be called first; runIfLeader does not wait for an election
elector.elect();

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
| `tryExtend()` | Attempts to push the lease deadline further out. Returns `true` on success. For FILE and DATABASE strategies this performs an actual renewal (DATABASE skips the write while the lease was renewed less than half a retry period ago, so calling it often costs no database load); for NETWORK and KUBERNETES it is a no-op that returns `isLeader()`. |
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
| `DATABASE` | The first election tick runs immediately on a shared worker thread. `elect()` blocks until that tick completes and returns `isLeader()`. `elect(Duration)` returns `false` if the timeout elapses before the first tick finishes; use a timeout of at least one `retryPeriod` (or a few seconds) to give the tick time to complete, especially against remote databases or pools with an acquisition timeout. |
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

The lock file is `<basePath>/<namespace>.<name>.lock`. Without a base path the temporary directory is used and an info message is logged: all competing processes must see the same directory, which is not the case for services with their own temporary directory (systemd `PrivateTmp`, containers, the per-user `TEMP` on Windows). Characters other than letters, digits, `.`, `-` and `_` are replaced by `_`, so the lock file is always created directly in the base path; in that case a short hash of the original name is appended (`<namespace>.<name>-<hash>.lock`), so different names never share a lock file. A lock held by another process is tried again once per retry period. The holder updates the modification time of the lock file, so cleaners of temporary directories keep it; a lock file that is deleted or replaced anyway is detected on the next renewal, and the node competes again for the new file.

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

The coordinator of the JGroups cluster view is the leader. During a network partition each side forms its own view, so by default each side has its own leader until the partitions merge again. To prevent this, set `minimumMembers` to a majority of the expected cluster size (`n / 2 + 1`): a node only leads while its view contains at least that many members, so a minority partition has no leader.

```java
// 3-node cluster: a node only leads while it sees at least 2 members
ILeaderElector leaderElector = LeaderElectionFactory.builder()
    .namespace("namespace").name("my-service").identity("node-1")
    .network().minimumMembers(2).build();
```

In addition, the coordinator sends a random challenge to the cluster once per retry period, and every member that sees it as the coordinator of its own view answers it. The coordinator only confirms its lead while it gets answers to challenges it sent within the renew deadline: from at least one other member, or from `minimumMembers - 1` members with a quorum. `isLeader()` returns `false` when the lead was not confirmed within the renew deadline, e.g. when a node comes back from a long JVM pause with an outdated view while the others chose a new coordinator; answers it buffered or received for older challenges do not count.

**Security:** by default the cluster is neither authenticated nor encrypted, so any host on the network can join it and take the lead; an info message is logged in this case. Set a shared encryption key: all cluster messages are then encrypted, including the messages of discovery and failure detection, so a node without the key can neither read nor forge them, nor join the cluster. Readable remain the cluster name, the sender and receiver addresses, the type of membership messages (join, leave, view, merge) and the TCP connections that detect crashed members; choose a cluster name that reveals nothing, and keep the cluster in a private network. JGroups logs every packet it rejects (e.g. from a node without the key); to keep foreign packets from flooding the log, set the loggers `org.jgroups.protocols.SYM_ENCRYPT`, `org.jgroups.protocols.Encrypt` and `org.jgroups.protocols.SERIALIZE` to `ERROR` or `OFF` in the logging configuration. Each message is encrypted with a random IV, and a recorded answer to an older challenge does not count. All nodes need the same key and the same library version.

The encryption does not shield the nodes from the network itself: JGroups reads every packet that reaches the cluster's ports before it is decrypted, including Java deserialization of object messages, so a crafted packet from any host can at least crash a node. Run the NETWORK strategy only in a trusted network segment (restrict the multicast group and the member ports with a firewall), and set a JVM-wide deserialization filter for the application, e.g. `-Djdk.serialFilter=...` with an allow-list of the classes the application needs.

```bash
# create the shared key once and distribute the file to all nodes
keytool -genseckey -alias leader-election -keyalg AES -keysize 256 -storetype PKCS12 -keystore leader-election.p12 -storepass <password>
```

```java
char[] password = ...; // e.g. from a secret store
ILeaderElector leaderElector = LeaderElectionFactory.builder()
    .namespace("namespace").name("my-service").identity("node-1")
    .network().encryptionKey("/etc/my-service/leader-election.p12", password, "leader-election").build();
Arrays.fill(password, ' '); // the password is no longer needed
```

A `javax.crypto.SecretKey` can also be passed directly, e.g. from a secret store: `.network().encryptionKey(secretKey)`. Only AES keys of 128, 192 or 256 bits are accepted, created randomly (`keytool` as above, or `KeyGenerator.getInstance("AES")`), never derived from a password: JGroups sends a hash of the key with every message, so a weak key could be found from it.

For full control, `.network().configuration("my-jgroups.xml")` replaces the default JGroups protocol stack (a file, classpath resource or URL), e.g. to use TCP instead of UDP multicast, bind to a specific network interface or add other JGroups security protocols. An encryption key can be combined with a custom configuration, as long as the configuration has no encryption protocol of its own.

### KUBERNETES strategy

```java
ILeaderElector leaderElector = LeaderElectionFactory.builder()
    .namespace("my-namespace").name("my-service").identity(System.getenv("HOSTNAME"))
    .kubernetes().build();

leaderElector.elect();
```

The lock is a Lease named after `name` in the Kubernetes namespace `namespace`. `isLeader()` returns `false` when the lease was not renewed within the renew deadline, even while the Kubernetes client still reports the lead. The service account of the pods needs these permissions. `get` and `update` are limited to the own Lease, so a compromised pod cannot take over the Leases of other components in the namespace; `create` cannot be limited by name in Kubernetes:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: leader-election
  namespace: my-namespace
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    resourceNames: ["my-service"]
    verbs: ["get", "update"]
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["create"]
```

**Upgrading from 1.2.x:** releases up to 1.2.x lock an Endpoints object instead. Nodes with different lock types do not see each other's lock, so during a rolling upgrade an old and a new node could both lead. Either stop all old pods before the new ones start (Deployment strategy `Recreate`), or upgrade in two steps:

1. Deploy 1.3.0 with `.kubernetes().lockType(KubernetesLockType.ENDPOINTS)`; the existing RBAC rules for `endpoints` still apply.
2. Once all pods run 1.3.0, add the `leases` rule above, remove the `lockType` line and deploy again. The Endpoints permissions can then be removed.

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

The library creates and manages the required `LeaderElection` table automatically. Schema setup runs at `build()` / `getLeaderElection()` time, before `elect()` is called, so missing tables and failed migrations surface immediately. On first use it inspects the table schema and, if a v1.0.0 installation is detected (column named `timestamp`), renames it to `lease_ts` automatically. On new installations a secondary index on the `instance` column is also created; existing installations can add it manually with `CREATE INDEX idx_leaderelection_instance ON LeaderElection(instance)`.

> **Externally managed schemas (Flyway, Liquibase, no DDL rights)**
> If the database user has no DDL privileges, or the table is managed by a migration tool, set `allowDdl=false` on the configuration. The library will then fail fast with a descriptive error at build time, including the exact DDL to apply, instead of attempting `CREATE TABLE` or `ALTER TABLE`:
>
> ```java
> config.setAllowDdl(false);  // disable runtime DDL; build() throws if the table or column is missing
> ```
>
> Expected columns: `id VARCHAR(36)`, `name VARCHAR(512)`, `instance VARCHAR(255)`, `username VARCHAR(255)`, `lease_ts BIGINT NOT NULL`, `force_release_deadline BIGINT NULL` (primary key on `name`).

> **Connection pool requirement**
> Each election tick borrows one connection from the `DataSource`. Ensure the pool is configured with a **connection-acquisition timeout** (e.g. HikariCP `connectionTimeout`, Agroal `acquisition-timeout`). An unbounded wait blocks the elector's election tick indefinitely, delaying step-down detection.
> The minimum recommended `renewDeadline` is **3 seconds**: each tick issues up to 3 sequential statements, each with a 1-second minimum JDBC timeout; at shorter values the total per-tick latency can exceed the renew deadline.

For databases with higher latency (e.g., Oracle), use the explicit `Duration` constructor to control `timeout`, `renewDeadline`, and `retryPeriod` independently:

```java
DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
    Duration.ofSeconds(30),
    Duration.ofSeconds(20),
    Duration.ofSeconds(5));
```

If the database becomes temporarily unavailable, the DATABASE strategy applies exponential back-off (capped at 60 s) so election ticks do not hammer a down database.

#### Database-specific SQL structures

By default the library uses `JDBCLeaderElectionDatabaseStructure`, which is compatible with PostgreSQL, H2, and most ANSI-SQL databases. For databases that need dialect-specific DDL, supply the matching structure class:

| Database | Structure class |
|---|---|
| Generic / H2 | `JDBCLeaderElectionDatabaseStructure` *(default)* |
| PostgreSQL | `PostgreSQLLeaderElectionDatabaseStructure` |
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

The table name can be set directly on the builder or the configuration without having to create a database structure manually. The dialect is still auto-detected:

```java
// Via builder (recommended)
ILeaderElector elector = LeaderElectionFactory.builder()
    .namespace("ns").name("app").identity("node-1")
    .database()
        .dataSource(ds)
        .tableName("myschema.MyElectionTable")
        .build();

// Or directly on the configuration
DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(10);
config.setDataSource(ds);
config.setTableName("myschema.MyElectionTable");
```

All structure classes validate and reject SQL-unsafe names. Each structure enforces the identifier length limit of its target database:

| Structure | Max table name length |
|---|---|
| `JDBCLeaderElectionDatabaseStructure` (generic/H2) | 128 |
| `PostgreSQLLeaderElectionDatabaseStructure` | 63 |
| `MySQLLeaderElectionDatabaseStructure` / `MariaDBLeaderElectionDatabaseStructure` | 64 |
| `OracleLeaderElectionDatabaseStructure` | 128 |
| `SQLServerLeaderElectionDatabaseStructure` | 128 |

For full control over the SQL dialect, supply a structure object directly:

```java
JDBCLeaderElectionDatabaseStructure structure = new JDBCLeaderElectionDatabaseStructure();
structure.setTableName("myschema.LeaderElection"); // schema-qualified names are supported
config.setLeaderElectionDatabaseStructure(structure);
```


## Configuration

`LeaderElectionConfiguration` controls the timing parameters:

| Parameter | Default | Description |
|---|---|---|
| `timeout` | 15 s | How long a lease is valid. Another node can take over after this elapses. |
| `renewDeadline` | 10 s | The leader must renew its lease within this duration. Must be < `timeout`, with a gap of at least min(1 s, `timeout` / 4). |
| `retryPeriod` | 5 s | How often candidates attempt to acquire or renew the lease. Must be < `renewDeadline`. |
| `closeTimeout` | 5 s | Maximum time `close()` waits for running leader tasks. Tasks that still run afterwards keep the lock: it is released once they ended, so no other node takes the lead meanwhile. |

The three timing parameters form a chain: `retryPeriod < renewDeadline < timeout`.

```
|<----- timeout (15 s) ------------------------------------------>|
|<----- renewDeadline (10 s) ----------------------->|  (gap: 5 s) |
|<-- retryPeriod (5 s) -->|<-- retryPeriod (5 s) -->|             |
      renew tick               renew tick        step-down     takeover
```

- **retryPeriod**: how often the leader renews its lease and candidates check whether the lease expired. Shorter values detect a crashed leader faster but increase database load.
- **renewDeadline**: if the leader cannot renew within this time (e.g. the database is slow or the JVM paused), it steps down. The gap between renewDeadline and timeout (`timeout - renewDeadline`) is the safety margin: it prevents two leaders by ensuring the old leader steps down before the new one can take over.
- **timeout**: after this time without a renewal another node takes the lease. Set it large enough that a healthy leader always renews in time, even under load.

Rule of thumb: `timeout >= 2 * retryPeriod`, `renewDeadline >= 2 * retryPeriod`, and leave at least 1 s (or `timeout / 4`) between renewDeadline and timeout. The builder derives these automatically when only `timeout()` is set.

The convenience constructor `new LeaderElectionConfiguration(timeoutInSeconds)` computes `renewDeadline` and `retryPeriod` automatically from the timeout value. The no-arg constructor defaults to (15 s / 10 s / 5 s).

```java
// Override close timeout if your shutdown budget is tight
config.setCloseTimeout(Duration.ofSeconds(2));
```


### Threads

All electors of a JVM share a few daemon threads: one timer, one lease monitor, and a worker pool that runs election rounds and lease extensions. Workers are only created while rounds run and end after 60 s idle, so the thread count does not grow with the number of electors, and a slow elector does not delay the others. The rounds of electors started at the same time are spread over the retry period, so they do not borrow database connections all at once. A round that hangs, e.g. in a connection pool without a connection timeout, is reported as a warning; configure the pool's connection timeout below the renew deadline. While a worker runs a round it carries the elector's name, e.g. `leader-election-db-<namespace>.<name>`. The NETWORK strategy additionally runs one JGroups stack per elector, and the KUBERNETES strategy an update thread and the threads of the client's leader election per elector, so keep the number of NETWORK and KUBERNETES electors per JVM small. KUBERNETES electors without an own API client share one default client.


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

For a **local** elector `forceRelease` calls `close()`; the elector stops and relinquishes its lock or database row. For a **remote DATABASE** leader a release deadline (now + renew deadline) is written to its row: the leader steps down on its next round, and after the deadline another node takes over. The released node stays away from the lead for one more timeout, so it does not win the lead back; only if no other node takes over in that time, it takes the lead back.

`forceRelease(null)`, `forceRelease("")`, and `forceRelease("unknown-id")` return `false` without throwing.


## Testing

### Unit tests

```bash
cb test
```

Runs all unit tests. No Docker required.

A single test class or method runs with `--tests`:

```bash
cb test --tests "*FileLockSafetyTest"
cb test --tests "*FileLockSafetyTest.testSymbolicLinkIsRefused"
```

The results are in `build/reports/tests/test/index.html`, the log in `build/toolarium-leader-election.log`.

### Symbolic link test (FILE strategy)

`FileLockSafetyTest.testSymbolicLinkIsRefused` checks that the FILE strategy refuses a lock file that was replaced by a symbolic link, so a link to another file (e.g. a configuration file) can never be opened or truncated through the lock. The test creates such a link and expects the lock to fail with the target untouched.

Creating a symbolic link needs a privilege that Windows does not grant by default. Where the link cannot be created, the test is reported as skipped, not failed. To run it:

- **Linux or macOS**: runs without any setup.
- **Windows**: enable *Developer Mode* (Settings, System, For developers) and open a new terminal, or run the build as administrator.
- **WSL**: runs without any setup. Use a copy of the project on the Linux file system, which also builds much faster than `/mnt/c`. WSL needs its own JDK and `cb`:

```bash
mkdir -p ~/projects
# the working tree including uncommitted changes; use git clone for the pushed state
rsync -a --exclude build --exclude .gradle /mnt/c/<path>/toolarium-leader-election/ ~/projects/toolarium-leader-election/
cd ~/projects/toolarium-leader-election
cb test --tests "*FileLockSafetyTest.testSymbolicLinkIsRefused"
```

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
| `KubernetesLeaderElectionTest` | k3s (lightweight Kubernetes), Lease lock |
| `KubernetesEndpointsLeaderElectionTest` | k3s (lightweight Kubernetes), Endpoints lock |

Each integration test starts its own container via [Testcontainers](https://testcontainers.com/), runs the full leader-election lifecycle against a real database or Kubernetes cluster, then tears the container down. Oracle has a longer cold-start time; allow several minutes for that container to become ready.

A single integration test runs with `--tests` as well, e.g. against PostgreSQL only:

```bash
cb test -Pintegration --tests "*PostgreSQLLeaderElectionTest"
```

The containers need several GB of memory together; if the run is aborted or containers fail to start, run the integration tests one at a time. The test results are in the same report as the unit tests.


## Built With

* [cb](https://github.com/toolarium/common-build), the toolarium common build

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository](https://github.com/toolarium/toolarium-leader-election/tags).
