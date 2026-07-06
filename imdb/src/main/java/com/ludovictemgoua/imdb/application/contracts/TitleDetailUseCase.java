package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.domain.model.TitleDetail;

public interface TitleDetailUseCase {

    TitleDetail getDetail(String titleId);
}
