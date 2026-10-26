/*
 * RunIfLeaderConcurrencyTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;


/**
 * Tests that a named leader task never runs twice at the same time, even when many threads start and finish it concurrently.
 *
 * @author patrick
 */
public class RunIfLeaderConcurrencyTest {

    /**
     * Many threads run the same named task in a loop; at most one of them runs it at any time.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testNamedTaskNeverRunsTwice() throws Exception {
        StubElector elector = new StubElector();
        assertTrue(elector.elect(Duration.ofSeconds(1)));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 3000; j++) {
                            elector.runIfLeader("concurrency-task", lease -> {
                                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                                runs.incrementAndGet();
                                active.decrementAndGet();
                            });
                        }
                    } catch (Exception e) {
                        // NOP
                    }
                });
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(60000);
            }
            assertTrue(runs.get() > 0);
            assertEquals(1, maxActive.get(), "A named task must never run twice at the same time");
        } finally {
            elector.close();
        }
    }


    /**
     * A minimal elector that leads at once.
     */
    private static final class StubElector extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {

        /**
         * Constructor for StubElector
         */
        StubElector() {
            super(new LeaderElectionInformation("run-if-leader-concurrency-" + System.nanoTime()), new LeaderElectionConfiguration());
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
         */
        @Override
        protected void initializeImplementation() throws LeaderElectionException {
            setLeader(true, null);
        }
    }
}
