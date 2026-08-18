/*
 * MockKubernetesLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.Lock;
import java.io.IOException;
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
     * Mock the kubernetes leader elector implementation
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws IOException in case of an i/o error
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElector getLeaderElection(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws IOException {
        return new KubernetesLeaderElectorMock(leaderElectionInformation, leaderElectionConfiguration);
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
