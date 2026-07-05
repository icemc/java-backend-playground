# Votee (Java) - Low-Level Design Document

| | |
|---|---|
| Author | Ludovic Temgoua Abanda |
| Status | Draft |
| Date | 2026-07-04 |
| Related docs | votee/docs/product-design.md (PDD, approved) |
| Reference implementation | votee-scala (io.hiis.votee) |

## 1. Purpose and Scope

The PDD defines what is being built and why. This document defines how: concrete class shapes, method signatures, the algorithm-by-algorithm translation strategy, the Maven module layout, the publishing configuration, and the test plan. Anything that is a judgment call rather than a mechanical translation is called out explicitly with its rationale, so the reasoning survives even after the code is written.

This document is a design reference for manual implementation, not generated production code. Two or three algorithms are worked through in full as pattern examples; the rest are specified as pseudocode against those same patterns.

## 2. Maven Module Layout

```
votee/
  pom.xml
  docs/
    product-design.md
    low-level-design.md
  src/
    main/
      java/
        com/ludovictemgoua/votee/
          model/
            Candidate.java
            PreferentialCandidate.java
            Ballot.java
            PreferentialBallot.java
            Rational.java
            Election.java
            AbstractPreferentialElection.java
            TieResolver.java
            TieResolvers.java
            Winner.java
          algorithms/
            Majority.java
            SuperMajority.java
            Approval.java
            Veto.java
            BordaCount.java
            Baldwin.java
            Contingent.java
            Coombs.java
            ExhaustiveBallot.java
    test/
      java/
        com/ludovictemgoua/votee/
          algorithms/
            MajorityTest.java
            SuperMajorityTest.java
            ApprovalTest.java
            VetoTest.java
            BordaCountTest.java
            BaldwinTest.java
            ContingentTest.java
            CoombsTest.java
            ExhaustiveBallotTest.java
          support/
            FixtureLoader.java
      resources/
        fixtures/
          01-candidates.json
          01-ballots.json
          02-ballots.json
          03-ballots.json
```

Class naming note: the Scala source spells two algorithms `BaldWin` and `Coomb`. The Java port uses the conventionally capitalized `Baldwin` and the correctly spelled `Coombs`, since there is no external consumer depending on the old names yet (this is a new artifact, not a maintained public API).

## 3. Package Structure

Two packages under the root `com.ludovictemgoua.votee`:

- `com.ludovictemgoua.votee.model`: the domain contracts and value types (Candidate, Ballot, Election, TieResolver, Winner, Rational) plus their default implementations.
- `com.ludovictemgoua.votee.algorithms`: one class per voting algorithm, each a thin static entry point plus an instance-level implementation.

No sub-packages beyond this; nine algorithm classes in one package is small enough not to need further nesting.

## 4. Domain Model

### 4.1 Rational

Exact fraction type. Implemented as a Java record with a compact constructor that normalizes on construction, since records support validation and field reassignment in a compact constructor before the canonical fields are set.

```java
public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {

    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    public Rational {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Rational denominator cannot be zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        if (gcd.signum() != 0 && !gcd.equals(BigInteger.ONE)) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    public static Rational of(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Rational whole(long value) {
        return of(value, 1);
    }

    public Rational add(Rational other) { /* cross multiply, see below */ }
    public Rational subtract(Rational other) { return add(other.negate()); }
    public Rational multiply(Rational other) { /* numerator*numerator, denominator*denominator, let the constructor reduce */ }
    public Rational divide(Rational other) { /* multiply by other's reciprocal */ }
    public Rational negate() { return new Rational(numerator.negate(), denominator); }

    @Override
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }
}
```

Design notes:

- The constructor always reduces to lowest terms and keeps the denominator positive, so `equals`/`hashCode` (record-generated, field-based) are reliable for map keys and test assertions without a custom implementation.
- `BigInteger` gives arbitrary precision, so there is no overflow risk from repeated addition across many ballots, unlike a `long`-based fraction.
- No `doubleValue()` conversion is required anywhere in the algorithms themselves; it can be added later purely for display purposes if needed.

### 4.2 Candidate and PreferentialCandidate

```java
public interface Candidate {
    String id();
}

public record PreferentialCandidate(String id, String name, String party) implements Candidate {
    public PreferentialCandidate(String id, String name) {
        this(id, name, null);
    }
}
```

Design note: the Scala version models `party` as `Option[String]`. The Java port keeps `party` as a plain nullable `String` record component rather than `Optional<String>`, following the standard Java guidance against using `Optional` as a field or record component type. Callers who want an `Optional` wrap it at the call site: `Optional.ofNullable(candidate.party())`. This also sidesteps needing the extra Jackson module required to (de)serialize `Optional` fields when loading the JSON test fixtures.

### 4.3 Ballot and PreferentialBallot

Scala's `Ballot` uses a self-referential higher-kinded type parameter so `exclude`/`include` return the concrete ballot type. Java has no equivalent construct; the nearest idiomatic translation is the Curiously Recurring Generic Pattern (CRGP), per the PDD decision log:

```java
public interface Ballot<C extends Candidate, SELF extends Ballot<C, SELF>> {
    int id();
    Rational weight();
    List<C> preferences();
    SELF exclude(Collection<? extends C> candidates);
    SELF include(Collection<? extends C> candidates);
}

public record PreferentialBallot<C extends Candidate>(int id, Rational weight, List<C> preferences)
        implements Ballot<C, PreferentialBallot<C>> {

    public PreferentialBallot {
        preferences = List.copyOf(preferences);
    }

    public static <C extends Candidate> PreferentialBallot<C> of(int id, List<C> preferences) {
        return new PreferentialBallot<>(id, Rational.ONE, preferences);
    }

    @Override
    public PreferentialBallot<C> exclude(Collection<? extends C> candidates) {
        return new PreferentialBallot<>(id, weight, preferences.stream()
                .filter(c -> !candidates.contains(c))
                .toList());
    }

    @Override
    public PreferentialBallot<C> include(Collection<? extends C> candidates) {
        List<C> combined = new ArrayList<>(candidates);
        combined.addAll(preferences);
        return new PreferentialBallot<>(id, weight, combined);
    }
}
```

Design note: Scala's `include`/`exclude` signatures allow widening the candidate type (`CC >: C`), since Scala's collections are covariant. Java generics are invariant, so `include`/`exclude` here stay fixed at `C` (accepting `Collection<? extends C>`, not a supertype). This is an accepted deviation per the PDD non-goals: the library only ever operates on one concrete candidate type per election in practice, so this loss of flexibility has no real-world effect on the nine algorithms.

Also note `List.copyOf` in the compact constructor: this is where "immutable value types" from the PDD's non-functional requirements is actually enforced, since a caller could otherwise hand in a mutable `ArrayList` and mutate it after construction.

### 4.4 TieResolver and TieResolvers

```java
@FunctionalInterface
public interface TieResolver<C extends Candidate> {
    List<Map.Entry<C, Rational>> resolve(List<Map.Entry<C, Rational>> tiedScores);
}

public final class TieResolvers {
    private TieResolvers() {}

    public static <C extends Candidate> TieResolver<C> doNothing() {
        return tied -> tied;
    }

    public static <C extends Candidate> TieResolver<C> random() {
        return tied -> {
            List<Map.Entry<C, Rational>> shuffled = new ArrayList<>(tied);
            Collections.shuffle(shuffled);
            return shuffled;
        };
    }

    public static <C extends Candidate> TieResolver<C> reverse() {
        return tied -> {
            List<Map.Entry<C, Rational>> reversed = new ArrayList<>(tied);
            Collections.reverse(reversed);
            return reversed;
        };
    }
}
```

Design note: Scala expresses the three built-in resolvers as members of `Election.TieResolvers`, reached via `given`/`using` implicit resolution so a default is supplied automatically when the caller omits one. Java has no implicits; the Java port makes this explicit in two ways: `TieResolvers` is a plain static factory class, and every algorithm exposes a `run(...)` overload without a `TieResolver` parameter that forwards to the full overload with `TieResolvers.doNothing()`. This is a mechanical, low-risk translation (also called out as a risk in the PDD, section 11).

### 4.5 Winner

`Winner` is an interface, not a record directly, following the same contract-plus-default-implementation shape as `Candidate`/`PreferentialCandidate` and `Ballot`/`PreferentialBallot`. This was a deliberate revision after initial implementation: a record would have forced every consumer into exactly the (candidate, score) pair, with no way to carry extra fields (rank, margin, district, and so on) without wrapping. As an interface, a consumer can implement `Winner<C>` directly for a richer result type instead.

```java
public interface Winner<C extends Candidate> {
    C candidate();
    Rational score();

    static <C extends Candidate> Winner<C> of(Map.Entry<C, Rational> entry) {
        return new PreferentialWinner<>(entry.getKey(), entry.getValue());
    }
}

public record PreferentialWinner<C extends Candidate>(C candidate, Rational score) implements Winner<C> {
}
```

The built-in algorithms always construct `PreferentialWinner` internally via `Winner.of(...)`; they don't expose a way to plug in a custom `Winner` implementation as the algorithm's own output type. The extensibility here is for consumers who want to adapt or wrap an algorithm's result into their own richer type downstream, not for parameterizing the algorithms themselves over `W`.

### 4.6 Election and AbstractPreferentialElection

```java
public interface Election<C extends Candidate, B extends Ballot<C, B>, W> {
    List<W> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver);

    default List<W> run(List<B> ballots, List<C> candidates, int vacancies) {
        return run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }
}

public abstract class AbstractPreferentialElection<C extends Candidate, B extends Ballot<C, B>>
        implements Election<C, B, Winner<C>> {

    protected static final Rational MAJORITY_THRESHOLD = Rational.of(1, 2);

    protected final List<Map.Entry<C, Rational>> resolveTies(
            List<Map.Entry<C, Rational>> sortedScores, TieResolver<C> tieResolver) {
        List<Map.Entry<C, Rational>> result = new ArrayList<>();
        int i = 0;
        while (i < sortedScores.size()) {
            Rational score = sortedScores.get(i).getValue();
            int j = i;
            while (j < sortedScores.size() && sortedScores.get(j).getValue().equals(score)) {
                j++;
            }
            result.addAll(tieResolver.resolve(sortedScores.subList(i, j)));
            i = j;
        }
        return result;
    }

    protected final Map<C, Rational> countFirstVotes(List<B> ballots, List<C> candidates) {
        return countPreference(ballots, candidates, List::getFirst);
    }

    protected final Map<C, Rational> countLastVotes(List<B> ballots, List<C> candidates) {
        return countPreference(ballots, candidates, List::getLast);
    }

    private Map<C, Rational> countPreference(
            List<B> ballots, List<C> candidates, Function<List<C>, C> pick) {
        Map<C, Rational> scores = new LinkedHashMap<>();
        for (B ballot : ballots) {
            List<C> valid = ballot.preferences().stream().filter(candidates::contains).toList();
            if (!valid.isEmpty()) {
                C candidate = pick.apply(valid);
                scores.merge(candidate, ballot.weight(), Rational::add);
            }
        }
        return scores;
    }
}
```

Design notes:

- `Election` stays an interface (the public contract, matching the Scala `Election` trait). `AbstractPreferentialElection` is an abstract class rather than an interface, because it needs `protected` helper methods (`resolveTies`, `countFirstVotes`, `countLastVotes`) that are implementation detail, not part of the public contract. Java interfaces cannot have `protected` members, so an abstract class is the correct tool here, not a design compromise.
- `countFirstVotes`/`countLastVotes` are unified into one private `countPreference` helper parameterized by which end of the (filtered) preference list to pick, since the Scala versions are identical except for `find` vs `findLast`. This is a small simplification beyond a literal translation, justified because both call sites in the reference are otherwise copy-pasted.
- The accumulator map is a `LinkedHashMap`, not `HashMap`. This is a deliberate choice, not an oversight: see section 6 below on determinism.

## 5. Algorithm Implementations

### 5.1 Shape summary

| Algorithm | Shape | Notes |
|---|---|---|
| Majority | A: threshold filter | Fixed threshold of one half |
| Super Majority | A: threshold filter | Same as Majority, threshold is a constructor/parameter argument |
| Approval | B: full tally and rank | Every listed preference scores a full vote |
| Veto | B: full tally and rank | Every preference except a voter's last choice scores a point |
| Borda Count | B: full tally and rank | Score by rank position, weighted |
| Coombs' Method | C: iterative elimination | Eliminate the most-last-ranked candidate each round |
| Baldwin | C: iterative elimination | Eliminate the lowest Borda scorer each round |
| Exhaustive Ballot | C: iterative elimination | Eliminate the lowest first-preference scorer each round |
| Contingent Vote | D: single runoff | One elimination round, not iterated |

Each algorithm class follows the same static entry point pattern used throughout the Scala reference's companion objects, adapted to Java:

```java
public final class Majority<C extends Candidate, B extends Ballot<C, B>>
        extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> run(
            List<B> ballots, List<C> candidates, int vacancies) {
        return run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> run(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Majority<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        Rational threshold = Rational.whole(ballots.size()).multiply(MAJORITY_THRESHOLD);
        List<Map.Entry<C, Rational>> sorted = countFirstVotes(ballots, candidates).entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
        return resolveTies(sorted, tieResolver).stream()
                .filter(e -> e.getValue().compareTo(threshold) > 0)
                .limit(vacancies)
                .map(Winner::of)
                .toList();
    }
}
```

This gives every algorithm two static overloads (with and without an explicit `TieResolver`) plus one instance method carrying the actual logic, mirroring `object Majority { def run(...) = new Majority[C, B]{}.run(...) }` from the Scala source, but without needing an anonymous class since `AbstractPreferentialElection` is directly instantiable here.

### 5.2 Worked example: Approval (shape B)

```java
@Override
public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
    Map<C, Rational> scores = new LinkedHashMap<>();
    for (B ballot : ballots) {
        for (C candidate : ballot.preferences()) {
            scores.merge(candidate, ballot.weight(), Rational::add);
        }
    }
    List<Map.Entry<C, Rational>> sorted = scores.entrySet().stream()
            .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
            .toList();
    return resolveTies(sorted, tieResolver).stream()
            .limit(vacancies)
            .map(Winner::of)
            .toList();
}
```

Veto follows the same shape, scoring every preference on a ballot except the last one (guarding the single-preference-ballot edge case the same way the Scala source does: a ballot with exactly one preference does not veto it). Borda Count also follows this shape, but the inner loop scores `candidates.size() - 1 - index` points per ranked position instead of a flat 1 point, and filters preferences down to the currently eligible `candidates` list first (relevant once Borda scoring is reused inside Baldwin's elimination loop).

### 5.3 Worked example: Coombs' Method (shape C, iterative elimination)

The Scala version is written as `@tailrec` self-recursion. The JVM does not guarantee tail-call optimization for javac-compiled bytecode the way Scala's compiler verifies and rewrites `@tailrec` methods into loops at compile time, so a literal recursive translation risks a `StackOverflowError` on a large enough candidate list. Every algorithm in shape C is written as an explicit `while` loop in Java instead of recursion. This is a deliberate, LLD-level decision, not just a style preference.

```java
@Override
public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
    List<C> remaining = new ArrayList<>(candidates);
    while (!remaining.isEmpty()) {
        Map<C, Rational> firstVotes = countFirstVotes(ballots, remaining);
        Rational majorityThreshold = MAJORITY_THRESHOLD.multiply(Rational.whole(ballots.size()));
        List<Map.Entry<C, Rational>> overMajority = firstVotes.entrySet().stream()
                .filter(e -> e.getValue().compareTo(majorityThreshold) > 0)
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
        if (!overMajority.isEmpty()) {
            return resolveTies(overMajority, tieResolver).stream().limit(1).map(Winner::of).toList();
        }
        Map<C, Rational> lastVotes = countLastVotes(ballots, remaining);
        List<Map.Entry<C, Rational>> sortedLast = lastVotes.entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
        C mostLastRanked = resolveTies(sortedLast, tieResolver).get(0).getKey();
        remaining.remove(mostLastRanked);
    }
    return List.of();
}
```

Baldwin follows the same while-loop shape but eliminates the lowest Borda scorer each round and stops once one candidate remains (returning it directly, matching the Scala base case). Exhaustive Ballot follows the same shape, eliminating the lowest first-preference scorer via `ballot.exclude(...)` each round (this is the one place `Ballot.exclude` is actually exercised) and stopping once two candidates remain.

### 5.4 Worked example: Contingent Vote (shape D, single runoff)

```java
@Override
public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
    Map<C, Rational> scores = new LinkedHashMap<>(countFirstVotes(ballots, candidates));
    List<Map.Entry<C, Rational>> sorted = resolveTies(scores.entrySet().stream()
            .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
            .toList(), tieResolver);

    if (sorted.get(0).getValue().compareTo(MAJORITY_THRESHOLD) > 0) {
        return List.of(Winner.of(sorted.get(0)));
    }

    List<C> topTwo = sorted.stream().limit(2).map(Map.Entry::getKey).toList();
    for (B ballot : ballots) {
        if (!topTwo.contains(ballot.preferences().getFirst())) {
            ballot.preferences().stream()
                    .filter(topTwo::contains)
                    .findFirst()
                    .ifPresent(candidate -> scores.merge(candidate, ballot.weight(), Rational::add));
        }
    }

    List<Map.Entry<C, Rational>> finalRound = resolveTies(scores.entrySet().stream()
            .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
            .toList(), tieResolver);
    return List.of(Winner.of(finalRound.get(0)));
}
```

## 6. Determinism and Tie-Break Ordering

The PDD (section 6.2/9) defines parity as identical winner output for identical input, not identical internal iteration order. That distinction matters here: Scala's `mutable.HashMap` iteration order depends on the case class's Scala-generated `hashCode` (MurmurHash3-based), which has no equivalent in Java's record-generated `hashCode`. If two candidates end up exactly tied on score and no `TieResolver` breaks the tie deterministically by content, the two implementations could order them differently even though each is internally consistent.

Mitigation used throughout section 5: every accumulator map is a `LinkedHashMap`, not a `HashMap`, so iteration order always matches ballot-processing order (first-seen order) rather than an unspecified hash-bucket order. Combined with `Stream.sorted`, which is documented as a stable sort, this makes the Java port's own output deterministic and reproducible across runs. It does not guarantee bit-identical ordering to the Scala side in a tie, but the fixture data checked in section 7.1 below produces no ties under `TieResolvers.doNothing()`, so this has no effect on the parity tests defined for this pass. It is recorded here so it is not rediscovered as a surprise if new fixture data introduces a real tie later.

## 7. Test Plan

### 7.1 Fixture files and expected winners

All fixture files are ported verbatim from `votee-scala/src/main/resources` into `votee/src/test/resources/fixtures`. `01-candidates.json` (candidates a, b, c, d) is used by every test. `02-ballots.json` is ported for parity even though no existing Scala spec currently exercises it.

| Algorithm | Ballots file | Expected winner(s) |
|---|---|---|
| Majority | 03-ballots.json | a |
| Super Majority (threshold 6/10) | 03-ballots.json | (none) |
| Approval | 03-ballots.json | d |
| Veto | 03-ballots.json | d |
| Borda Count | 03-ballots.json | a |
| Baldwin | 03-ballots.json | a |
| Contingent Vote | 03-ballots.json | a |
| Coombs' Method | 03-ballots.json | a |
| Exhaustive Ballot | 01-ballots.json | b |

These are the same fixture-to-expected-winner pairs already proven correct by the existing `votee-scala` test suite; the Java tests assert the exact same pairs.

### 7.2 Fixture loading utility

```java
public final class FixtureLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FixtureLoader() {}

    public static List<PreferentialCandidate> candidates(String fileName) throws IOException {
        try (InputStream in = FixtureLoader.class.getResourceAsStream("/fixtures/" + fileName)) {
            return MAPPER.readValue(in, new TypeReference<List<PreferentialCandidate>>() {});
        }
    }

    public static List<PreferentialBallot<PreferentialCandidate>> ballots(String fileName) throws IOException {
        try (InputStream in = FixtureLoader.class.getResourceAsStream("/fixtures/" + fileName)) {
            return MAPPER.readValue(in, new TypeReference<List<PreferentialBallot<PreferentialCandidate>>>() {});
        }
    }
}
```

This mirrors the Scala `Parser` utility's role, using Jackson instead of `play-json` (a test-scope-only dependency, per the PDD). Ballot deserialization needs a small custom Jackson module or a `@JsonCreator` constructor on `PreferentialBallot`, since the JSON's `weight` field is a plain number and needs converting into a `Rational` rather than the record's own `BigInteger`-pair shape; this is flagged here as an implementation detail to work out with a `@JsonCreator`-annotated static factory rather than the canonical constructor.

### 7.3 Test class pattern

One JUnit 5 test class per algorithm, following:

```java
class MajorityTest {

    @Test
    void picksTheFirstPreferenceMajorityWinner() throws IOException {
        var candidates = FixtureLoader.candidates("01-candidates.json");
        var ballots = FixtureLoader.ballots("03-ballots.json");

        var winners = Majority.run(ballots, candidates, 1);

        assertThat(winners).extracting(Winner::candidate)
                .containsExactly(candidates.stream().filter(c -> c.id().equals("a")).findFirst().orElseThrow());
    }
}
```

AssertJ is used for the fluent assertion style shown above; this needs to be added as a test-scope dependency alongside JUnit 5 and Jackson.

## 8. Build and Publishing Configuration

Indicative `pom.xml` shape (exact plugin versions to be filled in at implementation time):

```xml
<project>
    <groupId>com.ludovictemgoua</groupId>
    <artifactId>votee</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <distributionManagement>
        <repository>
            <id>github</id>
            <name>GitHub Packages</name>
            <url>https://maven.pkg.github.com/icemc/votee</url>
        </repository>
    </distributionManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-source-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Notes:

- The `github` server id in `distributionManagement` must have matching credentials in the local Maven `settings.xml` (`<server><id>github</id><username>...</username><password>${env.GITHUB_TOKEN}</password></server>`), the same pattern the Scala project already documents in its own README for `sbt-github-packages`.
- `distributionManagement` URL assumes the Java port is pushed to its own repository at `github.com/icemc/votee`; adjust the path if the artifact instead publishes under a different repository name.
- Starting version is `0.1.0-SNAPSHOT` during active development; the first `mvn deploy` that is meant to be consumed drops the `-SNAPSHOT` suffix per the Early SemVer scheme in the PDD (section 8.1).

## 9. Deviations from the Scala Reference

Consolidated list of every place this design deliberately departs from a literal translation, for quick review:

1. Ballot generics use the Curiously Recurring Generic Pattern instead of Scala's higher-kinded self-type (PDD decision log).
2. `Candidate.party` is a nullable `String`, not `Optional<String>` (section 4.2).
3. `Ballot.exclude`/`include` are invariant in `C`, not covariant-widening like the Scala version (section 4.3).
4. Tie-resolver defaults are explicit method overloads, not implicit `given`/`using` parameters (section 4.4).
5. `countFirstVotes`/`countLastVotes` share one private helper instead of two near-identical methods (section 4.6).
6. Recursive (`@tailrec`) algorithms are rewritten as `while` loops, since javac gives no tail-call guarantee (section 5.3).
7. Score accumulators use `LinkedHashMap` for deterministic iteration order, rather than relying on hash-bucket order (section 6).
8. Two algorithm names are corrected in casing/spelling: `BaldWin` becomes `Baldwin`, `Coomb` becomes `Coombs` (section 2).

## 10. Open Items for Implementation

- Exact Jackson binding strategy for `Rational` and `PreferentialBallot.weight` (custom deserializer vs. `@JsonCreator`) needs to be finalized once implementation starts; section 7.2 flags the shape of the problem but not the final code.
- Plugin versions in the `pom.xml` skeleton (section 8) are left unpinned; fill in current stable versions at implementation time rather than pinning them in a design document that may go stale.
- Whether `Rational` also needs a `toBigDecimal(MathContext)` convenience method for any future display/reporting use case outside the nine algorithms. Not required by anything in this design; add only if a concrete need shows up.
