package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;

import java.util.Optional;

public interface UserRepository {

    User insert(String email, String passwordHash, String displayName, Role role);

    Optional<User> findById(int id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    WriteResult updateProfile(int id, String displayName, String bio, int expectedVersion);

    void updateRole(int id, Role role);

    void softDelete(int id);

    PagedResult<User> findAll(int page, int size);
}
