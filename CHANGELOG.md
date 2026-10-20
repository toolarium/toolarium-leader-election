# toolarium-leader-election

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [ 1.2.0 ] - 2026-10-20
### Added
- **Administration API** — `ILeaderElectionAdministration`: obtain a live snapshot of all electors currently holding leadership via `getElectors()` and force-release a specific leader lease via `forceRelease(String id)`. Access the singleton via `LeaderElectionFactory.getInstance().getLeaderElectionAdministration()`.
- **`ILeaderElectorView`** — read-only view of an active leader: unique id, election group information, configuration, and the instant leadership was last confirmed. Returned by `getElectors()` and implemented by all elector instances.
- **DATABASE cross-node visibility** — `getElectors()` returns leaders held by all nodes in the cluster, not only those running in this JVM; records are read directly from the shared database table. `forceRelease()` on a remote DATABASE leader deletes its row so another node can take over without waiting for the lease timeout.
- `runIfLeader(String, ILeaderTask)` and `runIfLeader(String, Runnable)`: execute work on the calling thread only when this node holds leadership. The task is interrupted automatically when leadership is lost.
- `ILeaderLease` interface: gives a running task read-only access to leadership state (`isValid()`, `verify()`), elector identity, configuration, and lease timing (`getLeaderSince()`, `getRemainingLeadTime()`, `isExpiringSoon(Duration)`).
- `MockKubernetesLeaderElector.builder()`: fluent builder for constructing mock Kubernetes electors in tests, consistent with `LeaderElectionFactory.builder()`.

### Changed
- **Breaking API change**: `void initialize()` replaced by `boolean elect()` and `boolean elect(Duration timeout)`. `elect()` blocks until the first result is known and returns `isLeader()` directly; calling it again returns the current state immediately.
- Strategy selection is now always explicit — `LeaderElectionFactory` requires a strategy and will throw if none is provided. The previous auto-detection fallback (Kubernetes → Network) has been removed.
- DATABASE strategy: dialect-specific SQL structure is auto-detected from the JDBC connection on first use when none is configured explicitly.
- README: updated all usage examples and the API reference table.

## [ 1.1.0 ] - 2026-09-04
### Added
- DATABASE strategy: exponential back-off on consecutive connection failures (capped at 60 s) to avoid hammering an unavailable database.
- Automatic schema migration on startup: the legacy `timestamp` column is renamed to `lease_ts` for existing v1.0.0 installations.
- Extended test coverage: scheduler register/unregister, DAO steal-leadership, database clock accuracy, schema migration, and back-off behaviour.

### Changed
- DATABASE strategy: lease timestamp stored as epoch milliseconds (`bigint`) instead of a formatted string, removing timezone and locale sensitivity.
- DATABASE strategy: leadership stealing uses a single atomic conditional UPDATE, eliminating the previous read-then-write race condition.
- DATABASE strategy: the election group key is derived from the election name only (not the node identity), so all nodes with the same name form one election group.
- NETWORK strategy: initial leadership is now determined synchronously during `initialize()` rather than waiting for the first scheduler tick.
- KUBERNETES strategy: the API client is now scoped to the elector instance; the JVM-global default client is no longer mutated.
- `LeaderElectionScheduler`: simplified to a plain registry; removed the thread-death auto-close hook that interfered with framework-managed lifecycle threads.
- `LeaderElectionConfiguration`: validation corrected — `renewDeadline` must be strictly less than `timeout`, and `retryPeriod` must be ≤ `renewDeadline`.
- README: documented database-specific SQL structure classes, `LeaderElectionInformation` constructors, full `LeaderElector` API, configuration defaults, custom table name, and back-off behaviour.

## [ 1.0.0 ] - 2026-08-18
### Added
- `LeaderElectionStrategy` enum with four strategies: `FILE`, `NETWORK`, `KUBERNETES`, `DATABASE`.
- `FILE` strategy: OS-level file lock on a shared filesystem path (`FileLeaderElectorImpl`, `FileLock`, `FileLockFactory`). No extra dependency required.
- `NETWORK` strategy: JGroups-based cluster membership leader election (`NetworkLeaderElectorImpl`). Requires `org.jgroups:jgroups`.
- `DATABASE` strategy: JDBC-based optimistic locking against a shared `LeaderElection` table (`DatabaseLeaderElectorImpl`, `LeaderElectionDAO`). Requires only a JDBC driver; all SQL uses standard `java.sql.*`.
- Database-specific SQL structure classes: `JDBCLeaderElectionDatabaseStructure`, `MariaDBLeaderElectionDatabaseStructure`, `MySQLLeaderElectionDatabaseStructure`, `OracleLeaderElectionDatabaseStructure`, `SQLServerLeaderElectionDatabaseStructure`.
- `DatabaseLeaderElectionConfiguration` with support for both convenience (`timeoutInSeconds`) and explicit (`Duration timeout, renewDeadline, retryPeriod`) constructors.
- `FileLeaderElectionConfiguration` with configurable base path (defaults to `java.io.tmpdir`).
- `LeaderElectionScheduler` for periodic lease renewal and retry scheduling.
- `LeaderElectionException` as a dedicated checked exception for initialization and runtime errors.
- `H2LeaderElectionDatabaseConnectionHandler` for embedded H2 database support.
- `JDBCLeaderElectionDatabaseConnectionHandler` for standard JDBC URL/credentials setup.
- Automatic JVM shutdown hook registration for all `LeaderElector` instances created via `LeaderElectionFactory`.
- Auto-detection logic in `LeaderElectionFactory`: selects `KUBERNETES` when a Kubernetes environment is detected, otherwise falls back to `NETWORK`.
- Integration tests for PostgreSQL, MySQL, MariaDB, Oracle, and SQL Server via Testcontainers (activated with `-Pintegration`).
- Integration test for Kubernetes via Testcontainers k3s (`KubernetesLeaderElectionTest`): runs against a real k3s cluster with no mocking.
- Unit tests for FILE, DATABASE, KUBERNETES, and NETWORK strategies.

## [ 0.8.0 ] - 2021-12-23
### Changed
- Setup initial version.
