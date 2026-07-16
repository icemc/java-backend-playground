package com.ludovictemgoua.imdb.domain.model;

public record User(int id, String email, String passwordHash, String displayName, String bio,
                    Role role, int version) {
}
