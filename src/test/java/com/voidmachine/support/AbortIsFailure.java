package com.voidmachine.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.opentest4j.TestAbortedException;

/**
 * Registered for every test (service loader + auto-detection). MockBukkit signals an unimplemented
 * server feature with a {@link TestAbortedException}, which JUnit reports as "skipped" — a test that
 * never reached its assertions would look harmless. Here it fails, with the original stack trace.
 */
public final class AbortIsFailure implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw convert(throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw convert(throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw convert(throwable);
    }

    private static Throwable convert(Throwable t) {
        if (t instanceof TestAbortedException) {
            AssertionError e = new AssertionError("aborted (unimplemented in the mock server?): " + t, t);
            e.setStackTrace(t.getStackTrace());
            return e;
        }
        return t;
    }
}
