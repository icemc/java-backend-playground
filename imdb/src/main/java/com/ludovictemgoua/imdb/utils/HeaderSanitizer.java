package com.ludovictemgoua.imdb.utils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HeaderSanitizer {

    private static final String REDACTED = "***REDACTED***";

    // This API has no auth today, but request headers get logged wholesale by RequestLoggingFilter -
    // redacting known-sensitive ones here means that logging is already safe the moment auth (or any
    // header carrying a secret) is added, rather than something to remember to retrofit later.
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "cookie", "set-cookie", "x-api-key");

    private HeaderSanitizer() {
    }

    public static Map<String, String> sanitize(Map<String, String> headers) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            boolean sensitive = SENSITIVE_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT));
            sanitized.put(entry.getKey(), sensitive ? REDACTED : entry.getValue());
        }
        return sanitized;
    }
}
