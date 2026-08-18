[![License](https://img.shields.io/github/license/toolarium/toolarium-leader-election)](https://github.com/toolarium/toolarium-leader-election/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.toolarium/toolarium-leader-election/1.0.0)](https://search.maven.org/artifact/com.github.toolarium/toolarium-leader-election/1.0.0/jar)
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

When no strategy is specified, the factory auto-detects: it uses **KUBERNETES** if a Kubernetes environment is detected, otherwise it falls back to **NETWORK**.


## Adding the library

**Gradle**
```gradle
implementation 'com.github.toolarium:toolarium-leader-election:1.0.0'
```

**Maven**
```xml
<dependency>
    <groupId>com.github.toolarium</groupId>
    <artifactId>toolarium-leader-election</artifactId>
    <version>1.0.0</version>
</dependency>
```

The library only brings `slf4j-api` as a transitive dependency. You must provide an SLF4J binding (e.g., Logback) at runtime.


## Strategy-specific dependencies

### FILE — no extra dependency

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

### Auto-detection (Kubernetes if available, otherwise Network)

```java
LeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    new LeaderElectionConfiguration(10 /* timeout in seconds */));

leaderElector.initialize();

if (leaderElector.isLeader()) {
    // this node is the elected leader
}

// release on shutdown (also registered as a JVM shutdown hook automatically)
leaderElector.close();
```

### Explicit strategy selection

```java
LeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.FILE,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    new LeaderElectionConfiguration(10));
```

### FILE strategy with custom path

```java
FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration(
    "/shared/nfs/locks",
    Duration.ofSeconds(10),
    Duration.ofSeconds(7),
    Duration.ofSeconds(3));

LeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.FILE,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    config);
```

### NETWORK strategy (JGroups)

```java
LeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.NETWORK,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    new LeaderElectionConfiguration(10));

leaderElector.initialize();
```

### DATABASE strategy

Pass any standard `javax.sql.DataSource` — a connection pool like HikariCP is recommended for production:

```java
// example with PostgreSQL driver's built-in DataSource
PGSimpleDataSource ds = new PGSimpleDataSource();
ds.setUrl("jdbc:postgresql://db-host:5432/mydb");
ds.setUser("user");
ds.setPassword("pass");

DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(10);
config.setDataSource(ds);

LeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    LeaderElectionStrategy.DATABASE,
    new LeaderElectionInformation("namespace", "my-service", "node-1"),
    config);

leaderElector.initialize();
```

The library creates and manages the required `LeaderElection` table automatically.

For databases with higher latency (e.g., Oracle), use the explicit `Duration` constructor to control `timeout`, `renewDeadline`, and `retryPeriod` independently:

```java
DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
    Duration.ofSeconds(30),
    Duration.ofSeconds(20),
    Duration.ofSeconds(5));
```


## Configuration

`LeaderElectionConfiguration` controls three timing parameters:

| Parameter | Description |
|---|---|
| `timeout` | How long a lease is valid. Another node can take over after this elapses. |
| `renewDeadline` | The leader must renew its lease within this duration. Must be < `timeout`. |
| `retryPeriod` | How often candidates attempt to acquire or renew the lease. Must be <= `renewDeadline`. |

The convenience constructor `new LeaderElectionConfiguration(timeoutInSeconds)` computes `renewDeadline` and `retryPeriod` automatically.


## Built With

* [cb](https://github.com/toolarium/common-build) - The toolarium common build

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository].
