package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.PathStep;
import com.ludovictemgoua.imdb.application.PersonRef;
import com.ludovictemgoua.imdb.application.SixDegreesResult;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PersonController.class)
@WithSecurityConfig
class PersonControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    SixDegreesUseCase sixDegreesUseCase;
    @MockitoBean
    PersonAdminUseCase personAdminUseCase;

    @Test
    void rejectsMaxDegreeAboveSeven() throws Exception {
        mockMvc.perform(get("/api/v1/people/six-degrees")
                        .param("personA", "nm0000102")
                        .param("personB", "nm0000158")
                        .param("maxDegree", "9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsTheResultWhenFound() throws Exception {
        var personA = new PersonRef("nm0000102", "Kevin Bacon");
        var personB = new PersonRef("nm0000158", "Tom Hanks");
        var result = new SixDegreesResult(personA, personB, 2, true,
                List.of(new PathStep("nm0000102", "Kevin Bacon", null)));
        given(sixDegreesUseCase.compute("nm0000102", "nm0000158", 7))
                .willReturn(new SixDegreesOutcome.Found(result));

        mockMvc.perform(get("/api/v1/people/six-degrees")
                        .param("personA", "nm0000102")
                        .param("personB", "nm0000158"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degree").value(2));
    }

    @Test
    void returnsDisambiguationPayloadWith200WhenAmbiguous() throws Exception {
        given(sixDegreesUseCase.compute("Jamie Lee", "nm0000158", 7))
                .willReturn(new SixDegreesOutcome.Ambiguous("Jamie Lee", List.of(
                        new PersonCandidate("nm0000020", "Jamie Lee", 1975, List.of()),
                        new PersonCandidate("nm0000021", "Jamie Lee", 1990, List.of()))));

        mockMvc.perform(get("/api/v1/people/six-degrees")
                        .param("personA", "Jamie Lee")
                        .param("personB", "nm0000158"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresDisambiguation").value(true))
                .andExpect(jsonPath("$.candidates.length()").value(2));
    }

    @Test
    void returns404WhenPersonNotFound() throws Exception {
        given(sixDegreesUseCase.compute("Nobody", "nm0000158", 7))
                .willReturn(new SixDegreesOutcome.PersonNotFound("Nobody"));

        mockMvc.perform(get("/api/v1/people/six-degrees")
                        .param("personA", "Nobody")
                        .param("personB", "nm0000158"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns504WhenTheQueryTimesOut() throws Exception {
        given(sixDegreesUseCase.compute("nm0000102", "nm3937654", 7))
                .willThrow(new QueryTimeoutException("canceling statement due to user request"));

        mockMvc.perform(get("/api/v1/people/six-degrees")
                        .param("personA", "nm0000102")
                        .param("personB", "nm3937654"))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    void createPersonRequiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/people")
                        .with(user("1").roles("USER"))
                        .contentType("application/json")
                        .content("""
                                {"primaryName":"New Person","primaryProfession":[]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPersonSucceedsForAdmin() throws Exception {
        var created = new PersonCore("nm0000011", "New Person", null, null, List.of(), 0);
        given(personAdminUseCase.create(any())).willReturn(created);

        mockMvc.perform(post("/api/v1/people")
                        .with(user("1").roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {"primaryName":"New Person","primaryProfession":[]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("nm0000011"));
    }
}
