/*
 * KubernetesUtil.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.openapi.ApiException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Kubernetes util
 *
 * @author patrick
 */
public final class KubernetesUtil {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesUtil.class);
    private boolean checkEnvironmentVariables;
    private boolean checkEndpoint;


    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final KubernetesUtil INSTANCE = new KubernetesUtil();
    }


    /**
     * Constructor
     */
    private KubernetesUtil() {
        checkEnvironmentVariables = true;
        checkEndpoint = true;
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static KubernetesUtil getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Check if kubernetes is available
     *
     * @param leaderElectionInformation the leader election information
     * @return true if kubernetes environment is available
     */
    public boolean isAvailable(LeaderElectionInformation leaderElectionInformation) {

        if (checkEnvironmentVariables) {
            if (!hasKubernetesEnvironmentVariables()) {
                return false;
            }
        }

        if (checkEndpoint) {
            if (!hasEndpoint(leaderElectionInformation)) {
                return false;
            }
        }

        return true;
    }


    /**
     * Check kubernetes endpoint
     *
     * @param checkEndpoint true to check the kubernetes endpoint
     */
    public void setCheckEndpoint(boolean checkEndpoint) {
        this.checkEndpoint = checkEndpoint;
    }


    /**
     * Check if kubernetes is available
     *
     * @param leaderElectionInformation the leader election information
     * @return true if kubernetes environment is available
     */
    public boolean hasEndpoint(LeaderElectionInformation leaderElectionInformation) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(() -> {
            try {
                final EndpointsLock lock = new EndpointsLock(leaderElectionInformation.getNamespace(), leaderElectionInformation.getName(), leaderElectionInformation.getIdentity());
                lock.get();
                return true;
            } catch (ApiException e) {
                LOG.debug("Could not connect to kubernetes (http-code [" + e.getCode() + "]): " + e.getMessage());
            } catch (Exception e) {
                LOG.debug("Could not connect to kubernetes: " + e.getMessage());
            }
            return false;
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.debug("Kubernetes endpoint check timed out.");
            future.cancel(true);
        } catch (Exception e) {
            LOG.debug("Kubernetes endpoint check failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }

        return false;
    }


    /**
     * Check kubernetes environment variables
     *
     * @param checkEnvironmentVariables true to check the kubernetes environment variables
     */
    public void setCheckEnvironmentVariables(boolean checkEnvironmentVariables) {
        this.checkEnvironmentVariables = checkEnvironmentVariables;
    }


    /**
     * Check if kubernetes is available
     *
     * @return true if kubernetes environment is available
     */
    public boolean hasKubernetesEnvironmentVariables() {
        int counter = 0;
        for (String name : System.getenv().keySet()) {
            /*
            KUBERNETES_PORT=tcp://10.96.0.1:443
            KUBERNETES_PORT_443_TCP=tcp://10.96.0.1:443
            KUBERNETES_PORT_443_TCP_ADDR=10.96.0.1
            KUBERNETES_PORT_443_TCP_PORT=443
            KUBERNETES_PORT_443_TCP_PROTO=tcp
            KUBERNETES_SERVICE_HOST=10.96.0.1
            KUBERNETES_SERVICE_PORT=443
            KUBERNETES_SERVICE_PORT_HTTPS=443
            */

            if (name.startsWith("KUBERNETES")) {
                counter++;
            }
        }

        return (counter > 0);
    }


    /**
     * Convert the given leader election record to a string
     *
     * @param record the leader election record
     * @return the string representation
     */
    public String toString(LeaderElectionRecord record) {
        return record.hashCode() + " " + record.getHolderIdentity() + " "
                    + record.getLeaderTransitions() + " / "
                    + record.getLeaseDurationSeconds() + " / "
                    + DateTimeFormatter.ISO_INSTANT.format(record.getAcquireTime().toInstant()) + " / "
                    + DateTimeFormatter.ISO_INSTANT.format(record.getRenewTime().toInstant());
    }

}
