# votee

A Java library of pluggable vote-counting algorithms for elections - a Java port of [votee-scala](../votee-scala), an existing Scala 3 library of mine implementing the same domain.

[![votee CI](https://github.com/icemc/java-backend-playground/actions/workflows/votee-ci.yml/badge.svg)](https://github.com/icemc/java-backend-playground/actions/workflows/votee-ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/build-Maven-blue)
![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-lightgrey)
![License](https://img.shields.io/badge/license-Apache--2.0-green)

## Table of contents

- [What this is](#what-this-is)
- [Algorithms](#algorithms)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [Extending the library](#extending-the-library)
- [Known deviations from the Scala reference](#known-deviations-from-the-scala-reference)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Continuous integration](#continuous-integration)
- [Versioning and publishing](#versioning-and-publishing)
- [Design documents](#design-documents)
- [Reference implementation](#reference-implementation)
- [License](#license)

## What this is

Given a set of candidates and ballots, `votee` runs a chosen election algorithm and returns the winner(s). Vote weights and scores are tracked as exact rationals rather than floating-point numbers, so tallies never drift due to rounding. Consumers can use the built-in `PreferentialCandidate`/`PreferentialBallot` types, or implement the `Candidate`/`Ballot` contracts themselves.

All nine vote-counting algorithms implemented by the Scala reference are implemented here too, each checked for parity against the same JSON test fixtures the Scala test suite uses.

## Algorithms

| Algorithm | Entry point | Vacancies honored | Notes |
|---|---|---|---|
| [Majority](https://en.wikipedia.org/wiki/Majority_rule) | `Majority.elect(...)` | Yes | Winner needs strictly more than half the first-preference votes |
| [Super Majority](https://en.wikipedia.org/wiki/Supermajority) | `SuperMajority.elect(...)` | Yes | Like Majority, against a configurable threshold in `[1/2, 1]` |
| [Approval](https://en.wikipedia.org/wiki/Approval_voting) | `Approval.elect(...)` | Yes | Every listed preference on a ballot counts as a full vote |
| Veto | `Veto.elect(...)` | Yes | Every preference on a ballot scores a point except the voter's last choice |
| [Borda Count](https://en.wikipedia.org/wiki/Borda_count) | `BordaCount.elect(...)` | Yes | Candidates score points by rank position, weighted by ballot weight |
| [Baldwin](https://en.wikipedia.org/wiki/Nanson%27s_method#Baldwin_method) | `Baldwin.elect(...)` | No - always 1 winner | Repeated Borda-Count elimination of the lowest scorer each round |
| [Contingent Vote](https://en.wikipedia.org/wiki/Contingent_vote) | `Contingent.elect(...)` | No - always 1 winner | Top-two runoff with redistributed ballots |
| [Coombs' Method](https://en.wikipedia.org/wiki/Coombs%27_method) | `Coombs.elect(...)` | No - always 1 winner | Repeated elimination of the most-last-ranked candidate |
| [Exhaustive Ballot](https://en.wikipedia.org/wiki/Exhaustive_ballot) | `ExhaustiveBallot.elect(...)` | No - always 1 winner | Repeated elimination of the lowest first-preference scorer |

Every `elect(...)` method has an overload taking an explicit `TieResolver<C>` and one that defaults to `TieResolvers.doNothing()`; see [Core concepts](#core-concepts).

## Requirements

- JDK 21 or later
- Maven 3.9+

## Installation

`votee` is published to a private GitHub Packages Maven registry (see [Versioning and publishing](#versioning-and-publishing)). To consume it from another Maven project:

1. Add the repository to your `pom.xml`:

   ```xml
   <repositories>
       <repository>
           <id>github</id>
           <url>https://maven.pkg.github.com/icemc/votee</url>
       </repository>
   </repositories>
   ```

2. Add the dependency:

   ```xml
   <dependency>
       <groupId>com.ludovictemgoua</groupId>
       <artifactId>votee</artifactId>
       <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```

3. GitHub Packages requires authentication even for reads. Create a personal access token with `read:packages` scope, then add a matching server entry to your `~/.m2/settings.xml`:

   ```xml
   <servers>
       <server>
           <id>github</id>
           <username>YOUR_GITHUB_USERNAME</username>
           <password>${env.GITHUB_TOKEN}</password>
       </server>
   </servers>
   ```

   and set the `GITHUB_TOKEN` environment variable before running Maven.

## Quick start

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

Any of the nine algorithm classes in the table above can be substituted for `Majority` with the same call shape. To control how tied scores are broken, pass a `TieResolver` explicitly:

```java
List<Winner<PreferentialCandidate>> winners =
        BordaCount.elect(ballots, candidates, 1, TieResolvers.reverse());
```

## Core concepts

| Type | Role |
|---|---|
| `Candidate` | Contract for anything that can appear on a ballot (just an `id()`). |
| `PreferentialCandidate` | Built-in `Candidate`: `id`, `name`, and an optional `party` (a plain nullable field, not `Optional`, following standard Java field guidance). |
| `Ballot<C, SELF>` | Contract for a voter's submitted preferences: an `id`, a `weight`, an ordered `preferences()` list, and `exclude`/`include` for filtering candidates. The `SELF` type parameter is a Curiously Recurring Generic Pattern so `exclude`/`include` return the concrete ballot type, not the interface. |
| `PreferentialBallot<C>` | Built-in `Ballot`. Defensively copies its `preferences` list on construction, so it's genuinely immutable even if the caller mutates the list they passed in. |
| `Rational` | Exact fraction type (`BigInteger` numerator/denominator, reduced to lowest terms on construction). Used for every vote weight and score so tallies never drift the way floating-point sums can. |
| `TieResolver<C>` | Strategy for ordering candidates tied on score. Built-in strategies live in `TieResolvers`: `doNothing()` (deterministic, order-preserving), `random()` (shuffle), `reverse()`. |
| `Election<C, B, W>` / `AbstractPreferentialElection<C, B>` | The algorithm contract, and a base class providing shared helpers (`countFirstVotes`, `countLastVotes`, `resolveTies`) that every algorithm builds on. |
| `Winner<C>` | A candidate paired with their final score. |

## Extending the library

Bring your own candidate or ballot type by implementing the contracts directly, instead of subclassing the built-in defaults:

```java
public record Voter(String id, String district) implements Candidate {}

public record RankedBallot(int id, Rational weight, List<Voter> preferences)
        implements Ballot<Voter, RankedBallot> {

    @Override
    public RankedBallot exclude(Collection<? extends Voter> voters) { /* ... */ }

    @Override
    public RankedBallot include(Collection<? extends Voter> voters) { /* ... */ }
}
```

Every algorithm is generic over `<C extends Candidate, B extends Ballot<C, B>>`, so `Majority.<Voter, RankedBallot>elect(ballots, voters, seats)` works without any change to the algorithm classes themselves.

## Known deviations from the Scala reference

Ported behavior generally matches `votee-scala` exactly, but a few places deliberately diverge. Each is called out with a code comment at its call site too:

- **Ballot generics** use the Curiously Recurring Generic Pattern instead of Scala's higher-kinded self-type, since Java can't express `Ballot[+C <: Candidate, +T[+CC >: C <: Candidate] <: Ballot[CC, T]]` directly.
- **`Contingent`'s majority check compares the leader's raw score to a flat `1/2`**, not to half of `ballots.size()` (unlike `Majority`/`Coombs`, which do scale it) - this matches the Scala reference exactly, but means the runoff/redistribution branch is effectively unreachable with normal integer ballot weights. Under active review; not yet changed in either implementation.
- **`ExhaustiveBallot` resolves ties via the given `TieResolver`** at both the elimination and final-winner steps. The Scala reference accepts a `tieResolver` parameter but never actually uses it, deciding both steps by incidental sort order instead. This port intentionally does **not** replicate that specific behavior.
- **`Approval`/`Veto` on the ported test fixtures can legitimately tie** (every fixture ballot ranks all candidates, so `Approval` degenerates into a full N-way tie). Which candidate a tie resolves to first differs between Scala's hash-bucket map iteration and Java's insertion-ordered one; the test suite asserts scores, not winner identity, in that case. See the LLD's "Determinism and Tie-Break Ordering" section.
- **`Baldwin`, `Contingent`, `Coombs`, and `ExhaustiveBallot` ignore the `vacancies` parameter**, matching the Scala reference - each of these is structurally a single-winner algorithm.
- Score accumulators use `LinkedHashMap` (ballot-processing order) rather than relying on hash-bucket order, for reproducible results across runs.

## Building from source

```
mvn compile  # compile main sources
mvn test     # run the test suite
mvn package  # build the jar (main + sources)
```

Project layout:

```
votee/
  pom.xml
  LICENSE
  docs/                     PDD and LLD
  src/main/java/.../model/       domain contracts and value types
  src/main/java/.../algorithms/  one class per voting algorithm
  src/test/java/.../model/       unit tests for the domain types
  src/test/java/.../algorithms/  one test class per algorithm
  src/test/java/.../support/     FixtureLoader (test-scope-only JSON loading)
  src/test/resources/fixtures/   JSON fixtures ported verbatim from votee-scala
```

## Testing

46 tests across two areas:

- `model/` - unit tests for the domain types: `Rational` arithmetic and reduction, `PreferentialBallot`'s `exclude`/`include`/immutability, the three `TieResolvers` strategies.
- `algorithms/` - one test class per algorithm. Each has a fixture-driven case verified against the same JSON test data and expected winner as the corresponding `votee-scala` spec, plus hand-verified inline edge cases (majority/supermajority threshold boundaries, multi-round elimination, tie-resolver-sensitive outcomes, and so on).

`support/FixtureLoader` loads the JSON fixtures ported verbatim from `votee-scala/src/main/resources` into `src/test/resources/fixtures`, converting the plain JSON number for `weight` into a `Rational` via a small Jackson module. Jackson is a test-scope-only dependency - it never appears on the library's runtime classpath.

## Continuous integration

[`votee-ci.yml`](../.github/workflows/votee-ci.yml) runs `mvn test` on every push and pull request that touches this module (path-filtered to `votee/**`, so unrelated changes elsewhere in the monorepo don't trigger it).

## Versioning and publishing

Coordinates: `com.ludovictemgoua:votee`, currently at `0.1.0-SNAPSHOT`. Versioning follows Early SemVer (SemVer 2.0.0's own "major version zero" clause): breaking changes bump the minor version, backward-compatible changes bump the patch version, until the API is declared stable at `1.0.0`.

Target registry is a private GitHub Packages Maven repository; the `pom.xml` `distributionManagement` block is already pointed at it. Maven Central remains a possible future upgrade, since the `com.ludovictemgoua` groupId already satisfies Central's domain-ownership requirement.

## Design documents

- [`docs/product-design.md`](docs/product-design.md) - what is being built and why
- [`docs/low-level-design.md`](docs/low-level-design.md) - concrete class shapes, per-algorithm design, test plan, and build/publishing configuration

## Reference implementation

[`votee-scala`](../votee-scala) (`io.hiis.votee`) is the original Scala 3 library this port is based on, and is what every fixture-based test in this module is checked against for parity.

## License

Apache License 2.0 - see [`LICENSE`](LICENSE), matching the reference implementation's license.
