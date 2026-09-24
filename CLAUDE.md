# github

Feign contracts and Gson DTOs for the GitHub Contents, Commits and Git Data REST APIs, and
`GitHubCorpus`, which builds its two Contents clients through `Client.create` and reads and writes
one repository as a document corpus. Root `api.simplified.github.**`. `simplified-dev/client`
executes every contract here, so a change to a `@RequestLine`, a return type or a required `Accept`
is a change to that framework's contract with GitHub, and to the corpus built on it.

## Build

- Gradle `group` is **`dev.sbs`**, not `api.simplified`. The package root, the group and the JitPack
  coordinate (`com.github.simplified-api:github`) are three different spellings and none derives
  from another.
- `client`, `gson-extras` and `collections` are `api(...)` with inline `strictly()` pins; bump by
  editing the version string in `build.gradle.kts`. Being `api` means a consumer gets `Client`,
  `ClientConfig`, `GsonSettings` and `ConcurrentList` transitively and usually declares none of them.
- `utils` is not declared here. `SystemUtil` and `StringUtil` arrive through the `api` dependency
  `client` and `gson-extras` each carry on it; a `utils` pin bumps with theirs.
- No `jitpack.yml`. JitPack builds this with its own default JDK selection, so a toolchain bump here
  is not a build-config change there.

## Gates

`./gradlew test` is the whole gate. Every test builds a Gson fixture, a hand-made `ErrorContext`, a
`GitHubAuth` or `GitHubToken`, or a `GitHubCorpus` in-process, with no Spring context and nothing
that waits on the network, so there is no slow tier and a green build needs no credentials. No test
sets an environment variable or writes a `.env` file: `SystemUtil` loads its environment map once,
when the class loads, so a variable set later is never read, and the case that needs one unset
names it afresh from a random UUID.

A corpus test hands `GitHubCorpus.Builder.build(reads, writes)` contracts answered from memory; that
terminal makes no client, so such a corpus makes no request at all, and the builder's token is never
applied to the contracts it is handed. A consumer's tests put a corpus over their own doubles
through the same public terminal. Three cases in `GitHubCorpusBuilderTest` build through `build()`,
which makes both real clients: each builds its Feign proxy and starts a background DNS lookup and
`HEAD` probe of `api.github.com` whose failure is dropped, so the suite passes offline but is not
silent on the wire.

That also bounds what green means: the suite proves the declared shapes parse, never that the
endpoint still answers them. A `@RequestLine`, an `Accept` requirement or a return type is verified
by one hand-run call against `api.github.com` and nothing else.

## Gson cannot read these DTOs on its own

`GitTree.tree`, `GitCommit.parents`, `CreateTreeRequest.tree` and `CreateCommitRequest.parents` are
`ConcurrentList`, and `ManifestIndex.documents` is a `ConcurrentMap` of `ConcurrentList` - interfaces
Gson has no built-in binding for. `GsonSettings.defaults()` picks up
`dev.simplified.collection.gson.ConcurrentTypeAdapterFactory` off the classpath through
`ServiceLoader`, and that SPI hop is the only thing that teaches it. A bare `new Gson()` cannot read
those fields and reads every other, so the failure looks like a Git Data or catalogue problem rather
than a Gson-construction problem.

Every response DTO's constructor is private and every request DTO's is package-private. A response
DTO is built reflectively by the decoder, a request DTO through the `builder()` its `@ClassBuilder`
generates; a compile error reaching for a constructor is the design working.

## Tokens

- `GitHubAuth.bearer` takes a `@Nullable` token and degrades a null or empty one to
  `unauthenticated()`, so `System.getenv` passes straight through. The test is `StringUtil.isEmpty`,
  not `isBlank`: a token of whitespace alone is sent.
- `GitHubToken.of` reads through `SystemUtil.getEnv`, matching the name case-insensitively over the
  OS environment laid on two `.env` files - the class-loader resource at `../.env` and the file
  beside the jar or class directory `SystemUtil` was loaded from. A missing or empty value throws
  `IllegalStateException` where the token is made; a whitespace-only one is taken and sent to
  GitHub. `GitHubToken.value` refuses a null or empty token the same way.

## The Accept split

The two Contents surfaces are siblings, not one interface with an extension, because `ClientConfig`
carries a single static header set and they need different `Accept` values.

- `GitHubContentsContract` pins `application/vnd.github.raw+json`. It is the only Contents encoding
  that returns the body of a file between 1 and 100 MB, and GitHub supports none above 100 MB. The
  default media type is documented only up to 1 MB; GitHub does not say what it answers beyond.
- `GitHubContentsWriteContract` pins `application/vnd.github+json` so `GET` returns the envelope
  whose `sha` is the write token and `PUT` accepts a JSON body.
- Two contracts therefore means two `Client` instances. Merging them, or adding a method needing a
  third media type to either, silently changes what the existing methods return rather than failing.

`getFileContent` returns `byte[]` and not `String`: the framework's response decoder attempts a raw
JSON parse when the declared return type is `String`, which fails on any JSON-object file body.
Routing through the binary-body decoder is what avoids that path.

## Refs

Every request line takes its ref as a parameter - `commits/{branch}`, `contents/{path}?ref={ref}` on
the read contract and `?ref={branch}` on the write contract - and `PutContentRequest.branch` names
the branch a write commits to. `GitHubCorpus` hands its one branch to the tip read, the blob-sha read
and the write, so the concurrency token is read off the branch it is checked against.

`GitHubCorpus` reads its catalogue at a commit sha - the tip it just resolved - and never at the
branch. A branch read can be replayed from the client's response cache for up to a minute after the
branch moves; a commit names content that never changes. `poll()` compares the tip against the tip
the held catalogue was read at, not against the catalogue's `revision`, which the generator records
before the catalogue is committed and so never equals a tip.

`getLatestCommit` uses the single-commit-by-ref endpoint. Do not switch it to
`/commits?sha={branch}&per_page=1` - the listing endpoint is served from GitHub's 60-second edge cache
and answers a stale SHA for up to a minute after a push, where the by-ref form resolves through the
git ref lookup.

## Optimistic concurrency, two shapes

- Contents: `getFileMetadata` yields the blob SHA, `putFileContent` sends it back, GitHub rejects a
  stale one as `409`/`422`, which reaches the caller as `GitHubApiException` carrying that status -
  the framework raises `PreconditionFailedException` only for a `412`. One commit per successful
  `PUT`, so N files is N commits.
- Git Data: `updateRef` with `force` null or `false` makes GitHub run the fast-forward check. Same
  guarantee one level up, and N files land as one commit.

The blob SHA a Contents write carries is only as good as its pairing with the text the caller
edited. `GitHubCorpus.blob` reads the raw bytes at the branch and computes the SHA from them - SHA-1
over `blob <length>\0` and the bytes, as `git hash-object` does - so the two always name the same
content, and a body the response cache replays from before the branch moved carries its own stale
SHA and the `PUT` is refused rather than landing over the newer commit. `metadata` reads the SHA
through the write client, whose cache is its own, so text read beside it can be older than the SHA
it is written under.

`PutContentRequest.sha` is annotated `@NotNull`, but nothing enforces it -
`org.jetbrains.annotations.NotNull` is static-analysis only, and `@ClassBuilder`'s generated
`build()` checks only what a `@BuildFlag` declares, which no request type here does. Creating a file
that does not exist yet requires the field absent, and the builder will pass null through. The
annotation records the intended path, not a runtime guard.

## Failure classification

`GitHubApiException` is constructed by the framework's error decoder, so its one
`(Gson, ErrorContext)` constructor is fixed by the `ClientConfig.withErrorDecoder` method-reference
shape. The five-constructor exception pattern does not apply to it.

- A `304` never reaches it. `InternalErrorDecoder` short-circuits 3xx into `NotModifiedException`
  before any per-client decoder runs, and the conditional-request machinery replays the cached body.
  Do not add a not-modified branch here.
- Nor does a `412` or a `429`: the same decoder raises `PreconditionFailedException` and
  `RateLimitException` for them first. The rate-limit predicates accept a `429`, but in practice
  they only ever classify a `403`.
- `RateLimitException` also arrives with nothing sent. `InternalRequestInterceptor` refuses a
  request once the client's `api.github.com` bucket - set from the `X-RateLimit-*` headers of live
  responses - has none left before GitHub's reset, so after one primary-limit `403` the rest of the
  window fails that way. Every contract and corpus method's `@throws` says both.
- `isPrimaryRateLimit` requires `x-ratelimit-remaining: 0` **and** the message text together. Either
  signal alone moves when GitHub changes its wording or its header set; the conjunction does not.
  Loosening it to one signal makes a permissions 403 read as a rate limit.
- `isPermissions` is defined by exclusion, so it changes meaning whenever either rate-limit
  predicate changes. Treat the three as one decision.
- `GitHubErrorResponse`'s field initializers are the live fallback, not defensive decoration -
  `JsonApiException` constructs a fresh instance reflectively when the body is absent or not JSON,
  and those initializers are what the caller then reads.

## Naming collisions to expect

- `GitRef.Object` is a nested class named `Object`. Inside `GitRef` the simple name resolves to it
  and not to `java.lang.Object`; a method there taking or returning `Object` means the git object.
- `GitCommit` (Git Data) and `GitHubCommit` (Commits REST) are two different commit envelopes for
  the same commit. Git Data is narrower and its `Actor` carries no HTML URLs. Neither converts to
  the other.
- `GitBlob`'s `size`, `content` and `encoding` are null on the `POST /blobs` create response and
  populated only on `GET /blobs/{sha}`, which this contract does not declare. A create response
  carrying only `sha` and `url` is correct.

## Unused by design

Nothing in this repository calls `GitHubGitDataContract`, and no module in the workspace does
either. It ships fully round-trip tested so the first consumer does not also have to discover that
GitHub's envelope drifted.

Declare only the fields a consumer reads - Gson ignores the rest, and every declared field is one
more thing that can drift. Optional upstream fields are boxed and `@Nullable` so a missing `size`
stays distinguishable from `0`.

## Skip these

- `build/` - Gradle output.
- `.gradle/` - Gradle daemon state.

## Decisions that stay closed

- Do not merge the read and write Contents contracts. The `Accept` header is per-client and the two
  need different values; a merged interface makes one of the two surfaces silently wrong.
- Do not change `getFileContent` to return `String`. The decoder's JSON path is reached by return
  type, so the change is invisible until a caller reads a `.json` file.
- Do not record test fixtures from live responses. Fixtures are hand-narrowed from GitHub's public
  documentation pages precisely so no account-specific value reads as meaningful, and so no captured
  `Authorization` header can reach the tree.
- Do not add a not-modified return type to any contract method. Conditional requests are handled
  below the contract and a `304` is never a value a method sees.
- Do not open `GitHubCorpus`'s constructors. A caller putting its own contracts under a corpus uses
  `Builder.build(reads, writes)`, which is public API; a constructor a consumer reaches by sharing
  the package breaks in that consumer's compile rather than here.
