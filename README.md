# GitHub API

Feign contracts and Gson-bound DTOs for the slice of the GitHub REST API that reads and writes repository files. Covers the Contents API on both the read and the write side, the Commits API for branch-tip change detection, and the Git Database (Git Data) API for multi-file batched commits.

> [!IMPORTANT]
> This library ships **contracts and DTOs only** - it builds no HTTP client of its own. Wiring a contract into a live proxy is the caller's job, done through [simplified-dev/client](https://github.com/simplified-dev/client). Each contract declares the `Accept` media type it requires; supplying the wrong one silently changes what the endpoint returns rather than failing.

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Usage](#usage)
- [Contracts](#contracts)
- [Authentication and Rate Limits](#authentication-and-rate-limits)
- [Error Handling](#error-handling)
- [Writing Files](#writing-files)
  - [Contents API - one commit per file](#contents-api---one-commit-per-file)
  - [Git Data API - one commit per batch](#git-data-api---one-commit-per-batch)
- [Gradle Tasks](#gradle-tasks)
  - [Build and Test](#build-and-test)
- [Package Structure](#package-structure)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Four Feign contracts** - `GitHubContentsContract` (raw reads), `GitHubContentsWriteContract` (envelope reads + `PUT`), `GitHubGitDataContract` (blobs, trees, commits, refs), and the `GitHubAuth` header source
- **Media-type split by design** - the read and write Contents surfaces are siblings rather than one interface, because a raw-body read and a JSON-envelope write cannot share one static `Accept` header
- **Optimistic concurrency** - the blob SHA from a Contents envelope is the write token; a stale SHA is rejected by GitHub rather than silently overwriting a concurrent commit
- **Typed failures** - `GitHubApiException` carries the full HTTP context and disambiguates the crowded 403/429 surface into primary rate limit, secondary rate limit, and permissions
- **Owner and repo as parameters** - one proxy instance serves any number of repositories; nothing is baked into the contract
- **Conditional requests for free** - `If-None-Match` attach and `304` replay are handled beneath the contract by the client framework, so no method models a not-modified return
- **Gson-bound DTOs** - narrowed mirrors of the upstream JSON with private constructors, built reflectively by the decoder and never by hand

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [JDK](https://adoptium.net/) | **21+** | Required. Records, text blocks, and sequenced collections are used throughout |
| [Gradle](https://gradle.org/) | 8.x | Wrapper is bundled (`./gradlew`) |
| [Git](https://git-scm.com/) | 2.x+ | For cloning the repository |
| GitHub PAT | - | Optional. Without one the API allows 60 requests per hour per IP |

### Installation

Add the JitPack repository and the dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.simplified-api:github:master-SNAPSHOT")
}
```

The contracts are inert without a client, so pull in the framework that runs them:

```kotlin
dependencies {
    implementation("com.github.simplified-dev:client:master-SNAPSHOT")
    implementation("com.github.simplified-dev:gson-extras:master-SNAPSHOT")
}
```

Or clone and build locally:

```bash
git clone https://github.com/simplified-api/github.git
cd github
./gradlew build
```

> [!TIP]
> Building from the `Simplified-Api` parent instead substitutes `com.github.simplified-api:github` for the local sources through `includeBuild`, so a change here is visible to its siblings without a JitPack round trip.

### Usage

Build one `Client` per contract, then call the contract proxy. Owner and repository are method arguments, so a single client serves every repository you touch:

```java
// 1. The Authorization header is a dynamic supplier, evaluated per request. A blank token
//    degrades to unauthenticated rather than failing the proxy build.
GitHubAuth auth = GitHubAuth.bearer(System.getenv("GITHUB_TOKEN"));

// 2. The read client pins the raw media type. Without it the Contents endpoint answers a
//    base64 envelope capped at 1 MB and rejects anything larger.
ClientConfig<GitHubContentsContract> config = ClientConfig
    .builder(GitHubContentsContract.class, GsonSettings.defaults())
    .withHeader("Accept", "application/vnd.github.raw+json")
    .withHeader("X-GitHub-Api-Version", "2022-11-28")
    .withDynamicHeader("Authorization", auth)
    .withErrorDecoder(GitHubApiException::new)
    .build();

Client<GitHubContentsContract> client = Client.create(config);
GitHubContentsContract contents = client.getContract();

// 3. Read the branch tip, then a file off it.
GitHubCommit tip = contents.getLatestMasterCommit("simplified-api", "skyblock-data");
byte[] body = contents.getFileContent("simplified-api", "skyblock-data", "data/v1/index.json");

System.out.printf("%s at %s (%d bytes)%n",
    tip.getSha(), tip.getCommit().getCommitter().getDate(), body.length);
```

> [!NOTE]
> `getFileContent` returns `byte[]`, not `String`. The framework's response decoder tries to parse a raw JSON body when the declared return type is `String`, which fails on any JSON-object file; the binary-body decoder sidesteps that path and hands back the literal bytes.

> [!IMPORTANT]
> A read client and a write client are **two clients**. `ClientConfig` carries one static header set, and the two Contents surfaces need different `Accept` values - `application/vnd.github.raw+json` to get file bytes, `application/vnd.github+json` to get the envelope whose `sha` field is the write token.

## Contracts

| Contract | Accept | Endpoints | Notes |
|----------|--------|-----------|-------|
| `GitHubContentsContract` | `vnd.github.raw+json` | `GET commits/master`, `GET contents/{path}` | Raw file bytes, no 1 MB cap |
| `GitHubContentsWriteContract` | `vnd.github+json` | `GET contents/{path}`, `PUT contents/{path}` | Envelope read for the blob SHA, then the conditional write |
| `GitHubGitDataContract` | `vnd.github+json` | `getRef`, `getCommit`, `getTree`, `createBlob`, `createTree`, `createCommit`, `updateRef` | The seven calls a multi-file single commit needs |
| `GitHubAuth` | - | - | `Supplier<Optional<String>>` for the `Authorization` header |

`getLatestMasterCommit` resolves the tip through the single-commit-by-ref endpoint (`/commits/master`) rather than the listing endpoint (`/commits?sha=master&per_page=1`). The listing endpoint is served from GitHub's 60-second edge cache and hands back stale SHAs; the by-ref endpoint resolves through the git ref lookup and is always fresh.

## Authentication and Rate Limits

`GitHubAuth` is a `@FunctionalInterface` extending `Supplier<Optional<String>>`, so it drops straight into the framework's dynamic-header slot.

```java
GitHubAuth.bearer("ghp_...")     // Optional.of("Bearer ghp_...")
GitHubAuth.bearer("")            // degrades to unauthenticated
GitHubAuth.unauthenticated()     // always Optional.empty()
```

| Mode | Budget | Trigger |
|------|--------|---------|
| Unauthenticated | 60 requests / hour / IP | Blank or unset token |
| Authenticated (PAT) | 5000 requests / hour | Non-blank token |

> [!TIP]
> A blank token degrading instead of throwing is what lets an unset environment variable pass straight through to `bearer(...)` without a branch at the call site. Public-repo reads still succeed; the budget is what changes.

## Error Handling

Every non-2xx status surfaces as `GitHubApiException`, which carries the full response - status, headers, body, network timings, and the originating request - and lazily decodes the body into `GitHubErrorResponse`.

```java
try {
    contents.getFileContent(owner, repo, path);
} catch (GitHubApiException e) {
    if (e.isPrimaryRateLimit())        // quota exhausted, wait for the window
        scheduleRetryAfterReset(e);
    else if (e.isSecondaryRateLimit()) // abuse detection, back off harder
        backOff(e);
    else if (e.isPermissions())        // PAT scope problem, not a rate limit
        log.error("Token lacks scope: {}", e.getResponse().getReason());
    else
        throw e;
}
```

| Helper | Matches |
|--------|---------|
| `isPrimaryRateLimit()` | 403/429 **and** `x-ratelimit-remaining: 0` **and** a body message containing `API rate limit exceeded` |
| `isSecondaryRateLimit()` | 403/429 **and** a body message containing `secondary rate limit` or `abuse detection` |
| `isPermissions()` | 403 that matches neither of the above |

Requiring both the header and the message on the primary check is deliberate: either signal alone moves when GitHub tweaks its wording or its header set, and the pair does not.

> [!NOTE]
> A `304 Not Modified` never reaches `GitHubApiException`. The framework's internal error decoder short-circuits 3xx into `NotModifiedException` before any per-client decoder runs, and the conditional-request machinery replays the cached body transparently.

`GitHubErrorResponse` carries its fallbacks in field initializers - when the body is absent or is not JSON, the framework builds a fresh instance reflectively and those initializers are what a caller reads.

## Writing Files

### Contents API - one commit per file

Read the envelope for the blob SHA, then `PUT` with that SHA attached. GitHub rejects a stale SHA, which is the whole concurrency control.

```java
GitHubContentEnvelope current = write.getFileMetadata(owner, repo, path);

PutContentRequest body = PutContentRequest.builder()
    .message("Update " + path)
    .content(Base64.getEncoder().encodeToString(newBytes))
    .sha(current.getSha())      // the optimistic-concurrency token
    .branch("master")
    .build();

GitHubPutResponse result = write.putFileContent(owner, repo, path, body);
```

A conflicting write comes back as `409`/`422`, mapped by the framework to `PreconditionFailedException`. Every successful `PUT` writes its own commit, so a batch touching N files leaves N commits in the log.

### Git Data API - one commit per batch

`GitHubGitDataContract` is the alternative when N files must land as one commit: stage each file as a blob, overlay the blobs onto the current tree, create a commit parented on the current tip, and fast-forward the ref.

```
getRef(owner, repo, "master")                    -> current tip SHA
  -> getCommit(owner, repo, tipSha)              -> its tree SHA
  -> createBlob(...)          per changed file   -> blob SHAs
  -> createTree(base_tree = treeSha, entries)    -> new tree SHA
  -> createCommit(tree, parents = [tipSha])      -> detached commit SHA
  -> updateRef(owner, repo, "master", sha)       -> branch moves
```

Leaving `force` unset (or `false`) on `updateRef` makes GitHub enforce the fast-forward check and reject with `422` when the tip moved underneath - the same optimistic concurrency the Contents path gets from the blob SHA, one level up.

> [!NOTE]
> Nothing in this repository calls the Git Data surface. It ships fully DTO-tested so a consumer can adopt it without first discovering that GitHub's envelope drifted.

## Gradle Tasks

### Build and Test

```bash
./gradlew build       # compile, test, assemble jar
./gradlew test        # JUnit 5 suite
```

The whole suite is offline. Every test builds Gson fixtures or a hand-made `ErrorContext` in-process - no network, no Feign proxy, no Spring context - so `test` is the complete gate and there is no slow tier.

## Package Structure

```
github/
├── src/
│   ├── main/java/api/simplified/github/
│   │   ├── GitHubAuth.java                    # Supplier<Optional<String>> for Authorization
│   │   ├── GitHubContentsContract.java        # raw reads + branch tip
│   │   ├── GitHubContentsWriteContract.java   # envelope read + conditional PUT
│   │   ├── GitHubGitDataContract.java         # blobs, trees, commits, refs
│   │   ├── exception/                         # GitHubApiException, GitHubErrorResponse
│   │   ├── request/                           # PutContentRequest, CreateBlob/Tree/CommitRequest, UpdateRefRequest
│   │   └── response/                          # GitHubCommit, GitHubContentEnvelope, GitHubPutResponse,
│   │                                          #   GitBlob, GitTree, GitCommit, GitRef
│   └── test/java/                             # Gson round-trip + 403/429 classification tests
├── build.gradle.kts  settings.gradle.kts  gradle/libs.versions.toml
└── LICENSE.md  CONTRIBUTING.md  CLAUDE.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE](LICENSE.md) for the full text.

GitHub and the GitHub logo are trademarks of GitHub, Inc. This library is an independent REST client and is not affiliated with or endorsed by GitHub.
