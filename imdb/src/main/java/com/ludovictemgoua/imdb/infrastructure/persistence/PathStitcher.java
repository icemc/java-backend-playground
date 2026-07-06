package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.GraphPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PathStitcher {

    private PathStitcher() {
    }

    static GraphPath stitch(JdbcCoStarGraphRepository.RawMatch match) {
        List<Integer> backward = new ArrayList<>(match.backwardPath());
        Collections.reverse(backward);
        // forward's last element and reversed-backward's first element are the same node (where the
        // two searches met) - drop the duplicate before concatenating.
        List<Integer> stitched = new ArrayList<>(match.forwardPath());
        stitched.addAll(backward.subList(1, backward.size()));
        return new GraphPath(match.degree(), stitched);
    }
}
