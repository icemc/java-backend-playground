package com.ludovictemgoua.imdb.presentation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// Guards the fix in tracing-design.md §1: RequestLoggingFilter's "request started"/"request
// completed" log lines are the two that bracket a request's entire lifecycle, and were the only
// ones NOT carrying a traceId (confirmed empirically against a live trace before the fix - everything
// logged from inside the request already got one via micrometer-tracing-bridge-otel's
// Slf4JEventListener, it was specifically these two that ran outside the span's scope due to filter
// ordering). A real MockMvc request through the full registered filter chain, not a mocked slice, is
// what actually exercises that ordering.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestTracingIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logbackLogger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger().detachAppender(appender);
    }

    private static Logger logbackLogger() {
        return (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    }

    @Test
    void requestStartedAndCompletedLogLinesBothCarryATraceId() throws Exception {
        mockMvc.perform(get("/api/v1/genres/Action/top-rated"));

        assertThat(appender.list)
                .as("RequestLoggingFilter should log both a start and a completion line")
                .hasSize(2);
        assertThat(appender.list).allSatisfy(event -> {
            String traceId = event.getMDCPropertyMap().get("traceId");
            assertThat(traceId).as("traceId on: %s", event.getFormattedMessage()).isNotBlank();
        });
    }
}
