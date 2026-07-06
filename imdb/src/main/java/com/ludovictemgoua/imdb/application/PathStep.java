package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.SharedTitle;

public record PathStep(String id, String name, SharedTitle sharedTitle) {
}
