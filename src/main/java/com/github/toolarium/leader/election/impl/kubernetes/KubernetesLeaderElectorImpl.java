/*
 * KubernetesLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link ILeaderElector} based on kubernetes api.
 * 
 * @author patrick
 */
public class KubernetesLeaderElectorImpl extends AbstractLeaderElectorImpl {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesLeaderElectorImpl.class);
    private LeaderElector leaderElector;

    
    /**
     * Constructor for KubernetesLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws IOException in case of an i/o error
     */
    public KubernetesLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration)
        throws IOException {
        super(leaderElectionInformation, leaderElectionConfiguration);
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#init()
     * @throws IOException in case of an i/o error
     */
    protected void init() throws IOException {
        LOG.debug("Initialize kubernetes api client...");
        
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        final EndpointsLock lock = new EndpointsLock(getLeaderElectionInformation().getNamespace(), getLeaderElectionInformation().getName(), getLeaderElectionInformation().getIdentity());
        leaderElector = new LeaderElector(new LeaderElectionConfig(lock, getLeaderElectionConfiguration().getTimeout(), getLeaderElectionConfiguration().getRenewDeadline(), getLeaderElectionConfiguration().getRetryPeriod()));
        leaderElector.run(
                () -> {
                    setLeader(true, null);
                },                () -> {
                    setLeader(false, null);
                });
    }
}