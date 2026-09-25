package com.keyloop.docviewer.domain;

/** The external dealership systems documents are aggregated from. */
public enum SourceSystem {

    SALES("Sales System"),
    SERVICE("Service System");

    private final String displayName;

    SourceSystem(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
