# toolarium-leader-election

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [ 1.3.2 ] - 2026-10-29

## [ 1.3.1 ] - 2026-10-29
### Added
- **ALL**: maximum length validation for namespace, name, identity, username, and table name fields.
- **ALL**: default identity resolved from hostname (`HOSTNAME` env var, then local hostname) instead of a random UUID.
- **DATABASE**: `tableName()` on the builder and `setTableName()` on `DatabaseLeaderElectionConfiguration` set the table name without giving up auto-detection of the database dialect.
- **DATABASE**: `PostgreSQLLeaderElectionDatabaseStructure` for PostgreSQL; auto-detected from the JDBC product name.
- **DATABASE**: the `instance` column stores the elector identity instead of an internal UUID, so the database record shows a meaningful hostname.

### Changed
- **ALL**: improved validation error messages now include the actual and expected values.

## [ 1.3.0 ] - 2026-10-24
### Added
- **DATABASE**: `setAllowDdl(false)` disables automatic table creation and migration; existing tables are migrated automatically otherwise.
- **NETWORK**: `minimumMembers()` sets a quorum, so the minority side of a network partition has no leader.
- **NETWORK**: `encryptionKey()` encrypts the cluster messages with a shared AES key (128 to 256 bits); `configuration()` sets a custom JGroups configuration. Without either, an info message is logged.
- **KUBERNETES**: `lockType()` selects the lock; the default is a Lease, `ENDPOINTS` keeps the lock of 1.2.x for a rolling upgrade.

### Changed
- **ALL**: Java 11 or later is required.
- **ALL**: new timing defaults (timeout 15 s, renew deadline 10 s, retry period 5 s) and stricter validation of the timings.
- **ALL**: electors share a few threads, and their rounds are spread over the retry period.
- **ALL**: leader tasks that still run after the close timeout keep the lock until they end.
- **DATABASE**: schema errors are reported when the elector is built; the schema is checked once per table.
- **DATABASE**: SQL Server 2016 or later is required.
- **FILE**: lock files are kept after `close()`, are never truncated, and symbolic links are refused. Special characters in the namespace and name are replaced in the file name.
- **KUBERNETES**: the default lock is a Lease; the service account needs RBAC access to `leases`.
- **NETWORK**: the coordinator only keeps the lead while the other members confirm it; the keystore password is passed as `char[]`.

### Fixed
- **ALL**: no more than one leader during handover, close and re-election in all strategies; a node stops reporting the lead once it was not confirmed within the renew deadline.
- **ALL**: a closed elector never reports the lead, `elect()` and `close()` no longer block or leak threads, locks or tasks, and an elector can be elected again after `close()`.
- **ALL**: leader tasks are interrupted reliably when the lead is lost, leases of an earlier term are invalid, and a named task never runs twice at the same time.
- **ALL**: the shutdown hook also closes electors that were elected again after `close()`.
- **ALL**: the administration views are read-only snapshots.
- **DATABASE**: a healthy leader keeps the lead with the default timings, lease expiry no longer depends on the JVM time zone, and a slow or unreachable database no longer blocks `elect()` or keeps a node leading.
- **DATABASE**: `forceRelease()` hands the lead to another node without overlap.
- **DATABASE**: schema-qualified table names and table names with `_` work on all databases.
- **DATABASE**: a leader row with a time far in the future (e.g. a wrong clock) no longer blocks the election; a warning names the instance.
- **DATABASE**: a clock step on a node (NTP, a resumed VM) no longer shifts its lease times.
- **DATABASE**: works with non-auto-commit data sources and inside JTA transactions; `tryExtend()` returns `false` instead of throwing or waiting, and does not write to the database while the lease is fresh.
- **DATABASE**: the administration view lists every leader once with its own election name.
- **FILE**: long-running tasks are no longer interrupted by the lock renewal, the JVM can exit, and different names no longer share a lock file.
- **FILE**: a lock held by another process is tried again once per retry period instead of every second.
- **KUBERNETES**: `elect()` returns on a follower and while the API server is unreachable, the lock is released on `close()`, `close()` respects the close timeout, and the node keeps competing after an unexpected error.
- **NETWORK**: joining no longer fails on Windows hosts with reserved port ranges, `elect()` respects its timeout, and a node with an outdated cluster view no longer keeps the lead.
- **NETWORK**: with an encryption key, all messages including their headers are encrypted; the lead is confirmed by answers to fresh challenges, so a paused or replayed node cannot keep it.

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
