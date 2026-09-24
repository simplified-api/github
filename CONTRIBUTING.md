# Contributing to GitHub API

Thank you for your interest in contributing! This document explains how to get started, what to expect during the review process, and the conventions this project follows.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Development Setup](#development-setup)
  - [IntelliJ IDEA](#intellij-idea)
- [Making Changes](#making-changes)
  - [Branching Strategy](#branching-strategy)
  - [Code Style](#code-style)
  - [Commit Messages](#commit-messages)
  - [Validating Output](#validating-output)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Issues](#reporting-issues)
- [Project Architecture](#project-architecture)
- [Legal](#legal)

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | **21+** | Required |
| Gradle | 9.x | Wrapper is bundled (`./gradlew`) |
| Git | 2.x+ | For cloning and contributing |
| IDE | Any | IntelliJ IDEA is the recommended editor |

> [!IMPORTANT]
> This repository ships **contracts, DTOs and `GitHubCorpus`**, which builds its two clients through `Client.create` from [simplified-dev/client](https://github.com/simplified-dev/client), pinning each to the `Accept` media type its contract needs. That framework executes every contract here, so a change to a `@RequestLine`, a return type, or an `Accept` requirement is a change to its contract with GitHub, and to the corpus built on it. Read the surrounding Javadoc before altering one - most of them record a live constraint rather than a preference.

### Development Setup

1. **Fork and clone the repository**

   [Fork the repository](https://github.com/simplified-api/github/fork), then clone your fork:

   ```bash
   git clone https://github.com/<your-username>/github.git
   cd github
   ```

2. **Verify the JDK toolchain**

   `build.gradle.kts` asks for a JDK 21 toolchain, and the build configures no toolchain download repository, so Gradle has to find a JDK 21 installed locally. List the installations it detects with:

   ```bash
   ./gradlew -q javaToolchains
   ```

3. **Run the build**

   ```bash
   ./gradlew build
   ```

   This compiles the main sources, runs the test suite, and assembles the jar.

   > [!NOTE]
   > The suite needs no credentials and passes offline. Most cases build Gson fixtures, hand-built `ErrorContext` instances, `GitHubAuth` and `GitHubToken` values, or corpora over contracts answered from memory. Three `GitHubCorpusBuilderTest` cases build through `GitHubCorpus.Builder.build()`, which makes two real clients; each starts a DNS lookup and a `HEAD` probe of `api.github.com` on a background thread and drops any failure. Building waits on neither, so those cases pass with no network, but where there is one they reach GitHub.

4. **Build against local siblings (optional)**

   The upstream `client`, `gson-extras` and `collections` dependencies are `strictly()`-pinned to JitPack SHAs in `build.gradle.kts`. To test against unpublished changes to them, build from a Gradle composite that includes this repository beside local checkouts of those libraries, each substituted for its `com.github.simplified-dev` coordinate through `includeBuild(...) { dependencySubstitution { ... } }`. In the Simplified workspace that composite is the root build: its `settings.gradle.kts` includes `Simplified-Dev` and `Simplified-Api`, and the `Simplified-Dev` settings substitute `client`, `gson-extras`, `collections` and `utils` for their local sources. The `Simplified-Api` parent substitutes only the `simplified-api` modules for one another, so building from it still resolves the pinned JitPack artifacts.

### IntelliJ IDEA

1. Open the project root (the directory containing `settings.gradle.kts`). IntelliJ auto-imports the Gradle build.
2. Ensure the **Project SDK** under **File > Project Structure** is set to a JDK 21 installation.
3. Enable **annotation processing** - the Simplified Annotations processor generates every getter and builder in `request/` and `response/`, and the IDE reports phantom errors until the processor runs.
4. Open the workspace root composite instead when you need an unpublished `client`, `gson-extras` or `collections` change on the classpath; opening this repo alone, or the `Simplified-Api` parent, resolves the pinned JitPack artifacts.

## Making Changes

### Branching Strategy

- Create a feature branch from `master` for your work.
- Use a descriptive branch name: `fix/raw-accept-header`, `feat/git-data-batch-commit`, `docs/rate-limit-helpers`.

```bash
git checkout -b feat/my-feature master
```

### Code Style

The repository uses Simplified Annotations (`io.github.simplified-dev:annotations`) for boilerplate reduction and enforces a consistent Javadoc, exception, and control-flow style.

#### Javadoc

- **Punctuation** - Single hyphens ` - ` only as separators. Never em dashes, `&mdash;`, or `--`.
- **Voice** - Class/interface = noun phrase. Method = third-person singular verb ("Returns the..."). Field = sentence fragment, no tags.
- **Tags** - Always include `@param`, `@return`, `@throws` where applicable. Lowercase sentence fragments, no trailing period. Single space after the parameter name - never column-align.
- **Cross-references** - Use `{@link}` / `{@linkplain}` / `@see`. Use `{@code}` for inline code. Import link targets so they render with short names.
- **Overrides** - Use `/** {@inheritDoc} */` for methods that override library/framework types. Do not rewrite the parent doc.
- **Field getters** - Field-like interface methods (no params, non-void return) use a noun-phrase fragment without `@return` and without "Gets"/"Returns". `@Getter` implementations carry their doc on the field, not a separate method Javadoc block.
- **Structure** - `<p>` on its own line between paragraphs; `<ul>` / `<li>` for lists; `<b>` for emphasis inside list items.
- **Forbidden tags** - Never use `@author` or `@since`.
- **Upstream references** - Every contract's class Javadoc carries an `@see` link to the GitHub REST documentation it follows, pinned to `apiVersion=2022-11-28`.

#### Control flow

Omit braces on single-line bodies; use braces when the body wraps across multiple lines. Applies to all single-statement forms (`if`, `for`, `while`, `do`, lambda bodies).

```java
if (StringUtil.isEmpty(token))
    return unauthenticated();

for (GitTree.Entry entry : tree.getTree()) {
    if (!"blob".equals(entry.getType()))
        continue;
    stage(entry);
}
```

#### Exception classes

Project exceptions follow a **five-constructor pattern** in this order:

1. `(Throwable cause)`
2. `(String message)`
3. `(Throwable cause, String message)`
4. `(@PrintFormat String message, Object... args)`
5. `(Throwable cause, @PrintFormat String message, Object... args)`

Root exceptions (extending `RuntimeException`) reverse the `super()` parameter order:

```java
super(message, cause);
super(String.format(message, args), cause);
```

Child exceptions pass through to the parent, which handles the reversal:

```java
super(cause, message);
super(cause, message, args);
```

Message conventions:

- No trailing punctuation.
- Start with an uppercase letter.
- Use `'%s'` for interpolated values in format strings.

Annotations:

- `@NotNull` on `Throwable cause` and `String message` parameters.
- `@PrintFormat` on format string parameters (from `org.intellij.lang.annotations`).
- `@Nullable` on `Object... args` parameters.

Javadoc:

- **Class-level** - "Thrown when [condition]." Never use the words "unchecked" or "exception" in the description.
- **Constructor** - "Constructs a new {@code ClassName} with [description]."
- **`@param` tags** - lowercase, no trailing period.

> [!NOTE]
> `GitHubApiException` is not one of these. It is a framework-decoded API failure, so its single `(Gson, ErrorContext)` constructor is fixed by `ClientConfig.withErrorDecoder`'s method reference shape. The five-constructor pattern governs exceptions this project throws itself, not the one the decoder constructs.

#### DTOs

- One `@SerializedName` per field, spelled exactly as GitHub spells it. Do not rely on Gson's field-name matching.
- Response DTOs get `@RequiredArgsConstructor(access = AccessLevel.PRIVATE)` - Gson builds them reflectively and nothing else should.
- Request DTOs get `@Getter` plus `@ClassBuilder`, whose generated all-args constructor is package-private, so a caller builds one through `builder()`.
- Declare only the fields a consumer reads. Gson silently ignores the rest, and every declared field is one more thing that can drift.
- Optional upstream fields are `@Nullable` boxed types, never primitives - a missing `size` must stay distinguishable from `0`.

### Commit Messages

Write clear, concise commit messages that describe *what* changed and *why*.

```
Read the branch tip through the by-ref commit endpoint

The listing endpoint (/commits?sha=master&per_page=1) is served from
GitHub's 60-second edge cache and returned a SHA one commit behind for
up to a minute after a push. /commits/master resolves through the git
ref lookup and is always fresh.
```

- Use the imperative mood ("Add", "Fix", "Update", not "Added", "Fixes").
- Keep the subject line under 72 characters.
- Add a body when the *why* isn't obvious from the subject.

### Validating Output

- **Test suite**

  ```bash
  ./gradlew test
  ```

- **Round-trip coverage** - required when your change adds or renames a DTO field. The Contents, Commits and Git Data DTOs carry Gson round-trip tests (`ContentsApiDtoRoundTripTest`, `CommitsApiDtoRoundTripTest`, `GitDataDtoRoundTripTest`) built from fixtures lifted out of GitHub's own documentation; a new field without one is a field nothing would notice going missing.

- **Classification coverage** - required when your change touches `GitHubApiException`. `GitHubApiExceptionTest` builds a primitive `ErrorContext` per case; add one per new status/header/message combination rather than widening an existing assertion.

- **Token coverage** - required when your change touches `GitHubAuth` or `GitHubToken`. `GitHubAuthTest` and `GitHubTokenTest` call them directly. Neither sets an environment variable or writes a `.env` file; a case that needs a variable unset names one afresh from a random UUID.

- **Corpus coverage** - required when your change touches `GitHubCorpus`. Build the corpus through `GitHubCorpus.Builder.build(reads, writes)` over contracts answered from memory, as `GitHubCorpusPollTest` does, so the case asserts which requests the corpus makes and sends none of them to GitHub.

- **Live verification** - the suite proves the shapes parse, not that the endpoint still answers them. When your change touches a `@RequestLine`, an `Accept` requirement, or a return type, exercise it against `api.github.com` once by hand and say so in the PR.

> [!TIP]
> When you add a DTO field, copy the fixture JSON from the linked GitHub documentation page rather than from a live response. Live responses carry account-specific values that read as meaningful to the next person.

## Submitting a Pull Request

1. **Push your branch** to your fork.

   ```bash
   git push origin feat/my-feature
   ```

2. **Open a Pull Request** against the `master` branch of [simplified-api/github](https://github.com/simplified-api/github).

3. **In the PR description**, include:
   - A summary of the changes and the motivation behind them.
   - The GitHub API version you tested against (`X-GitHub-Api-Version`).
   - Whether you verified the change against the live API, and how.
   - A link to the GitHub REST documentation page for any endpoint you added or changed.

4. **Respond to review feedback.** PRs may go through one or more rounds of review before being merged.

### What gets reviewed

- **Fidelity to the upstream API.** A `@RequestLine`, an `Accept` requirement, or a DTO field that disagrees with GitHub's documented shape blocks a merge.
- **Media-type discipline.** The read and write Contents surfaces stay separate contracts. A change that merges them, or that adds a method needing a third `Accept` to an existing contract, needs a stated reason.
- **Nullability.** An upstream field that is absent on some responses is `@Nullable`; one that is always present is `@NotNull`. Getting this backwards produces a null that surfaces far from its cause.
- **Javadoc and exception style** as documented above. Inconsistent style will be flagged.

## Reporting Issues

Use [GitHub Issues](https://github.com/simplified-api/github/issues) to report bugs or request features.

When reporting a bug, include:

- **JDK version** (`java -version`)
- **Operating system**
- **Contract and method** that reproduces the issue
- **`Accept` and `X-GitHub-Api-Version` headers** your client was configured with
- **Authenticated or unauthenticated**, and the `x-ratelimit-remaining` value if you have it
- **Full stack trace** (if applicable)
- **Expected vs. actual response shape** - paste the JSON with any tokens and private paths redacted
- **Steps to reproduce** - ideally a minimal `ClientConfig` plus the one call

> [!CAUTION]
> Never paste a personal access token into an issue, a test fixture, or a commit. A token in a public repository is revoked by GitHub's secret scanner, but only after it has been readable.

## Project Architecture

A brief overview to help you find your way around the codebase:

```
api.simplified.github/
├── GitHubAuth.java                  # Supplier<Optional<String>> plugged into the dynamic-header slot
├── GitHubContentsContract.java      # read: branch tip + raw file bytes
├── GitHubContentsWriteContract.java # write: envelope read (for the blob SHA) + conditional PUT
├── GitHubCorpus.java                # one repository as a corpus; builds both Contents clients, + Builder, Blob
├── GitHubGitDataContract.java       # blobs, trees, commits, refs - the multi-file batch path
├── GitHubToken.java                 # the personal access token a corpus sends, refused where it is made
├── ManifestIndex.java               # the corpus catalogue: revision + layered documents
├── exception/                       # GitHubApiException + the parsed GitHubErrorResponse body
├── request/                         # outbound bodies, @ClassBuilder + package-private constructor
└── response/                        # inbound mirrors, private constructor, Gson-built
```

### Request flow

```
caller or GitHubCorpus
  -> Client<C>.getContract()                # JDK proxy unwrapping RetryableApiException
  -> Feign proxy                            # @RequestLine expansion, Gson encode
  -> InternalRequestInterceptor             # route rate-limit gate (refusal -> RateLimitException), target URL
  -> CachingFeignClient                     # RFC 7234 fresh-hit short circuit, If-None-Match, 304 replay
  -> Apache HTTP/5                          # pooled, timed (DNS / TCP / TLS)
  -> InternalResponseInterceptor            # a live response's X-RateLimit-* headers -> the route's bucket
  -> InternalErrorDecoder                   # 3xx -> NotModifiedException, 412 -> PreconditionFailedException,
                                            # 429 -> RateLimitException, else the per-client decoder
  -> GitHubApiException                     # every other non-2xx, body decoded to GitHubErrorResponse
```

Beyond `GitHubApiException`, this repository implements none of that pipeline. The contracts declare what to send and what comes back, `GitHubCorpus` assembles its two clients - the `Accept` media type, the API version header, the auth and `GitHubApiException::new` as the per-client decoder - and `simplified-dev/client` runs the rest.

### Write paths

```
Contents API           envelope.sha -> PUT with sha  -> one commit per file
Git Data API           ref -> commit -> tree -> blobs -> tree -> commit -> ref  -> one commit per batch
```

Both are optimistic: the Contents path is guarded by the blob SHA, the Git Data path by GitHub's fast-forward check on `updateRef`.

## Legal

By submitting a pull request, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE.md), the same license that covers this project.

**Do not commit credentials.** Personal access tokens, `.env` files, and captured responses containing `Authorization` headers must never enter the repository. Test fixtures are hand-written JSON lifted from public documentation, never a recorded live exchange.

GitHub and the GitHub logo are trademarks of GitHub, Inc. This library is an independent REST client and is not affiliated with or endorsed by GitHub.
