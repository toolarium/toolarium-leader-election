/*
 * IdentityUtil.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.util;

import java.net.InetAddress;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility for resolving the identity of this instance.
 *
 * @author patrick
 */
public final class IdentityUtil {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityUtil.class);


    /**
     * Private class, the only instance of the singleton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final IdentityUtil INSTANCE = new IdentityUtil();
    }

    /** Cached host name, resolved once. */
    private volatile String cachedHostName;


    /**
     * Constructor
     */
    private IdentityUtil() {
        // NOP
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static IdentityUtil getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Resolve a unique identity for an elector instance. The identity is built from the
     * host name and a short random suffix so that multiple electors in the same JVM each
     * get a distinct value.
     *
     * <p>The host name is resolved once and cached:
     * <ol>
     *   <li>The {@code HOSTNAME} environment variable (the pod name in Kubernetes).</li>
     *   <li>The local host name via {@link InetAddress#getLocalHost()}.</li>
     *   <li>A full random UUID as last resort.</li>
     * </ol>
     *
     * @return a unique identity string
     */
    public String resolveIdentity() {
        String host = resolveHostName();
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        return host + "-" + shortId;
    }


    /**
     * Resolve and cache the host name of this instance.
     *
     * @return the host name
     */
    private String resolveHostName() {
        String result = cachedHostName;
        if (result != null) {
            return result;
        }

        String hostName = System.getenv("HOSTNAME");
        if (hostName != null && !hostName.isBlank()) {
            cachedHostName = hostName;
            return hostName;
        }

        try {
            result = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not resolve the host name, falling back to UUID: {}", e.getMessage(), e);
            }
            result = UUID.randomUUID().toString();
        }

        cachedHostName = result;
        return result;
    }
}
