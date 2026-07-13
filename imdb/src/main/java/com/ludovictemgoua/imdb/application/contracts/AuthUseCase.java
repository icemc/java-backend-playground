package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.LoginRequest;
import com.ludovictemgoua.imdb.application.RegisterRequest;
import com.ludovictemgoua.imdb.application.TokenPair;

public interface AuthUseCase {

    TokenPair register(RegisterRequest request);

    TokenPair login(LoginRequest request);

    TokenPair refresh(String refreshToken);
}
