# GitHub API

Feign contracts, Gson-bound DTOs and a corpus client for the slice of the GitHub REST API that reads and writes repository files. The contracts cover the Contents API on both the read and the write side, the Commits API for branch-tip change detection, and the Git Database (Git Data) API for multi-file batched commits. `GitHubCorpus` sits on the two Contents contracts and treats one repository as a document corpus: file text at the branch or at a commit, blob shas, single-file writes, and a catalogue it re-reads when the branch moves.

> [!IMPORTANT]
> A contract is inert on its own. Running one takes a `Client` from [simplified-dev/client](https://github.com/simplified-dev/client), and each contract declares the `Accept` media type it requires; supplying the wrong one silently changes what the endpoint returns rather than failing. `GitHubCorpus` builds both of its clients itself, each with the media type it needs, so a caller reading and writing files through a corpus configures neither.

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Usage](#usage)
- [GitHubCorpus](#githubcorpus)
  - [Building a corpus](#building-a-corpus)
  - [Reading and writing files](#reading-and-writing-files)
  - [The catalogue](#the-catalogue)
- [Contracts](#contracts)
  - [Using the contracts directly](#using-the-contracts-directly)
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

- **A corpus client** - `GitHubCorpus` binds one repository, branch and catalogue path, builds the two Contents clients with the media type each needs, and answers file text, blob shas, the branch tip, and a held catalogue it replaces when the branch moves
- **Three Feign contracts and an auth source** - `GitHubContentsContract` (raw reads + branch tip), `GitHubContentsWriteContract` (envelope reads + `PUT`), `GitHubGitDataContract` (blobs, trees, commits, refs), and `GitHubAuth` for the `Authorization` header
- **Media-type split by design** - the read and write Contents surfaces are siblings rather than one interface, because a raw-body read and a JSON-envelope write cannot share one static `Accept` header
- **Optimistic concurrency** - the blob SHA of a file is the write token; a stale SHA is rejected by GitHub rather than silently overwriting a concurrent commit
- **Typed failures** - `GitHubApiException` carries the full HTTP context and disambiguates the crowded 403 surface into primary rate limit, secondary rate limit, and permissions
- **Owner, repo and ref as parameters** - one proxy instance serves any number of repositories, branches and commits; nothing is baked into a contract
- **Conditional requests for free** - `If-None-Match` attach and `304` replay are handled beneath the contract by the client framework, so no method models a not-modified return
- **Gson-bound DTOs** - narrowed mirrors of the upstream JSON; a response DTO has a private constructor and is built reflectively by the decoder, a request DTO through its generated builder

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [JDK](https://adoptium.net/) | **21+** | Required. Records, text blocks, and sequenced collections are used throughout |
| [Gradle](https://gradle.org/) | 9.x | Wrapper is bundled (`./gradlew`) |
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

The framework that runs the contracts comes with it: `client`, `gson-extras`, `collections` and Gson are `api` dependencies, so `Client`, `ClientConfig`, `GsonSettings` and the concurrent collections reach a consumer's classpath without a declaration of their own.

Or clone and build locally:

```bash
git clone https://github.com/simplified-api/github.git
cd github
./gradlew build
```

> [!TIP]
> Building from the `Simplified-Api` parent instead substitutes `com.github.simplified-api:github` for the local sources through `includeBuild`, so a change here is visible to its siblings without a JitPack round trip.

### Usage

Name the repository, then build a corpus and read through it. The corpus makes both clients, so nothing about media types, API versions or error decoding reaches the call site:

```java
// Every setting has a default: the branch is master, the catalogue is index.json at the
// repository root, and a corpus named with no token reads unauthenticated.
GitHubCorpus corpus = GitHubCorpus.of("simplified-api", "skyblock")
    .manifest("data/v1/index.json")
    .token(GitHubToken.of("GITHUB_TOKEN"))   // reads the variable here; unset or empty throws
    .build();

String tip = corpus.tip();                                           // the branch's commit sha
String text = corpus.read("data/v1/items/accessories.json");        // at the branch
String pinned = corpus.read("data/v1/items/accessories.json", tip); // at that commit
```

[GitHubCorpus](#githubcorpus) covers the rest of its surface, and [Using the contracts directly](#using-the-contracts-directly) builds a client by hand for a caller that needs an endpoint the corpus does not reach.

## GitHubCorpus

A `GitHubCorpus` is one repository read and written as a set of files: it deals in paths, bytes, shas and commits, and what those mean to a consumer's types is the consumer's.

### Building a corpus

`GitHubCorpus.of(owner, repo)` answers a `GitHubCorpus.Builder`:

| Builder method | Default | Names |
|----------------|---------|-------|
| `branch(String)` | `master` | The branch every request names, except a read that names a ref of its own |
| `manifest(String)` | `index.json` | The repo-root-relative path of the catalogue |
| `gson(GsonSettings)` | `GsonSettings.defaults()` | The settings the catalogue is parsed with and the read client decodes with |
| `token(GitHubToken)` | none | The personal access token both clients send |

The builder has two terminals:

- **`build()`** makes the two clients - the read client pinned to `application/vnd.github.raw+json` and the write client pinned to `application/vnd.github+json` - each carrying the token when one is named. Nothing waits on GitHub. Each client builds its proxy and connection pool at once and starts a DNS lookup and a `HEAD` probe of `api.github.com` on a background thread, dropping any failure, so an unreachable GitHub is reported by the first read or write rather than by the build.
- **`build(reads, writes)`** answers every request through the two contracts it is handed and makes no client, so it starts no probe. The contracts replace everything a client carries - the `Accept` media type, the API version header, response and error decoding, and the auth - so the builder's token is not applied to them; the repository, the branch, the catalogue path and the catalogue's parser settings still come from the builder. It serves a caller answering the requests itself: an in-memory test double, a recording or caching proxy, a gateway. Whatever answers them answers as the clients do - `reads` returns a file as its raw bytes, whatever its size, and `writes` returns the envelope a blob sha is read from and takes the body a file is written with.

`GitHubToken` is the token a corpus sends, checked where it is made rather than as a `401` on the first write:

```java
GitHubToken.of("GITHUB_TOKEN")   // reads the variable; unset or empty throws IllegalStateException
GitHubToken.value(token)         // a token the caller already holds; null or empty throws IllegalStateException
```

`of` reads through `SystemUtil.getEnv` from `simplified-dev/utils`, which matches the name case-insensitively and reads the OS environment over two `.env` files - the class-loader resource at `../.env`, and the file beside the jar or class directory `SystemUtil` was loaded from - so a variable spelled the same in the OS environment and in a file is read from the OS environment. A value of whitespace alone is not empty, so it is taken as a token and sent to GitHub rather than refused here.

A write needs one. A read does not, but an unauthenticated corpus shares the 60-requests-per-hour budget of its address.

### Reading and writing files

| Method | Answers | Request |
|--------|---------|---------|
| `read(path)` | The file's text at the branch, decoded as UTF-8 | `GET contents/{path}?ref={branch}`, raw |
| `read(path, ref)` | The file's text at a commit sha, branch or tag | `GET contents/{path}?ref={ref}`, raw |
| `blob(path)` | A `Blob` - the text at the branch and the git blob sha of the bytes it was decoded from | `GET contents/{path}?ref={branch}`, raw |
| `metadata(path)` | The file's blob sha at the branch | `GET contents/{path}?ref={branch}`, envelope |
| `write(path, content, sha, message)` | Nothing - it commits the text as one commit on the branch | `PUT contents/{path}` |
| `tip()` | The commit sha the branch points at | `GET commits/{branch}` |

A read at the branch can be answered from the client's response cache for up to a minute after the branch moves, where a commit sha names content that never changes. A caller that needs a file exactly as some commit holds it reads at that commit.

`write` is the Contents path below, bound to the corpus's repository and branch: the sha is the optimistic-concurrency token, and GitHub refuses the write when the file no longer carries it. `blob` is the read that pairs with it. It computes the sha from the bytes it read - SHA-1 over `blob <length>\0` and the bytes, as `git hash-object` does - so the text and the sha always name the same content, and a body the cache replays from before the branch moved carries that older body's sha, which GitHub refuses rather than letting the write land over the newer commit. `metadata` reads the sha through the write client, whose cache is its own, so text read beside it can be older than the sha it is written under.

```java
GitHubCorpus.Blob current = corpus.blob("data/v1/items/accessories.json");
String edited = edit(current.text());
corpus.write("data/v1/items/accessories.json", edited, current.sha(), "Update accessories");
```

### The catalogue

`manifest()` answers the corpus catalogue as a `ManifestIndex`. The first call reads the branch tip, then the catalogue at that commit, and holds both; every later call answers the held catalogue with no request. `manifestCommit()` answers the commit the held catalogue was read at, fetching the catalogue first when none is held, so a file read at that commit comes out of the same tree the catalogue did.

`poll()` asks whether the branch has moved since the held catalogue was read. One tip request rules out the whole corpus: when the tip is the one the held catalogue was read at, it answers empty and reads nothing more. Otherwise it reads the catalogue at the new tip, holds it with that tip, and answers it. What to do about the change is the caller's.

Both read the catalogue at a commit sha rather than at the branch, so the catalogue read is never answered from before the branch moved. The tip read is a branch read, though, and the client's response cache can answer it for up to a minute, so a poll inside that minute can report no move for a branch that has moved. A catalogue body that is empty or the JSON literal `null` throws `IllegalStateException`, and one that is malformed or does not read as a catalogue object throws Gson's `JsonSyntaxException`; either way the corpus keeps the catalogue it held before, if any.

The catalogue names each logical document's layers in merge order:

```json
{
  "documents": {
    "accessories": [
      { "path": "data/v1/items/accessories.json", "sha256": "eb2becf6...8bf3" }
    ]
  },
  "revision": "320cea87f2d538b6afcd9b15936fda8d668bb880"
}
```

Layers merge by key with the later one winning, so a generated file and a companion overriding rows in it are one document. `layersOf(name)` answers a document's layers, empty when the catalogue carries no such document, and `fingerprintOf(name)` joins every layer's SHA-256 in merge order, so a change to any layer moves it; an absent document answers empty, which reads as "cannot claim unchanged". `revision` is the commit the generator's checkout stood at, never the commit carrying the catalogue, which is why `poll()` compares branch tips and not revisions. `ManifestIndex.empty()` answers a catalogue holding nothing.

## Contracts

| Contract | Accept | Endpoints | Notes |
|----------|--------|-----------|-------|
| `GitHubContentsContract` | `vnd.github.raw+json` | `GET commits/{branch}`, `GET contents/{path}?ref={ref}` | Raw file bytes, no 1 MB cap |
| `GitHubContentsWriteContract` | `vnd.github+json` | `GET contents/{path}?ref={branch}`, `PUT contents/{path}` | Envelope read for the blob SHA, then the conditional write |
| `GitHubGitDataContract` | `vnd.github+json` | `getRef`, `getCommit`, `getTree`, `createBlob`, `createTree`, `createCommit`, `updateRef` | The seven calls a multi-file single commit needs |
| `GitHubAuth` | - | - | `Supplier<Optional<String>>` for the `Authorization` header |

Every request line names its ref. `getLatestCommit(owner, repo, branch)` answers a branch's tip commit, `getFileContent(owner, repo, path, ref)` a file's raw bytes at a branch, a tag or a commit sha, and `getFileMetadata(owner, repo, path, branch)` the envelope at the branch a write is checked against.

`getLatestCommit` resolves the tip through the single-commit-by-ref endpoint (`/commits/{branch}`) rather than the listing endpoint (`/commits?sha={branch}&per_page=1`). The listing endpoint is served from GitHub's 60-second edge cache and hands back stale SHAs; the by-ref endpoint resolves through the git ref lookup and is always fresh at GitHub. The client's own response cache can still replay a tip it holds for up to a minute.

### Using the contracts directly

Build one `Client` per contract, then call the contract proxy. Owner, repository and ref are method arguments, so a single client serves every repository you touch:

```java
// 1. The Authorization header is a dynamic supplier, evaluated per request. A null or empty
//    token - an unset or empty variable - degrades to unauthenticated rather than failing.
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

// 3. Read the branch tip, then a file at that commit.
GitHubCommit tip = contents.getLatestCommit("simplified-api", "skyblock", "master");
byte[] body = contents.getFileContent("simplified-api", "skyblock", "data/v1/index.json", tip.getSha());

System.out.printf("%s at %s (%d bytes)%n",
    tip.getSha(), tip.getCommit().getCommitter().getDate(), body.length);
```

> [!NOTE]
> `getFileContent` returns `byte[]`, not `String`. The framework's response decoder tries to parse a raw JSON body when the declared return type is `String`, which fails on any JSON-object file; the binary-body decoder sidesteps that path and hands back the literal bytes.

> [!IMPORTANT]
> A read client and a write client are **two clients**. `ClientConfig` carries one static header set, and the two Contents surfaces need different `Accept` values - `application/vnd.github.raw+json` to get file bytes, `application/vnd.github+json` to get the envelope whose `sha` field is the write token.

## Authentication and Rate Limits

`GitHubAuth` is a `@FunctionalInterface` extending `Supplier<Optional<String>>`, so it drops straight into the framework's dynamic-header slot.

```java
GitHubAuth.bearer("ghp_...")     // Optional.of("Bearer ghp_...")
GitHubAuth.bearer("")            // degrades to unauthenticated
GitHubAuth.bearer(null)          // degrades to unauthenticated
GitHubAuth.unauthenticated()     // always Optional.empty()
```

| Mode | Budget | Trigger |
|------|--------|---------|
| Unauthenticated | 60 requests / hour / IP | No token, or a null or empty one |
| Authenticated (PAT) | 5000 requests / hour | Any other token |

> [!TIP]
> A null or empty token degrading instead of throwing is what lets `System.getenv(...)` pass straight through to `bearer(...)` without a branch at the call site, whether the variable is unset or set to an empty string. Public-repo reads still succeed; the budget is what changes. A token of whitespace alone is not empty, so `bearer` sends it.

`GitHubCorpus` takes a `GitHubToken` instead, which refuses an unset or empty variable where it is read, and hands each of its clients a bearer `GitHubAuth` over it; a corpus named with no token uses `GitHubAuth.unauthenticated()`.

Each client keeps the budget GitHub reports. `simplified-dev/client` holds one rate-limit bucket for `api.github.com` and sets it from the `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset` headers of every live response. Once the bucket has no request left, the client refuses the next one with `RateLimitException` before sending it, and keeps refusing until the reset GitHub named. A reply served from the client's response cache counts against the bucket as well, until the next live response brings the count back to GitHub's figure.

## Error Handling

A non-2xx status surfaces as `GitHubApiException`, which carries the full response - status, headers, body, network timings, and the originating request - and lazily decodes the body into `GitHubErrorResponse`. Three kinds of status are the framework's before they are GitHub's: a 3xx surfaces as `NotModifiedException`, a `412` as `PreconditionFailedException` and a `429` as `RateLimitException`, each raised by `simplified-dev/client` before the GitHub decoder runs and none of them a `GitHubApiException`. `RateLimitException` also arrives with no request sent, when the client's rate-limit gate refuses one (see [Authentication and Rate Limits](#authentication-and-rate-limits)).

```java
try {
    contents.getFileContent(owner, repo, path, ref);
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

The helpers accept a `429`, but a `429` surfaces as `RateLimitException` before `GitHubApiException` is built, so in practice they classify a `403`. A primary-limit `403` carries `x-ratelimit-remaining: 0`, which empties the client's bucket, so every later request until GitHub's reset is refused as `RateLimitException` without being sent. A caller that wants every rate-limit shape catches `RateLimitException` as well.

> [!NOTE]
> A `304 Not Modified` never reaches `GitHubApiException`. The framework's internal error decoder short-circuits 3xx into `NotModifiedException` before any per-client decoder runs, and the conditional-request machinery replays the cached body transparently.

`GitHubErrorResponse` carries its fallbacks in field initializers - when the body is absent or is not JSON, the framework builds a fresh instance reflectively and those initializers are what a caller reads.

## Writing Files

### Contents API - one commit per file

Read the envelope for the blob SHA, then `PUT` with that SHA attached. GitHub rejects a stale SHA, which is the whole concurrency control.

```java
GitHubContentEnvelope current = write.getFileMetadata(owner, repo, path, "master");

PutContentRequest body = PutContentRequest.builder()
    .message("Update " + path)
    .content(Base64.getEncoder().encodeToString(newBytes))
    .sha(current.getSha())      // the optimistic-concurrency token
    .branch("master")
    .build();

GitHubPutResponse result = write.putFileContent(owner, repo, path, body);
```

A conflicting write comes back as `409`/`422` and surfaces as `GitHubApiException` carrying that status; the framework raises `PreconditionFailedException` only for a `412`. Every successful `PUT` writes its own commit, so a batch touching N files leaves N commits in the log. `GitHubCorpus.write` is this flow bound to one repository and branch, and `GitHubCorpus.blob` reads a sha paired with the text it came from.

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

The whole suite passes offline. Every test builds Gson fixtures, a hand-made `ErrorContext`, a `GitHubAuth` or `GitHubToken`, or a `GitHubCorpus` in-process, with no Spring context and nothing that waits on the network, so `test` is the complete gate and there is no slow tier. No test sets an environment variable or writes a `.env` file; the one that reads an unset variable names one from a random UUID. A corpus test builds through `GitHubCorpus.Builder.build(reads, writes)` over contracts answered from memory and makes no request; the three `GitHubCorpusBuilderTest` cases that build through `build()` make real clients, which probe `api.github.com` in the background and drop any failure.

## Package Structure

```
github/
├── src/
│   ├── main/java/api/simplified/github/
│   │   ├── GitHubAuth.java                    # Supplier<Optional<String>> for Authorization
│   │   ├── GitHubContentsContract.java        # raw reads + branch tip
│   │   ├── GitHubContentsWriteContract.java   # envelope read + conditional PUT
│   │   ├── GitHubCorpus.java                  # one repository read and written as a corpus, + Builder, Blob
│   │   ├── GitHubGitDataContract.java         # blobs, trees, commits, refs
│   │   ├── GitHubToken.java                   # the personal access token a corpus sends
│   │   ├── ManifestIndex.java                 # the corpus catalogue: revision + layered documents
│   │   ├── exception/                         # GitHubApiException, GitHubErrorResponse
│   │   ├── request/                           # PutContentRequest, CreateBlob/Tree/CommitRequest, UpdateRefRequest
│   │   └── response/                          # GitHubCommit, GitHubContentEnvelope, GitHubPutResponse,
│   │                                          #   GitBlob, GitTree, GitCommit, GitRef
│   └── test/java/                             # Gson round-trip, 403/429 classification, auth and token,
│                                              #   catalogue and GitHubCorpus tests
├── build.gradle.kts  settings.gradle.kts  gradle.properties  gradle/libs.versions.toml
└── LICENSE.md  CONTRIBUTING.md  CLAUDE.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE](LICENSE.md) for the full text.

GitHub and the GitHub logo are trademarks of GitHub, Inc. This library is an independent REST client and is not affiliated with or endorsed by GitHub.
