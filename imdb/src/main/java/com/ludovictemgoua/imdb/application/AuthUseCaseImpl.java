package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.AuthUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.infrastructure.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AuthUseCaseImpl implements AuthUseCase {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthUseCaseImpl(UserRepository userRepository, JwtService jwtService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public TokenPair register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with this email already exists");
        }
        String hash = passwordEncoder.encode(request.password());
        User user = userRepository.insert(request.email(), hash, request.displayName(), Role.USER);
        return issueTokens(user);
    }

    @Override
    public TokenPair login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ForbiddenException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new ForbiddenException("Invalid email or password");
        }
        return issueTokens(user);
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        var parsed = jwtService.parse(refreshToken)
                .orElseThrow(() -> new ForbiddenException("Invalid or expired refresh token"));
        User user = userRepository.findById(parsed.userId())
                .orElseThrow(() -> new ForbiddenException("Invalid or expired refresh token"));
        return issueTokens(user);
    }

    private TokenPair issueTokens(User user) {
        String access = jwtService.issueAccessToken(user.id(), Set.of(user.role()));
        String refresh = jwtService.issueRefreshToken(user.id());
        return new TokenPair(access, refresh);
    }
}
