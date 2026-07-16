package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record PrincipalCredit(String personId, String personName, String category, String job,
                               List<String> characters, int ordering, int version) {
}
