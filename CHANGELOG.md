# toolarium-leader-election

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [ 1.3.0 ] - 2026-10-24
### Added
- **DATABASE**: `forceRelease()` on a remote leader now prevents split-brain overlap; no other node can acquire leadership until the force-release deadline elapses, ensuring a clean handover.
- **DATABASE**: automatic schema migration adds the `force_release_deadline` column to existing installations on startup.
- **DATABASE**: `DatabaseLeaderElectionConfiguration.setAllowDdl(false)` disables DDL execution (`CREATE TABLE` / `ALTER TABLE`) at startup. When DDL is required and disabled, `elect()` throws a descriptive error with the exact SQL to apply manually. Default is `true`.
- **DATABASE**: a node that stepped down due to a renew-deadline timeout now automatically re-acquires leadership once its own lease expires, without requiring a restart.

### Fixed
- **DATABASE**: leadership can no longer be held beyond the configured renew deadline when the scheduler thread is delayed or blocked.
- **DATABASE**: `runIfLeader` tasks are interrupted reliably when leadership is lost, with no spurious interrupt leaks to the calling thread.
- **ALL**: `ILeaderLease.isValid()` correctly returns `false` for leases from a previous leadership term after a lose-and-regain cycle.
- **DATABASE**: `close()` now correctly interrupts running `runIfLeader` tasks even when the close budget is nearly exhausted.
- **DATABASE**: a scheduler tick already in flight when `close()` is called can no longer re-establish leadership after `close()` has invalidated it.
- **ALL**: Java 8 source/target compatibility restored.
- **FILE**: scheduler fields and channel reference are now correctly visible across threads, preventing resource leaks on `close()`.
- **FILE**: interrupt flag is preserved when the file-lock scheduler shuts down.
- **DATABASE**: lease renewal is committed correctly when the data source uses non-auto-commit mode.
- **DATABASE**: `ILeaderLease.tryExtend()` is now safe to call from within a JTA-managed transaction.
- **DATABASE**: `forceRelease()` returns `false` for blank ids instead of throwing an undeclared exception.
- **DATABASE**: `CREATE TABLE`, `CREATE INDEX`, and `ALTER TABLE` DDL statements are now committed explicitly so schema changes are visible to other nodes when the connection pool uses non-auto-commit mode (affects PostgreSQL pools configured with `autoCommit=false`).
- **DATABASE**: concurrent callers of the internal DB-clock sync (e.g. scheduler thread and a `runIfLeader` caller) no longer each issue a redundant DB round-trip when a refresh is due.

## [ 1.2.1 ] - 2026-10-22
### Added
- **DATABASE**: a secondary index on the `instance` column is created automatically alongside the table, keeping `forceRelease()` efficient regardless of table size. Can be added manually to existing installations with `CREATE INDEX idx_leaderelection_instance ON LeaderElection(instance)`.

### Fixed
- **DATABASE**: node now correctly steps down when the renew deadline passes before a renewal completes.
- **DATABASE**: `ILeaderLease.isValid()` correctly returns `false` for a lease from a prior leadership term after a lose-and-regain cycle.
- **DATABASE**: `close()` stops any running `runIfLeader` task before releasing the database row.
- **DATABASE**: non-auto-commit data sources are now supported for scheduler-thread renewals.
- **DATABASE**: `close()` is idempotent; calling it more than once no longer causes errors.
- **DATABASE**: scheduler threads are daemon threads, preventing JVM hang on shutdown when `close()` is not called.
- **DATABASE**: elector can be reused after a `close()` + `elect()` cycle without restarting the application.
- **NETWORK**: `elect()` now reliably returns the correct result when stale cluster members from a prior JVM run appear in the initial membership view.
- **ALL**: `elect(null)` now correctly delegates to `elect()` instead of throwing `NullPointerException`.

## [ 1.2.0 ] - 2026-10-20
### Added
- **Administration API**: `ILeaderElectionAdministration`: snapshot all electors currently holding leadership via `getElectors()` and force-release a specific lease via `forceRelease(String id)`. Available via `LeaderElectionFactory.getInstance().getLeaderElectionAdministration()`.
- **`ILeaderElectorView`**: read-only view of an active leader: id, election group, configuration, and last confirmed timestamp.
- **DATABASE cross-node visibility**: `getElectors()` returns leaders from all nodes in the cluster, not only the local JVM. `forceRelease()` on a remote DATABASE leader takes effect immediately without waiting for the lease timeout.
- `runIfLeader(String, ILeaderTask)` and `runIfLeader(String, Runnable)`: execute work on the calling thread only when this node holds leadership; interrupted automatically when leadership is lost.
- `ILeaderLease` interface: read-only access to leadership state (`isValid()`, `verify()`), elector identity, configuration, and lease timing (`getLeaderSince()`, `getRemainingLeadTime()`, `isExpiringSoon(Duration)`).
- `MockKubernetesLeaderElector.builder()`: fluent builder for mock Kubernetes electors in tests.

### Changed
- **Breaking API change**: `void initialize()` replaced by `boolean elect()` and `boolean elect(Duration timeout)`. `elect()` blocks until the first result is known and returns `isLeader()` directly.
- Strategy selection is now always explicit; `LeaderElectionFactory` requires a strategy; the previous auto-detection fallback (Kubernetes → Network) has been removed.
- DATABASE strategy: SQL dialect is auto-detected from the JDBC connection when not configured explicitly.
- README: updated all usage examples and the API reference table.

## [ 1.1.0 ] - 2026-09-04
### Added
- DATABASE strategy: exponential back-off on consecutive connection failures (capped at 60 s).
- Automatic schema migration on startup: the legacy `timestamp` column is renamed to `lease_ts` for existing v1.0.0 installations.

### Changed
- DATABASE strategy: lease timestamp stored as epoch milliseconds instead of a formatted string.
- DATABASE strategy: leadership stealing uses a single atomic conditional UPDATE.
- DATABASE strategy: the election group key is derived from the election name only, so all nodes with the same name form one group.
- NETWORK strategy: initial leadership is determined synchronously during `initialize()`.
- KUBERNETES strategy: the API client is scoped to the elector instance; the JVM-global default client is no longer mutated.
- `LeaderElectionConfiguration`: validation corrected; `renewDeadline` must be strictly less than `timeout`, and `retryPeriod` must be ≤ `renewDeadline`.
- README: updated API reference, configuration defaults, and back-off behaviour.

## [ 1.0.0 ] - 2026-08-18
### Added
- Four leader election strategies: `FILE` (OS file lock), `NETWORK` (JGroups cluster), `KUBERNETES` (Endpoints lock), `DATABASE` (JDBC optimistic locking).
- Dialect-specific SQL structure classes for JDBC, MariaDB, MySQL, Oracle, and SQL Server.
- `DatabaseLeaderElectionConfiguration` and `FileLeaderElectionConfiguration` with both convenience and explicit constructors.
- `LeaderElectionException` as a dedicated checked exception for initialization and runtime errors.
- H2 and standard JDBC connection handler implementations for the DATABASE strategy.
- Automatic JVM shutdown hook registration for all `LeaderElector` instances created via `LeaderElectionFactory`.
- Auto-detection in `LeaderElectionFactory`: selects `KUBERNETES` when a Kubernetes environment is detected, otherwise falls back to `NETWORK`.
- Integration tests for PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, and Kubernetes (k3s) via Testcontainers.

## [ 0.8.0 ] - 2021-12-23
### Changed
- Setup initial version.
