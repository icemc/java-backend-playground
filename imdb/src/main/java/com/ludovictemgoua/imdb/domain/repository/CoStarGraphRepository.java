package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.GraphPath;

import java.util.Optional;

public interface CoStarGraphRepository {

    Optional<GraphPath> findShortestPath(int personA, int personB);
}
