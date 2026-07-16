package com.ludovictemgoua.imdb.application.rest;

import java.util.List;

// Every field nullable/absent - merge-patch semantics (docs/crud-expansion-design.md §6.5): only
// fields present in the JSON body are applied, everything else is left as-is on the existing row.
public record PatchTitleRequest(String primaryTitle, String originalTitle, String titleType,
                                 Integer startYear, Integer endYear, Integer runtimeMinutes,
                                 List<String> genres, int version) {
}
