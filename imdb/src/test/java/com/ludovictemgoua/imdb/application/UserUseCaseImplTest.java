package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.rest.UpdateProfileRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserUseCaseImplTest {

    @Mock
    UserRepository userRepository;

    @Test
    void getOwnProfileExcludesThePasswordHash() {
        given(userRepository.findById(7))
                .willReturn(Optional.of(new User(7, "a@example.com", "secret-hash", "Ada", "bio", Role.USER, 0)));

        var profile = new UserUseCaseImpl(userRepository).getOwnProfile(7);

        assertThat(profile.email()).isEqualTo("a@example.com");
        assertThat(profile.displayName()).isEqualTo("Ada");
    }

    @Test
    void getOwnProfileThrowsNotFoundForAnUnknownId() {
        given(userRepository.findById(999)).willReturn(Optional.empty());

        assertThatThrownBy(() -> new UserUseCaseImpl(userRepository).getOwnProfile(999))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateOwnProfileThrowsConflictOnVersionMismatch() {
        given(userRepository.updateProfile(7, "New Name", "New Bio", 0)).willReturn(WriteResult.VERSION_CONFLICT);
        var useCase = new UserUseCaseImpl(userRepository);

        assertThatThrownBy(() -> useCase.updateOwnProfile(7, new UpdateProfileRequest("New Name", "New Bio", 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateRoleThrowsNotFoundForAnUnknownUser() {
        given(userRepository.findById(999)).willReturn(Optional.empty());
        var useCase = new UserUseCaseImpl(userRepository);

        assertThatThrownBy(() -> useCase.updateRole(999, Role.ADMIN)).isInstanceOf(NotFoundException.class);
    }
}
