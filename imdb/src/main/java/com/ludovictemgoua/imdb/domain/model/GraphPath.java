package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

// Deliberately algorithm-agnostic: just a degree and the ordered chain of person ids connecting the
// two endpoints. Nothing here leaks that today's implementation happens to compute this by meeting
// two searches in the middle - a future CoStarGraphRepository implementation (precomputed BFS, a
// graph database) returns the exact same shape without this type ever changing.
public record GraphPath(int degree, List<Integer> personIds) {
}
