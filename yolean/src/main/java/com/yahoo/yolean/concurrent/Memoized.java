package com.yahoo.yolean.concurrent;

import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Wraps a lazily initialised resource which needs to be shut down.
 * The wrapped supplier may not return {@code null}, and should be retryable on failure.
 * If it throws, it will be retried if {@link #get} is retried. A supplier that fails to
 * clean up partial state on failure may cause a resource leak.
 *
 * @author jonmv
 */
public class Memoized<T, E extends Exception> implements Supplier<T>, AutoCloseable {

    /** Provides a tighter bound on the thrown exception type. */
    @FunctionalInterface
    public interface Closer<T, E extends Exception> { void close(T t) throws E; }

    private final Object monitor = new Object();
    private final Closer<T, E> closer;
    private volatile T wrapped;
    private Supplier<T> factory;

    public Memoized(Supplier<T> factory, Closer<T, E> closer) {
        this.factory = requireNonNull(factory);
        this.closer = requireNonNull(closer);
    }

    public static <T extends AutoCloseable> Memoized<T, ?> of(Supplier<T> factory) {
        return new Memoized<>(factory, AutoCloseable::close);
    }

    @Override
    public T get() {
        // Double-checked locking: try the variable, and if not initialized, try to initialize it.
        if (wrapped == null) synchronized (monitor) {
            // Ensure the factory is called only once, by clearing it once successfully called.
            if (factory != null) wrapped = requireNonNull(factory.get());
            factory = null;

            // If we found the factory, we won the initialization race, and return normally; otherwise
            // if wrapped is non-null, we lost the race, wrapped was set by the winner, and we return; otherwise
            // we tried to initialise because wrapped was cleared by closing this, and we fail.
            if (wrapped == null) throw new IllegalStateException("already closed");
        }
        return wrapped;
    }

    @Override
    public void close() throws E {
        // Alter state only when synchronized with calls to get().
        synchronized (monitor) {
            // Ensure we only try to close the generated resource once, by clearing it after picking it up here.
            T maybe = wrapped;
            wrapped = null;
            // Clear the factory, to signal this has been closed.
            factory = null;
            if (maybe != null) closer.close(maybe);
        }
    }

}