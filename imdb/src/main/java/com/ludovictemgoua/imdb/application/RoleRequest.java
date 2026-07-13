package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Role;
import jakarta.validation.constraints.NotNull;

public record RoleRequest(@NotNull Role role) {
}
