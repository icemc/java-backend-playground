package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record CastMember(String id, String name, String category, List<String> characters, int ordering) {
}
