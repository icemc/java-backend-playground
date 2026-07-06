package com.ludovictemgoua.imdb.application;

import java.util.List;

public record SixDegreesResult(PersonRef personA, PersonRef personB, Integer degree,
                                boolean withinRequestedMax, List<PathStep> path) {
}
