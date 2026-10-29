/*
 * LeaderElectionFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */

package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.file.FileLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLockType;
import com.github.toolarium.leader.election.dto.network.NetworkLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.LeaderElectionScheduler;
import com.github.toolarium.leader.election.impl.database.DatabaseLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.file.FileLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.network.NetworkLeaderElectorImpl;
import java.time.Duration;
import java.util.Collection;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Defines the leader election factory.
 *
 * @author patrick
 */
public final class LeaderElectionFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionFactory.class);
    private final ILeaderElectionAdministration leaderElectionAdministration;


    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author Patrick Meier
     */
    private static final class HOLDER {
        static final LeaderElectionFactory INSTANCE = new LeaderElectionFactory();
    }

    /**
     * Constructor
     */
    private LeaderElectionFactory() {
        leaderElectionAdministration = new LeaderElectionAdministration();
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static LeaderElectionFactory getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Returns the administration API for the leader election framework.
     * Use it to list all electors currently holding leadership and to force-release a specific lease.
     *
     * <pre>
     *     ILeaderElectionAdministration admin = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
     *     admin.getElectors().forEach(v -&gt; LOG.info(v.getId() + " " + v.getLeaderElectionInformation()));
     *     admin.forceRelease("some-id");
     * </pre>
     *
     * <p><b>Access control:</b> the returned singleton is shared across the entire JVM.
     * Callers should gate access behind appropriate application-level authorization
     * (e.g. a secured management or actuator endpoint) to prevent unintended
     * force-release of electors owned by other subsystems.
     *
     * @return the singleton {@link ILeaderElectionAdministration}; never {@code null}
     */
    public ILeaderElectionAdministration getLeaderElectionAdministration() {
        return leaderElectionAdministration;
    }


    /**
     * Create a fluent builder for configuring and constructing a leader elector.
     *
     * <p>Usage (step 1 - common identity and timing, step 2 - strategy-specific options):
     * <pre>
     *     // FILE
     *     ILeaderElector e = LeaderElectionFactory.builder()
     *         .namespace("ns").name("app").identity("node-1")
     *         .timeout(Duration.ofSeconds(10))
     *         .file().basePath("/tmp/locks").build();
     *
     *     // DATABASE
     *     ILeaderElector e = LeaderElectionFactory.builder()
     *         .namespace("ns").name("app").identity("node-1")
     *         .database().dataSource(ds).build();
     *
     *     // NETWORK (optional minimumMembers) or KUBERNETES (optional lockType)
     *     ILeaderElector e = LeaderElectionFactory.builder()
     *         .namespace("ns").name("app").identity("node-1")
     *         .network().build();
     * </pre>
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }


    /**
     * Get the leader elector
     *
     * @param leaderElectionStrategy the leader election strategy; must not be {@code null}
     * @param leaderElectionInformation the leader election information
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    public ILeaderElector getLeaderElection(LeaderElectionStrategy leaderElectionStrategy, LeaderElectionInformation leaderElectionInformation) throws LeaderElectionException {
        return getLeaderElection(leaderElectionStrategy, leaderElectionInformation, new LeaderElectionConfiguration());
    }


    /**
     * Get the leader elector
     *
     * @param leaderElectionStrategy the leader election strategy; must not be {@code null}
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    public ILeaderElector getLeaderElection(LeaderElectionStrategy leaderElectionStrategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws LeaderElectionException {
        if (leaderElectionStrategy == null) {
            throw new LeaderElectionException("A leader election strategy must be specified explicitly.");
        }

        return selectLeaderElectorBasedOnStrategy(leaderElectionStrategy, leaderElectionInformation, leaderElectionConfiguration);
    }


    /**
     * Select the leader elector based on the given strategy.
     *
     * @param leaderElectionStrategy the leader election strategy. To choose automated put in null.
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    private ILeaderElector selectLeaderElectorBasedOnStrategy(
            LeaderElectionStrategy leaderElectionStrategy,
            LeaderElectionInformation leaderElectionInformation,
            LeaderElectionConfiguration leaderElectionConfiguration) throws LeaderElectionException {
        ILeaderElector leaderElector = null;
        switch (leaderElectionStrategy) {
            case FILE:
                LOG.info("Use file system leader elector.");
                FileLeaderElectionConfiguration fileLeaderElectionConfiguration = null;
                if (leaderElectionConfiguration instanceof FileLeaderElectionConfiguration) {
                    fileLeaderElectionConfiguration = (FileLeaderElectionConfiguration)leaderElectionConfiguration;
                } else {
                    fileLeaderElectionConfiguration = new FileLeaderElectionConfiguration(leaderElectionConfiguration.getTimeout(), leaderElectionConfiguration.getRenewDeadline(), leaderElectionConfiguration.getRetryPeriod());
                }

                leaderElector = new FileLeaderElectorImpl(leaderElectionInformation, fileLeaderElectionConfiguration);
                break;

            case DATABASE:
                if (leaderElectionConfiguration instanceof DatabaseLeaderElectionConfiguration) {
                    LOG.info("Use database leader elector.");
                    ((DatabaseLeaderElectionConfiguration) leaderElectionConfiguration).validateDataSource();
                    DatabaseLeaderElectorImpl dbElector = new DatabaseLeaderElectorImpl(leaderElectionInformation, (DatabaseLeaderElectionConfiguration)leaderElectionConfiguration);
                    dbElector.initializeSchema();
                    leaderElector = dbElector;
                } else {
                    throw new LeaderElectionException("Invalid configuration. To use database leader election you must define a " + DatabaseLeaderElectionConfiguration.class.getName() + "!");
                }
                break;

            case NETWORK:
                LOG.info("Use network leader elector.");
                NetworkLeaderElectionConfiguration networkLeaderElectionConfiguration = null;
                if (leaderElectionConfiguration instanceof NetworkLeaderElectionConfiguration) {
                    networkLeaderElectionConfiguration = (NetworkLeaderElectionConfiguration)leaderElectionConfiguration;
                } else {
                    networkLeaderElectionConfiguration = new NetworkLeaderElectionConfiguration(leaderElectionConfiguration.getTimeout(), leaderElectionConfiguration.getRenewDeadline(), leaderElectionConfiguration.getRetryPeriod());
                    networkLeaderElectionConfiguration.setCloseTimeout(leaderElectionConfiguration.getCloseTimeout());
                }

                leaderElector = new NetworkLeaderElectorImpl(leaderElectionInformation, networkLeaderElectionConfiguration);
                break;

            case KUBERNETES:
                LOG.info("Use kubernetes leader elector.");
                leaderElector = new KubernetesLeaderElectorImpl(leaderElectionInformation, leaderElectionConfiguration);
                break;

            default:
                break;
        }

        if (leaderElector != null) {
            LeaderElectionScheduler.getInstance().register(leaderElector);
        }

        return leaderElector;
    }


    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Step-1 fluent builder: configure common identity and timing fields, then select
     * a strategy to get a strategy-specific step-2 builder.
     *
     * @author patrick
     */
    public static final class Builder {
        private String namespace = null;
        private String name = null;
        private String identity = null;
        private String username;
        private Duration timeout;
        private Duration renewDeadline;
        private Duration retryPeriod;
        private Duration closeTimeout;


        /**
         * Constructor (package-private; use {@link LeaderElectionFactory#builder()})
         */
        private Builder() {
            // NOP
        }


        /**
         * Set the namespace component of the election group identity.
         * Together with {@code name} the combined election group name ({@code "namespace.name"})
         * must not exceed {@value LeaderElectionInformation#MAX_ELECTION_GROUP_NAME_LENGTH} characters.
         *
         * @param namespace the namespace
         * @return this builder
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }


        /**
         * Set the name component of the election group identity.
         * Together with {@code namespace} the combined election group name ({@code "namespace.name"})
         * must not exceed {@value LeaderElectionInformation#MAX_ELECTION_GROUP_NAME_LENGTH} characters.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }


        /**
         * Set the per-node identity (e.g. hostname or pod name).
         * Must not exceed {@value LeaderElectionInformation#MAX_IDENTITY_LENGTH} characters.
         *
         * @param identity the identity
         * @return this builder
         */
        public Builder identity(String identity) {
            this.identity = identity;
            return this;
        }


        /**
         * Override the OS username stored in the leader record.
         * When not set the value of {@code System.getProperty("user.name")} is used.
         * Must not exceed {@value LeaderElectionInformation#MAX_USERNAME_LENGTH} characters.
         *
         * @param username the username
         * @return this builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }


        /**
         * Set the leadership lease timeout.
         *
         * @param timeout the timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }


        /**
         * Set the renew deadline (must be less than timeout).
         *
         * @param renewDeadline the renew deadline
         * @return this builder
         */
        public Builder renewDeadline(Duration renewDeadline) {
            this.renewDeadline = renewDeadline;
            return this;
        }


        /**
         * Set the retry period (must be less than or equal to renewDeadline).
         *
         * @param retryPeriod the retry period
         * @return this builder
         */
        public Builder retryPeriod(Duration retryPeriod) {
            this.retryPeriod = retryPeriod;
            return this;
        }


        /**
         * Set the close timeout (maximum time to wait for clean shutdown).
         *
         * @param closeTimeout the close timeout
         * @return this builder
         */
        public Builder closeTimeout(Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
            return this;
        }


        /**
         * Select the FILE strategy and obtain a file-specific builder.
         *
         * @return a {@link FileBuilder} carrying the common fields already set
         */
        public FileBuilder file() {
            return new FileBuilder(this);
        }


        /**
         * Select the DATABASE strategy and obtain a database-specific builder.
         *
         * @return a {@link DatabaseBuilder} carrying the common fields already set
         */
        public DatabaseBuilder database() {
            return new DatabaseBuilder(this);
        }


        /**
         * Select the NETWORK strategy and obtain a network builder.
         *
         * @return a {@link NetworkBuilder} carrying the common fields already set
         */
        public NetworkBuilder network() {
            return new NetworkBuilder(this);
        }


        /**
         * Select the KUBERNETES strategy and obtain a kubernetes builder.
         *
         * @return a {@link KubernetesBuilder} carrying the common fields already set
         */
        public KubernetesBuilder kubernetes() {
            return new KubernetesBuilder(this);
        }


        /**
         * Build the {@link LeaderElectionInformation} from the common fields.
         *
         * @return the leader election information
         */
        LeaderElectionInformation buildInfo() {
            LeaderElectionInformation info = new LeaderElectionInformation(namespace, name, identity);
            if (username != null) {
                info.setUsername(username);
            }
            return info;
        }


        /**
         * Apply the common timing fields onto a pre-created configuration instance.
         *
         * @param <C> configuration type
         * @param config the configuration to populate
         * @return the same configuration instance
         */
        <C extends LeaderElectionConfiguration> C buildBaseConfig(C config) {
            if (timeout != null) {
                config.setTimeout(timeout);
                // When only timeout is set, derive renewDeadline and retryPeriod from it
                // so the defaults (based on 10 s) do not violate the validation constraints.
                if (renewDeadline == null && retryPeriod == null) {
                    config.setRetryPeriod(LeaderElectionConfiguration.deriveRetryPeriod(timeout));
                    config.setRenewDeadline(LeaderElectionConfiguration.deriveRenewDeadline(timeout));
                }
            }
            if (renewDeadline != null) {
                config.setRenewDeadline(renewDeadline);
            }
            if (retryPeriod != null) {
                config.setRetryPeriod(retryPeriod);
            }
            if (closeTimeout != null) {
                config.setCloseTimeout(closeTimeout);
            }
            // Validate after all fields are set so intermediate states (e.g. timeout
            // reduced below the current renewDeadline) do not cause false rejections.
            config.validate();
            return config;
        }
    }


    // -------------------------------------------------------------------------
    // Strategy-specific step-2 builders
    // -------------------------------------------------------------------------

    /**
     * Step-2 builder for the FILE strategy.
     *
     * @author patrick
     */
    public static final class FileBuilder {
        private final Builder parent;
        private String basePath;


        /**
         * Constructor
         *
         * @param parent the parent builder carrying the common fields
         */
        private FileBuilder(Builder parent) {
            this.parent = parent;
        }


        /**
         * Set the directory where lock files are stored.
         * Defaults to the system temp directory when not set.
         *
         * @param basePath the lock file directory
         * @return this builder
         */
        public FileBuilder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }


        /**
         * Build the FILE leader elector.
         *
         * @return the leader elector
         * @throws LeaderElectionException In case of an initialisation error
         */
        public ILeaderElector build() throws LeaderElectionException {
            FileLeaderElectionConfiguration config = parent.buildBaseConfig(new FileLeaderElectionConfiguration());
            if (basePath != null) {
                config.setBasePath(basePath);
            }
            return LeaderElectionFactory.getInstance().getLeaderElection(
                    LeaderElectionStrategy.FILE, parent.buildInfo(), config);
        }
    }


    /**
     * Step-2 builder for the DATABASE strategy.
     *
     * @author patrick
     */
    public static final class DatabaseBuilder {
        private final Builder parent;
        private DataSource dataSource;
        private String tableName;
        private LeaderElectionDatabaseStructure databaseStructure;


        /**
         * Constructor
         *
         * @param parent the parent builder carrying the common fields
         */
        private DatabaseBuilder(Builder parent) {
            this.parent = parent;
        }


        /**
         * Set the JDBC data source (required for DATABASE strategy).
         *
         * @param dataSource the data source
         * @return this builder
         */
        public DatabaseBuilder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }


        /**
         * Set the table name. The name is applied to the auto-detected or caller-supplied
         * database structure, so the dialect is still detected automatically. This is a
         * shortcut for calling {@code databaseStructure.setTableName()} manually.
         *
         * @param tableName the table name
         * @return this builder
         */
        public DatabaseBuilder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }


        /**
         * Set a custom database structure (table name, DDL, DML).
         * When not set the default {@link com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure} is used.
         *
         * @param databaseStructure the database structure
         * @return this builder
         */
        public DatabaseBuilder databaseStructure(LeaderElectionDatabaseStructure databaseStructure) {
            this.databaseStructure = databaseStructure;
            return this;
        }


        /**
         * Build the DATABASE leader elector.
         *
         * @return the leader elector
         * @throws LeaderElectionException In case of an initialisation error
         */
        public ILeaderElector build() throws LeaderElectionException {
            DatabaseLeaderElectionConfiguration config = parent.buildBaseConfig(new DatabaseLeaderElectionConfiguration());
            config.setDataSource(dataSource);
            if (databaseStructure != null) {
                config.setLeaderElectionDatabaseStructure(databaseStructure);
            }
            if (tableName != null) {
                config.setTableName(tableName);
            }
            return LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, parent.buildInfo(), config);
        }
    }


    /**
     * Step-2 builder for the NETWORK strategy.
     *
     * @author patrick
     */
    public static final class NetworkBuilder {
        private final Builder parent;
        private int minimumMembers = NetworkLeaderElectionConfiguration.DEFAULT_MINIMUM_MEMBERS;
        private String configuration;
        private SecretKey encryptionKey;


        /**
         * Constructor
         *
         * @param parent the parent builder carrying the common fields
         */
        private NetworkBuilder(Builder parent) {
            this.parent = parent;
        }


        /**
         * Set the minimum number of cluster members required to take the lead (quorum).
         * Use a majority of the expected cluster size ({@code n / 2 + 1}) to prevent two leaders
         * during a network partition. Default is 1 (no quorum).
         *
         * @param minimumMembers the minimum number of cluster members, at least 1
         * @return this builder
         */
        public NetworkBuilder minimumMembers(int minimumMembers) {
            this.minimumMembers = minimumMembers;
            return this;
        }


        /**
         * Set a custom JGroups configuration that replaces the default protocol stack, e.g. to use TCP,
         * a specific network interface or other security protocols.
         *
         * @param configuration the JGroups configuration (file, classpath resource or URL)
         * @return this builder
         */
        public NetworkBuilder configuration(String configuration) {
            this.configuration = configuration;
            return this;
        }


        /**
         * Encrypt all cluster messages with a shared key. A node without the key can neither read the
         * messages nor join the cluster. All nodes of the cluster need the same key, an AES key of 128, 192 or 256 bits.
         *
         * @param encryptionKey the shared secret key
         * @return this builder
         * @throws IllegalArgumentException In case the key is no AES key of 128, 192 or 256 bits
         */
        public NetworkBuilder encryptionKey(SecretKey encryptionKey) throws IllegalArgumentException {
            NetworkLeaderElectionConfiguration.validateEncryptionKey(encryptionKey);
            this.encryptionKey = encryptionKey;
            return this;
        }


        /**
         * Encrypt all cluster messages with a shared key read from a keystore (PKCS12 or JCEKS). A node without
         * the key can neither read the messages nor join the cluster. All nodes of the cluster need the same key.
         * The password array is not changed; the caller can clear it afterwards.
         *
         * @param keystore the keystore file
         * @param password the password of the keystore and the key
         * @param alias the alias of the secret key entry
         * @return this builder
         * @throws IllegalArgumentException In case the key could not be read
         */
        public NetworkBuilder encryptionKey(String keystore, char[] password, String alias) throws IllegalArgumentException {
            this.encryptionKey = NetworkLeaderElectionConfiguration.loadEncryptionKey(keystore, password, alias);
            return this;
        }


        /**
         * Build the NETWORK leader elector.
         *
         * @return the leader elector
         * @throws LeaderElectionException In case of an initialisation error
         */
        public ILeaderElector build() throws LeaderElectionException {
            NetworkLeaderElectionConfiguration config = parent.buildBaseConfig(new NetworkLeaderElectionConfiguration());
            config.setMinimumMembers(minimumMembers);
            config.setConfiguration(configuration);
            config.setEncryptionKey(encryptionKey);
            return LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.NETWORK, parent.buildInfo(), config);
        }
    }


    /**
     * Step-2 builder for the KUBERNETES strategy.
     *
     * @author patrick
     */
    public static final class KubernetesBuilder {
        private final Builder parent;
        private KubernetesLockType lockType = KubernetesLeaderElectionConfiguration.DEFAULT_LOCK_TYPE;


        /**
         * Constructor
         *
         * @param parent the parent builder carrying the common fields
         */
        private KubernetesBuilder(Builder parent) {
            this.parent = parent;
        }


        /**
         * Set the Kubernetes resource that holds the lock. Default is {@link KubernetesLockType#LEASE}.
         * Use {@link KubernetesLockType#ENDPOINTS} only during a rolling upgrade from 1.2.x, until all
         * nodes run this release; nodes with different lock types do not see each other's lock.
         *
         * @param lockType the lock type
         * @return this builder
         */
        public KubernetesBuilder lockType(KubernetesLockType lockType) {
            this.lockType = lockType;
            return this;
        }


        /**
         * Build the KUBERNETES leader elector.
         *
         * @return the leader elector
         * @throws LeaderElectionException In case of an initialisation error
         */
        public ILeaderElector build() throws LeaderElectionException {
            KubernetesLeaderElectionConfiguration config = parent.buildBaseConfig(new KubernetesLeaderElectionConfiguration());
            config.setLockType(lockType);
            return LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.KUBERNETES, parent.buildInfo(), config);
        }
    }


    // -------------------------------------------------------------------------
    // Administration API
    // -------------------------------------------------------------------------

    /**
     * Implementation of {@link ILeaderElectionAdministration} delegating to {@link LeaderElectionScheduler}.
     */
    private static final class LeaderElectionAdministration implements ILeaderElectionAdministration {

        /**
         * Constructor
         */
        private LeaderElectionAdministration() {
            // NOP
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElectionAdministration#getElectors()
         */
        @Override
        public Collection<ILeaderElectorView> getElectors() {
            return LeaderElectionScheduler.getInstance().getElectorViews();
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElectionAdministration#forceRelease(String)
         */
        @Override
        public boolean forceRelease(String id) throws LeaderElectionException {
            return LeaderElectionScheduler.getInstance().forceRelease(id);
        }
    }
}
