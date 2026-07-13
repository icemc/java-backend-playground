package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record CustomListView(int id, int userId, String name, Visibility visibility, int version,
                              List<ListItemView> items) {
}
