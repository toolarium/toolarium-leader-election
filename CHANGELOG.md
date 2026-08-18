# toolarium-leader-election

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 1.0.0 - 2026-08-18
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

## 0.8.0 - 2021-12-23
### Changed
- Setup initial version.
