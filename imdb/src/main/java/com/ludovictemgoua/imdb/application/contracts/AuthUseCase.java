package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.rest.LoginRequest;
import com.ludovictemgoua.imdb.application.rest.RegisterRequest;
import com.ludovictemgoua.imdb.application.rest.TokenPair;

public interface AuthUseCase {

    TokenPair register(RegisterRequest request);

    TokenPair login(LoginRequest request);

    TokenPair refresh(String refreshToken);
}
