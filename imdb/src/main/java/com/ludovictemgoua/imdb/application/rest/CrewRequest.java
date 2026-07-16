package com.ludovictemgoua.imdb.application.rest;

import java.util.List;

public record CrewRequest(List<String> directors, List<String> writers) {
}
