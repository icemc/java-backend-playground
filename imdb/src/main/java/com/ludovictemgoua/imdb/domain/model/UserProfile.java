package com.ludovictemgoua.imdb.domain.model;

public record UserProfile(int id, String email, String displayName, String bio, Role role, int version) {
}
