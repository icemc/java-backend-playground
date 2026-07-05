# votee

A Java library of pluggable vote-counting algorithms for elections - a Java port of [votee-scala](../votee-scala), an existing Scala 3 library of mine implementing the same domain.

![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/build-Maven-blue)
![Status](https://img.shields.io/badge/status-in--development-yellow)

## What this is

Given a set of candidates and ballots, `votee` runs a chosen election algorithm (Majority, Approval, Borda Count, and so on) and returns the winner(s). Vote weights and scores are tracked as exact rationals rather than floating-point numbers, so tallies never drift due to rounding. Consumers can use the built-in `PreferentialCandidate`/`PreferentialBallot` types, or implement the `Candidate`/`Ballot` contracts themselves.

The full rationale behind every design decision in this port (why Java's generics need a different shape than Scala's, why `Rational` is hand-written instead of a dependency, why algorithms are iterative instead of recursive, and so on) is written up in:

- [`docs/product-design.md`](docs/product-design.md) - what is being built and why
- [`docs/low-level-design.md`](docs/low-level-design.md) - concrete class shapes, per-algorithm design, test plan, and build/publishing configuration

## Status

Domain model (`Candidate`, `Ballot`, `Election`, `TieResolver`, `Winner`, `Rational`) is implemented. Of the nine algorithms in the reference implementation:

- [x] Majority
- [ ] Super Majority
- [ ] Approval
- [ ] Veto
- [ ] Borda Count
- [ ] Baldwin
- [ ] Contingent Vote
- [ ] Coombs' Method
- [ ] Exhaustive Ballot

This list tracks the same nine algorithms `votee-scala` implements; see that project's own README for the longer list of voting methods neither library has implemented yet.

## Getting started

Requires JDK 21+ and Maven.

```
mvn test    # run the test suite
mvn package # build the jar
```

## Usage

```java
List<PreferentialCandidate> candidates = List.of(
        new PreferentialCandidate("a", "Alice"),
        new PreferentialCandidate("b", "Bob"),
        new PreferentialCandidate("c", "Carol")
);

List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
        PreferentialBallot.of(1, List.of(candidates.get(0), candidates.get(1), candidates.get(2))),
        PreferentialBallot.of(2, List.of(candidates.get(0), candidates.get(2), candidates.get(1))),
        PreferentialBallot.of(3, List.of(candidates.get(1), candidates.get(0), candidates.get(2)))
);

List<Winner<PreferentialCandidate>> winners = Majority.elect(ballots, candidates, 1);
```

`Majority.elect(...)` has an overload accepting an explicit `TieResolver<C>` (see `TieResolvers` for the built-in `doNothing`/`random`/`reverse` strategies) for callers who need to control how tied scores are broken; the two-argument overload above defaults to `TieResolvers.doNothing()`.

## Testing

Tests live under `src/test/java`, split into:

- `model/` - unit tests for the domain types (`Rational` arithmetic and reduction, `PreferentialBallot`'s `exclude`/`include`/immutability, the three `TieResolvers`)
- `algorithms/` - one test class per algorithm. `MajorityTest` covers a fixture-driven case (verified against the same JSON test data and expected winner as `votee-scala`'s own `MajoritySpec`) plus inline edge cases (an exact-half tie produces no winner; a clear majority wins)
- `support/FixtureLoader` - loads the JSON fixtures ported verbatim from `votee-scala/src/main/resources` into `src/test/resources/fixtures`, converting the plain JSON number for `weight` into a `Rational`. Kept test-scope-only (Jackson is a test dependency, not a runtime one) so the library itself stays dependency-free.

## Publishing

Coordinates: `com.ludovictemgoua:votee`, currently at `0.1.0-SNAPSHOT` (Early SemVer - see the PDD). Target registry is a private GitHub Packages Maven repository; the `pom.xml` `distributionManagement` block is already pointed at it. Maven Central remains a possible future upgrade, since the `com.ludovictemgoua` groupId already satisfies Central's domain-ownership requirement.

## Reference implementation

[`votee-scala`](../votee-scala) (`io.hiis.votee`) is the original Scala 3 library this port is based on, and is what every fixture-based test in this module is checked against for parity.
