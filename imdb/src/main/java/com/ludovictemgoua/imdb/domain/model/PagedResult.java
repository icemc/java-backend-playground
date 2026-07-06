package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record PagedResult<T>(List<T> content, long totalElements, int page, int size) {
}
