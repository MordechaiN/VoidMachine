package com.voidmachine.core.journal;

/** The journal could not durably perform an operation. Callers must fail closed. */
public final class JournalException extends Exception {

    public JournalException(String message) {
        super(message);
    }

    public JournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
