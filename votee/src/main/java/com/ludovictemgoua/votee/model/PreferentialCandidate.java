package com.ludovictemgoua.votee.model;

public record PreferentialCandidate(String id, String name, String party) implements Candidate {
    public PreferentialCandidate(String id, String name) {
        this(id, name, null);
    }
}
