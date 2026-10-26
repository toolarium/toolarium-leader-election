/*
 * KubernetesLockType.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.kubernetes;

/**
 * Defines the Kubernetes resource that holds the leader election lock.
 *
 * @author patrick
 */
public enum KubernetesLockType {
    /**
     * A {@code coordination.k8s.io/v1} Lease. Requires RBAC on {@code leases} only.
     */
    LEASE,

    /**
     * An Endpoints object, the lock of release 1.2.x and earlier. Requires RBAC write access on {@code endpoints},
     * which also controls Service routing, and Endpoints is a deprecated API. Only use it during a rolling upgrade
     * from 1.2.x, until all nodes run a release that uses {@link #LEASE}.
     */
    ENDPOINTS;
}
