# Votee (Java) - Product Design Document

| | |
|---|---|
| **Author** | Ludovic Temgoua Abanda |
| **Status** | Draft |
| **Date** | 2026-07-04 |
| **Related docs** | `votee/docs/low-level-design.md` (follow-up, not yet written) |
| **Reference implementation** | [`votee-scala`](https://github.com/icemc/votee) (`com.ludovictemgoua.votee`, github.com/icemc/Votee) |

## 1. Overview

`votee` is a Java library of pluggable vote-counting algorithms for elections. It is a Java port of `votee-scala`, an existing Scala 3 library of mine that implements the same domain. The goal of this port is behavioral parity with the Scala original, expressed in idiomatic Java rather than a mechanical line-by-line translation - every place where Scala and Java diverge in what's idiomatic is a deliberate design decision, not an accident.

This document defines *what* is being built and *why*. The follow-up Low-Level Design document defines *how* - concrete class shapes, method signatures, and algorithm-by-algorithm implementation notes.

## 2. Background

`votee-scala` implements nine election-counting methods (Majority, Super Majority, Approval, Veto, Borda Count, Baldwin, Contingent Vote, Coombs' Method, Exhaustive Ballot) against a small, extensible domain model: a `Candidate`, a `Ballot` of ranked or weighted `Candidate` preferences, and an `Election` contract that turns a set of ballots into a list of `Winner`s. Vote weights and scores are tracked as exact rationals (via `spire.math.Rational`) rather than floating-point numbers, since tallies must never drift due to rounding.

The library was designed to be extended by consumers: they can supply their own `Candidate` and `Ballot` implementations, or rely on the built-in `PreferentialCandidate` / `PreferentialBallot` defaults. This extensibility is a first-class requirement carried over into the Java port.

## 3. Goals

- Full behavioral parity with `votee-scala` for all nine currently-implemented algorithms: given the same candidates and ballots, the Java port produces the same winners.
- An idiomatic Java 21 API - a Java developer reading this library should not feel like they're reading translated Scala.
- The same extensibility story as the original: default implementations (`PreferentialCandidate`, `PreferentialBallot`) provided out of the box, with contracts (`Candidate`, `Ballot`, `Election`) that consumers can implement themselves.
- Exact rational arithmetic for all vote tallying - no floating-point rounding error in scores or thresholds.
- Test parity: the same known-good JSON fixtures used by `votee-scala` validate the Java port, so both implementations are provably checking the same cases.
- Ship as a fully publishable Maven library - versioned, packaged (main, sources, and javadoc jars), and deployed to a private GitHub Packages registry. This is a deliberate exercise of the full library-release workflow, not just the algorithm code (see §8.1).

## 4. Non-Goals

- Replicating Scala's variance and higher-kinded type model exactly. Java's type system can't express `Ballot[+C <: Candidate, +T[+CC >: C <: Candidate] <: Ballot[CC, T]]` directly; the Java port uses the nearest idiomatic equivalent (see §9) rather than fighting the type system for 1:1 fidelity.
- Implementing the algorithms still on `votee-scala`'s own TODO list (Instant Runoff 2-Round, Kemeny-Young, Minimax Condorcet, Nanson, Oklahoma, PAV, Preferential Block Voting, Random Ballot, SAV). These aren't implemented in the reference either, so there's nothing to port yet - tracked as future work in both libraries.
- A REST or CLI wrapper around the library. This is a library-only port, matching the current scope of `votee-scala`.

## 5. Target Users & Use Cases

Same audience as the reference implementation: JVM developers who need a pluggable vote-counting component - for a small internal poll, a governance tool, or as a building block in a larger application - without hand-rolling tallying logic or getting floating-point edge cases wrong. A consumer picks an algorithm (e.g. `Majority.run(...)`), supplies candidates and ballots, and gets back a ranked list of winners.

## 6. Functional Requirements

### 6.1 Core domain model

| Concept | Responsibility |
|---|---|
| `Candidate` | Base contract for anything that can appear on a ballot. Ships with a `PreferentialCandidate` default (id, name, optional party). |
| `Ballot` | Base contract for a voter's submitted preferences, carrying an id, a weight, and an ordered list of candidate preferences. Ships with a `PreferentialBallot` default. Supports excluding/including candidates (used internally by elimination-style algorithms). |
| `Election` | Contract turning a list of ballots + candidates + vacancy count into a list of `Winner`s, given a pluggable tie-resolution strategy. |
| `TieResolver` | Strategy for ordering candidates that end up tied on score. Ships with three defaults: do-nothing (deterministic, order-preserving), random (shuffle), reverse. |
| `Winner` | A candidate paired with their final score. |
| `Rational` | Exact fraction type (arbitrary-precision numerator/denominator) used everywhere a vote weight or score is represented, so tallies never accumulate floating-point error. |

### 6.2 Voting algorithms

All nine algorithms below must be ported with matching behavior, verified against the same fixture data used by `votee-scala`:

| Algorithm | Summary | Reference |
|---|---|---|
| Majority | Winner needs strictly more than half the first-preference votes. | [wikipedia.org/wiki/Majority_rule](https://en.wikipedia.org/wiki/Majority_rule) |
| Super Majority | Like Majority, but against a configurable threshold above one half. | [wikipedia.org/wiki/Supermajority](https://en.wikipedia.org/wiki/Supermajority) |
| Approval | Every candidate a voter includes on their ballot gets one full vote; most approvals wins. | [wikipedia.org/wiki/Approval_voting](https://en.wikipedia.org/wiki/Approval_voting) |
| Veto | Every candidate on a ballot scores a point except the voter's least-preferred choice. | - |
| Borda Count | Candidates score points based on rank position on each ballot; points summed across all ballots. | [wikipedia.org/wiki/Borda_count](https://en.wikipedia.org/wiki/Borda_count) |
| Baldwin | Repeated Borda-Count elimination: drop the lowest Borda scorer each round until one candidate remains. | [wikipedia.org/wiki/Nanson's_method#Baldwin_method](https://en.wikipedia.org/wiki/Nanson%27s_method#Baldwin_method) |
| Contingent Vote | Top two first-preference candidates advance; other ballots' next usable preference is redistributed to decide the winner. | [wikipedia.org/wiki/Contingent_vote](https://en.wikipedia.org/wiki/Contingent_vote) |
| Coombs' Method | Repeated elimination of the candidate ranked *last* most often, until one candidate has a majority. | [wikipedia.org/wiki/Coombs'_method](https://en.wikipedia.org/wiki/Coombs%27_method) |
| Exhaustive Ballot | Repeated elimination of the lowest first-preference scorer until two candidates remain. | [wikipedia.org/wiki/Exhaustive_ballot](https://en.wikipedia.org/wiki/Exhaustive_ballot) |

## 7. Non-Functional Requirements

- **Exactness**: all scoring arithmetic uses `Rational`; no `double`/`float` in the tallying path.
- **Immutability**: domain model types (`Candidate`, `Ballot`, `Winner`, `Rational`) are immutable value types.
- **Determinism**: given the same inputs and the same `TieResolver`, an algorithm always produces the same output.
- **Extensibility without inheritance-for-reuse**: consumers extend behavior by implementing the `Candidate`/`Ballot`/`TieResolver` contracts, not by subclassing concrete algorithm classes.
- **Test parity**: every algorithm has at least one test driven by the ported JSON fixtures, plus the existing Scala test suite's expected-winner assertions.

## 8. High-Level Architecture

- **Build tool**: Maven, targeting **Java 21 (LTS)**.
- **Package**: `com.ludovictemgoua.votee` - namespaced under the author's own domain rather than mirrored from the Scala source's `io.hiis.votee`, since this artifact is meant to actually be published (see §8.1). Domain-based reverse-DNS naming is kept even though GitHub Packages doesn't require it, so the coordinates need not change if the library is later promoted to Maven Central.
- **Module layout** (indicative - finalized in the LLD):
  - `com.ludovictemgoua.votee.model` - `Candidate`, `PreferentialCandidate`, `Ballot`, `PreferentialBallot`, `Election`, `TieResolver`, `Winner`, `Rational`
  - `com.ludovictemgoua.votee.algorithms` - one class per algorithm
- **Dependencies**: no runtime dependencies beyond the JDK. A JSON library (e.g. Jackson) is a test-scope-only dependency, used solely to load the ported fixture files.
- **Testing**: JUnit 5, with the `01-candidates.json` / `01-ballots.json` / `02-ballots.json` / `03-ballots.json` fixtures ported verbatim from `votee-scala/src/main/resources` into the Java module's test resources.

### 8.1 Versioning & Publishing Strategy

- **Versioning scheme**: Semantic Versioning 2.0.0, applied from initial development under SemVer's own "major version zero" clause - **Early SemVer**. The library starts at `0.1.0`; while the major version stays `0`, a breaking API change bumps MINOR (`0.(x+1).0`) and a backward-compatible change bumps PATCH (`0.x.(y+1)`). The jump to `1.0.0` marks the point where the public API is declared stable.
- **Coordinates**: `groupId=com.ludovictemgoua`, `artifactId=votee`.
- **Target registry**: a private GitHub Packages Maven registry (under the author's GitHub account/org), configured via Maven's `distributionManagement` and authenticated with a `GITHUB_TOKEN` - the same mechanism `votee-scala` itself already uses to publish, via `sbt-github-packages`. Maven Central remains a possible future upgrade (see §11) but isn't required for this pass's success criteria.
- **Release artifacts**: main jar and sources jar at minimum; a javadoc jar is included if time allows, but isn't required for GitHub Packages the way it would be for Central.

## 9. Key Design Decisions & Rationale

| Decision | Chosen approach | Alternatives considered | Rationale |
|---|---|---|---|
| Ballot self-type | Curiously Recurring Generic Pattern: `Ballot<C extends Candidate, SELF extends Ballot<C, SELF>>`, with `exclude`/`include` returning `SELF` | (a) Interface methods return the base `Ballot<C>` type; (b) drop the interface, keep only `PreferentialBallot<C>` | Java has no equivalent to Scala's self-referential higher-kinded type parameter. CRGP is the standard idiomatic Java answer to "an interface method must return the implementing type" - it preserves both the extensible-contract story and fluent, type-safe chaining after `exclude`/`include`. |
| Rational arithmetic | Hand-written immutable `Rational` (`BigInteger` numerator/denominator, GCD-reduced on construction) | Third-party library (e.g. Apache Commons Math `BigFraction`) | No new runtime dependency, arbitrary precision (no overflow), and it's a self-contained value-object exercise that fits this repo's purpose. |
| Build tool | Maven | Gradle | More universal expectation in traditional enterprise Java codebases; lowest-friction first impression for someone skimming the repo. |
| Language level | Java 21 (LTS) | Java 17 (LTS) | Latest LTS; enables idiomatic use of records, sealed interfaces, and pattern-matching `switch` where they fit, and signals current Java fluency. |
| Port scope | All 9 implemented algorithms in one pass | Core model + 3–4 representative algorithms first | Matches `votee-scala`'s current scope exactly, so "parity" has one unambiguous meaning instead of a moving target. |
| Test data | Port the existing JSON fixtures verbatim | Fresh inline JUnit test data per algorithm | Reuses known-good test vectors and enables a direct "same input, same output across languages" comparison between the two implementations. |
| Versioning scheme | Early SemVer, starting at `0.1.0` | Start at `1.0.0` immediately | The API surface (especially the CRGP-based `Ballot` generics) is new and likely to shift once real usage/tests expose friction; SemVer's own major-version-zero clause exists for exactly this, and signals to any consumer that the API isn't frozen yet. |

## 10. Success Criteria

- All 9 algorithms implemented and passing tests driven by the ported fixture data.
- For every fixture case, the Java port's winner(s) match `votee-scala`'s winner(s) exactly.
- Public API (model + algorithm entry points) is Javadoc'd.
- The module builds and tests cleanly via `mvn test` with no warnings from the compiler about raw types or unchecked generics.
- The module is versioned, packaged, and successfully deployed to the private GitHub Packages registry, resolvable by a separate consuming project given a valid `GITHUB_TOKEN`.

## 11. Risks & Open Questions

- **Semantic drift risk**: the biggest real risk isn't arithmetic (arbitrary-precision `Rational` sidesteps overflow/rounding) but subtle behavioral differences introduced while translating Scala's collection operations (e.g. `groupMapReduce`, tail-recursive elimination loops) into Java's `Stream`/`Collection` APIs. Mitigated by the fixture-based parity tests in §6.2/§9.
- **Tie-resolution parity**: Scala's `given`/`using` implicit default parameters become explicit method overloads in Java (a default-tie-resolver overload calling through to a full-parameter overload). This is a mechanical, low-risk translation, detailed further in the LLD.
- **Publishing setup risk**: GitHub Packages requires a `GITHUB_TOKEN` with the right scopes for both publishing and (for consumers) resolving the artifact - a lighter setup than Maven Central, but still an administrative dependency outside the codebase itself.
- **Future work**: promoting to Maven Central once the API stabilizes past `0.x` remains an option, since the domain-based `com.ludovictemgoua` coordinates already satisfy Central's namespace-ownership requirement - no renaming needed if that path is taken later.
- **Open question**: whether `votee` should eventually depend on or be compared against `votee-scala` in an automated cross-language parity test (e.g. a shared fixture-runner), versus the two test suites simply being reviewed by eye for now. Deferred - not required for this port's success criteria.

## 12. Out of Scope / Future Work

- The unimplemented algorithms already tracked in `votee-scala`'s own README TODO list.
- Any REST/CLI layer on top of the library.
- Automated cross-language (Scala vs. Java) regression testing.

## 13. References

- Reference implementation: [`votee-scala`](https://github.com/icemc/votee)
- Root repository context: [`/README.md`](../../README.md)
