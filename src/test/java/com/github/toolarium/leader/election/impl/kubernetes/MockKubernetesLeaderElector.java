/*
 * MockKubernetesLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.Lock;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The mock kubernetes leader elector implementation
 *
 * @author patrick
 */
public final class MockKubernetesLeaderElector {
    private static final Logger LOG = LoggerFactory.getLogger(MockKubernetesLeaderElector.class);


    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final MockKubernetesLeaderElector INSTANCE = new MockKubernetesLeaderElector();
    }


    /**
     * Constructor
     */
    private MockKubernetesLeaderElector() {
        // NOP
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static MockKubernetesLeaderElector getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Create a fluent builder for constructing a mock Kubernetes leader elector.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }


    /**
     * Mock the kubernetes leader elector implementation
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws IOException in case of an i/o error
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public ILeaderElector getLeaderElection(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws IOException {
        return new KubernetesLeaderElectorMock(leaderElectionInformation, leaderElectionConfiguration);
    }


    /**
     * Fluent builder for a mock Kubernetes leader elector.
     *
     * @author patrick
     */
    public static final class Builder {
        private String namespace = "";
        private String name = "";
        private String identity = "";
        private Duration timeout;
        private Duration renewDeadline;
        private Duration retryPeriod;
        private Duration closeTimeout;


        /**
         * Constructor (package-private; use {@link MockKubernetesLeaderElector#builder()})
         */
        private Builder() {
            // NOP
        }


        /**
         * Set the namespace.
         *
         * @param namespace the namespace
         * @return this builder
         */
        public Builder namespace(String namespace) {
            if (namespace != null) {
                this.namespace = namespace;
            } else {
                this.namespace = "";
            }
            return this;
        }


        /**
         * Set the name.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            if (name != null) {
                this.name = name;
            } else {
                this.name = "";
            }
            return this;
        }


        /**
         * Set the per-node identity.
         *
         * @param identity the identity
         * @return this builder
         */
        public Builder identity(String identity) {
            if (identity != null) {
                this.identity = identity;
            } else {
                this.identity = "";
            }
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
         * Set the renew deadline.
         *
         * @param renewDeadline the renew deadline
         * @return this builder
         */
        public Builder renewDeadline(Duration renewDeadline) {
            this.renewDeadline = renewDeadline;
            return this;
        }


        /**
         * Set the retry period.
         *
         * @param retryPeriod the retry period
         * @return this builder
         */
        public Builder retryPeriod(Duration retryPeriod) {
            this.retryPeriod = retryPeriod;
            return this;
        }


        /**
         * Set the close timeout.
         *
         * @param closeTimeout the close timeout
         * @return this builder
         */
        public Builder closeTimeout(Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
            return this;
        }


        /**
         * Build the mock Kubernetes leader elector.
         *
         * @return the leader elector
         * @throws IOException in case of an i/o error
         */
        public ILeaderElector build() throws IOException {
            LeaderElectionConfiguration config;
            if (timeout != null && renewDeadline != null && retryPeriod != null) {
                config = new LeaderElectionConfiguration(timeout, renewDeadline, retryPeriod);
            } else if (timeout != null && renewDeadline == null && retryPeriod == null) {
                config = new LeaderElectionConfiguration(timeout.toSeconds());
            } else {
                config = new LeaderElectionConfiguration();
                if (timeout != null) {
                    config.setTimeout(timeout);
                }
                if (renewDeadline != null) {
                    config.setRenewDeadline(renewDeadline);
                }
                if (retryPeriod != null) {
                    config.setRetryPeriod(retryPeriod);
                }
            }
            if (closeTimeout != null) {
                config.setCloseTimeout(closeTimeout);
            }
            return MockKubernetesLeaderElector.getInstance().getLeaderElection(
                    new LeaderElectionInformation(namespace, name, identity), config);
        }
    }


    protected class KubernetesLeaderElectorMock extends KubernetesLeaderElectorImpl {
        private List<String> electionHistory = new ArrayList<>();
        private List<String> leadershipHistory = new ArrayList<>();

        /**
         * Constructor for KubernetesLeaderElectorImpl
         *
         * @param leaderElectionInformation the leader election information
         * @param leaderElectionConfiguration the leader election configuration
         * @throws IOException in case of an i/o error
         */
        public KubernetesLeaderElectorMock(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws IOException {
            super(leaderElectionInformation, leaderElectionConfiguration);
        }


        /**
         * Get the election history
         *
         * @return the election history
         */
        public List<String> getElectionHistory() {
            return electionHistory;
        }


        /**
         * Get the leadership history
         *
         * @return the leadership history
         */
        public List<String> getLeadershipHistory() {
            return leadershipHistory;
        }


        /**
         * @see com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl#createLock(com.github.toolarium.leader.election.dto.LeaderElectionInformation)
         */
        @Override
        protected Lock createLock(LeaderElectionInformation leaderElectionInformation) {

            MockResourceLock mockLock = new MockResourceLock(leaderElectionInformation.getUniqueName());
            mockLock.setRenewCountMax(50);
            /*
            mockLock.setConsumers(
                    record -> {
                        LOG.debug("[CREATE] " + KubernetesUtil.getInstance().toString(record));
                        electionHistory.add("create record");
                        leadershipHistory.add("get leadership");
                    },
                    record -> {
                        LOG.debug("[UPDATE] " + KubernetesUtil.getInstance().toString(record));
                        electionHistory.add("update record");
                    },
                    record -> {
                        LOG.debug("[CHANGE] " + KubernetesUtil.getInstance().toString(record));
                        electionHistory.add("change record");
                    });
*/
            return mockLock;
        }
    }
}
