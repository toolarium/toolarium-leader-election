/*
 * KubernetesLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.Lock;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link LeaderElector} based on kubernetes api.
 * 
 * @author patrick
 */
public class KubernetesLeaderElectorImpl extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesLeaderElectorImpl.class);
    private io.kubernetes.client.extended.leaderelection.LeaderElector leaderElector;
    private Thread updateThread;
    private volatile boolean runThread;
    private final ApiClient preConfiguredClient;


    /**
     * Constructor for KubernetesLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public KubernetesLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) {
        this(leaderElectionInformation, leaderElectionConfiguration, null);
    }


    /**
     * Constructor for KubernetesLeaderElectorImpl with a pre-configured API client.
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @param apiClient an already-configured Kubernetes API client, or null to use the default
     */
    public KubernetesLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration, ApiClient apiClient) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        this.preConfiguredClient = apiClient;
        leaderElector = null;
        updateThread = null;
        runThread = false;
    }

    
    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        if (!isInitialized()) {
            LOG.debug("Initialize kubernetes api client...");

            try {
                ApiClient client = (preConfiguredClient != null) ? preConfiguredClient : Config.defaultClient();
                Configuration.setDefaultApiClient(client);
            } catch (IOException e) {
                throw new LeaderElectionException("Could not initialize the kubernetes api client: " + e.getMessage(), e);
            }
    
            final Lock lock = createLock(getLeaderElectionInformation());
            leaderElector = new io.kubernetes.client.extended.leaderelection.LeaderElector(new LeaderElectionConfig(lock, getLeaderElectionConfiguration().getTimeout(), 
                                                                                           getLeaderElectionConfiguration().getRenewDeadline(), 
                                                                                           getLeaderElectionConfiguration().getRetryPeriod()));
            
            updateThread = new Thread(KubernetesLeaderElectorImpl.class.getName() + ": Update thread") { 
                /**
                 * @see java.lang.Thread#run()
                 */
                @Override
                public void run() {
                    while (runThread && !Thread.currentThread().isInterrupted()) {
                        leaderElector.run(() -> {
                            LOG.debug("LEAD");
                            setLeader(true, null);
                        }, () -> {
                            LOG.debug("NO LEAD");
                            setLeader(false, null);
                        });
                        if (runThread && !Thread.currentThread().isInterrupted()) {
                            try {
                                Thread.sleep(getLeaderElectionConfiguration().getRetryPeriod().toMillis());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    
                    runThread = false;
                    LOG.debug(KubernetesLeaderElectorImpl.class.getName() + " thread stopped successful.");
                }
            };
            
            runThread = true;
            updateThread.setDaemon(true);
            updateThread.setName(KubernetesLeaderElectorImpl.class.getName() + ": Update thread");
            updateThread.start();
        }
    }


    /**
     * Create the lock
     * 
     * @param leaderElectionInformation the leader election information
     * @return the lock
     */
    protected Lock createLock(LeaderElectionInformation leaderElectionInformation) {
        return new EndpointsLock(leaderElectionInformation.getNamespace(), leaderElectionInformation.getName(), leaderElectionInformation.getIdentity());
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderElector#close()
     */
    @Override
    public void close() {
        super.close();

        runThread = false;
        final Thread thread = updateThread;
        updateThread = null;

        if (leaderElector != null) {
            LOG.info("Exited from [" + getLeaderElectionInformation().getUniqueName() + "].");

            try {
                leaderElector.close();
            } catch (Exception e) {
                // NOP
            }
        }

        leaderElector = null;

        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}