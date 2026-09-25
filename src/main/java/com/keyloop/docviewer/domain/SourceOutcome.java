package com.keyloop.docviewer.domain;

/** Result of calling one source system during a search. */
public enum SourceOutcome {
    /** The source answered; its document list (possibly empty) is current. */
    OK,
    /** The source did not answer within its timeout. */
    TIMEOUT,
    /** The source failed: connection error, 5xx, or an unreadable response. */
    ERROR
}
