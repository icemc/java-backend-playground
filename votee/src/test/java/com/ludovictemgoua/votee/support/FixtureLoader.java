package com.ludovictemgoua.votee.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.Rational;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Loads the JSON test fixtures ported from votee-scala. Kept in the test tree (not main) so the
 * Jackson dependency, and the Rational-from-JSON-number conversion it needs, never leak into the
 * library's own runtime classpath.
 */
public final class FixtureLoader {

    private static final ObjectMapper MAPPER = buildMapper();

    private FixtureLoader() {
    }

    public static List<PreferentialCandidate> candidates(String fileName) {
        return read(fileName, new TypeReference<List<PreferentialCandidate>>() {
        });
    }

    public static List<PreferentialBallot<PreferentialCandidate>> ballots(String fileName) {
        return read(fileName, new TypeReference<List<PreferentialBallot<PreferentialCandidate>>>() {
        });
    }

    private static <T> T read(String fileName, TypeReference<T> type) {
        String path = "/fixtures/" + fileName;
        try (InputStream in = FixtureLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found on classpath: " + path);
            }
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load fixture: " + path, e);
        }
    }

    private static ObjectMapper buildMapper() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Rational.class, new RationalDeserializer());
        return new ObjectMapper().registerModule(module);
    }

    /** Converts a plain JSON number (the fixtures only use integer ballot weights) into a Rational. */
    private static final class RationalDeserializer extends StdDeserializer<Rational> {

        private RationalDeserializer() {
            super(Rational.class);
        }

        @Override
        public Rational deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            BigDecimal value = parser.getDecimalValue();
            if (value.scale() <= 0) {
                return Rational.whole(value.longValueExact());
            }
            BigInteger denominator = BigInteger.TEN.pow(value.scale());
            return new Rational(value.unscaledValue(), denominator);
        }
    }
}
