package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.application.rest.UpdateProfileRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.springframework.stereotype.Service;

@Service
public class UserUseCaseImpl implements UserUseCase {

    private final UserRepository userRepository;

    public UserUseCaseImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserProfile getOwnProfile(int userId) {
        return toProfile(findOrThrow(userId));
    }

    @Override
    public UserProfile updateOwnProfile(int userId, UpdateProfileRequest request) {
        WriteResult result = userRepository.updateProfile(userId, request.displayName(), request.bio(), request.version());
        switch (result) {
            case NOT_FOUND -> throw new NotFoundException("No user with id " + userId);
            case VERSION_CONFLICT -> throw new ConflictException("Your profile was modified concurrently - refresh and retry");
            case SUCCESS -> { }
        }
        return getOwnProfile(userId);
    }

    @Override
    public void deleteOwnAccount(int userId) {
        userRepository.softDelete(userId);
    }

    @Override
    public PublicUserProfile getPublicProfile(int userId) {
        User user = findOrThrow(userId);
        return new PublicUserProfile(user.id(), user.displayName());
    }

    @Override
    public PagedResult<UserProfile> listAll(int page, int size) {
        PagedResult<User> users = userRepository.findAll(page, size);
        return new PagedResult<>(users.content().stream().map(UserUseCaseImpl::toProfile).toList(),
                users.totalElements(), users.page(), users.size());
    }

    @Override
    public void updateRole(int userId, Role role) {
        findOrThrow(userId);
        userRepository.updateRole(userId, role);
    }

    @Override
    public void deleteAccount(int userId) {
        findOrThrow(userId);
        userRepository.softDelete(userId);
    }

    private User findOrThrow(int userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("No user with id " + userId));
    }

    private static UserProfile toProfile(User user) {
        return new UserProfile(user.id(), user.email(), user.displayName(), user.bio(), user.role(), user.version());
    }
}
