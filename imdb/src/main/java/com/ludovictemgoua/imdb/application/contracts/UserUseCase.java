package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.rest.UpdateProfileRequest;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.UserProfile;

public interface UserUseCase {

    UserProfile getOwnProfile(int userId);

    UserProfile updateOwnProfile(int userId, UpdateProfileRequest request);

    void deleteOwnAccount(int userId);

    PublicUserProfile getPublicProfile(int userId);

    PagedResult<UserProfile> listAll(int page, int size);

    void updateRole(int userId, Role role);

    void deleteAccount(int userId);
}
