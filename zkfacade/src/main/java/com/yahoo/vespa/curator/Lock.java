// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.curator;

import com.google.common.util.concurrent.UncheckedTimeoutException;
import com.yahoo.path.Path;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.curator.stats.ThreadLockStats;
import org.apache.curator.framework.recipes.locks.InterProcessLock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A cluster-wide re-entrant mutex which is released on (the last symmetric) close.
 *
 * Re-entrancy is limited to the instance of this. To ensure re-entrancy callers should access the lock through
 * {@link Curator#lock(Path, Duration)} instead of constructing this directly.
 *
 * @author bratseth
 */
public class Lock implements Mutex {

    private final InterProcessLock mutex;
    private final String lockPath;

    public Lock(String lockPath, Curator curator) {
        this(lockPath, curator.createMutex(lockPath));
    }

    /** Public for testing only */
    public Lock(String lockPath, InterProcessLock mutex) {
        this.lockPath = lockPath;
        this.mutex = mutex;
    }

    /** Take the lock with the given timeout. This may be called multiple times from the same thread - each matched by a close */
    public void acquire(Duration timeout) throws UncheckedTimeoutException {
        ThreadLockStats threadLockStats = ThreadLockStats.getCurrentThreadLockStats();
        threadLockStats.invokingAcquire(lockPath, timeout);

        final boolean acquired;
        try {
            acquired = mutex.acquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            threadLockStats.acquireFailed(lockPath);
            throw new RuntimeException("Exception acquiring lock '" + lockPath + "'", e);
        }

        if (!acquired) {
            threadLockStats.acquireTimedOut(lockPath);
            throw new UncheckedTimeoutException("Timed out after waiting " + timeout +
                    " to acquire lock '" + lockPath + "'");
        }
        threadLockStats.lockAcquired(lockPath);
    }

    @Override
    public void close() {
        try {
            mutex.release();
            ThreadLockStats.getCurrentThreadLockStats().lockReleased(lockPath);
        }
        catch (Exception e) {
            ThreadLockStats.getCurrentThreadLockStats().lockReleaseFailed(lockPath);
            throw new RuntimeException("Exception releasing lock '" + lockPath + "'");
        }
    }
}


