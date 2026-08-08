# github

Feign contracts and Gson DTOs for the GitHub Contents, Commits and Git Data REST APIs. Root
`api.simplified.github.**`. Ships no client - `simplified-dev/client` executes every contract here,
so a change to a `@RequestLine`, a return type or a required `Accept` is a change to that
framework's contract with GitHub.

## Build

- Gradle `group` is **`dev.sbs`**, not `api.simplified`. The package root, the group and the JitPack
  coordinate (`com.github.simplified-api:github`) are three different spellings and none derives
  from another.
- `client` and `gson-extras` are `api(...)` with inline `strictly()` pins; bump by editing the
  version string in `build.gradle.kts`. Being `api` means a consumer gets `Client`, `ClientConfig`,
  `GsonSettings` and `ConcurrentList` transitively and usually declares none of them.
- No `jitpack.yml`. JitPack builds this with its own default JDK selection, so a toolchain bump here
  is not a build-config change there.

## Gates

`./gradlew test` is the whole gate. Every test builds a Gson fixture or a hand-made `ErrorContext`
in-process - no network, no Feign proxy, no Spring context - so there is no slow tier and a green
build needs no credentials.

That also bounds what green means: the suite proves the declared shapes parse, never that the
endpoint still answers them. A `@RequestLine`, an `Accept` requirement or a return type is verified
by one hand-run call against `api.github.com` and nothing else.

## Gson cannot read these DTOs on its own

`GitTree.tree`, `GitCommit.parents` and `CreateTreeRequest.tree` are `ConcurrentList`, an interface
Gson has no built-in binding for. `GsonSettings.defaults()` picks up
`dev.simplified.collection.gson.ConcurrentTypeAdapterFactory` off the classpath through
`ServiceLoader`, and that SPI hop is the only thing that teaches it. A bare `new Gson()` fails on
those three fields and on nothing else, so the failure looks like a Git Data problem rather than a
Gson-construction problem.

Every response DTO's constructor is private and every request DTO's is package-private. They are
built reflectively by the decoder; a compile error reaching for a constructor is the design working.

## The Accept split

The two Contents surfaces are siblings, not one interface with an extension, because `ClientConfig`
carries a single static header set and they need different `Accept` values.

- `GitHubContentsContract` pins `application/vnd.github.raw+json`. It is the only Contents encoding
  that returns a raw body for a file over 1 MB; without it the endpoint answers a base64 envelope
  capped at 1 MB and rejects anything larger.
- `GitHubContentsWriteContract` pins `application/vnd.github+json` so `GET` returns the envelope
  whose `sha` is the write token and `PUT` accepts a JSON body.
- Two contracts therefore means two `Client` instances. Merging them, or adding a method needing a
  third media type to either, silently changes what the existing methods return rather than failing.

`getFileContent` returns `byte[]` and not `String`: the framework's response decoder attempts a raw
JSON parse when the declared return type is `String`, which fails on any JSON-object file body.
Routing through the binary-body decoder is what avoids that path.

## Branch handling is not uniform

Every read request line hardcodes `master` - `commits/master`, `contents/{path}?ref=master` on both
Contents contracts. Only `PutContentRequest.branch` is parameterised.

Setting that field to anything but `master` reads the blob SHA off master and writes it against a
different branch's tip, so the concurrency token is checked against a file it was never read from.
Either leave `branch` unset or parameterise the `ref` on `getFileMetadata` in the same change.

`getLatestMasterCommit` uses the single-commit-by-ref endpoint. Do not switch it to
`/commits?sha=master&per_page=1` - the listing endpoint is served from GitHub's 60-second edge cache
and answers a stale SHA for up to a minute after a push, where the by-ref form resolves through the
git ref lookup.

## Optimistic concurrency, two shapes

- Contents: `getFileMetadata` yields the blob SHA, `putFileContent` sends it back, GitHub rejects a
  stale one as `409`/`422`, which the framework maps to `PreconditionFailedException`. One commit per
  successful `PUT`, so N files is N commits.
- Git Data: `updateRef` with `force` null or `false` makes GitHub run the fast-forward check. Same
  guarantee one level up, and N files land as one commit.

`PutContentRequest.sha` is annotated `@NotNull`, but nothing enforces it - Lombok null-checks only
its own `@NonNull`, and `org.jetbrains.annotations.NotNull` is static-analysis only. Creating a file
that does not exist yet requires the field absent, and the builder will pass null through. The
annotation records the intended path, not a runtime guard.

## Failure classification

`GitHubApiException` is constructed by the framework's error decoder, so its one
`(Gson, ErrorContext)` constructor is fixed by the `ClientConfig.withErrorDecoder` method-reference
shape. The five-constructor exception pattern does not apply to it.

- A `304` never reaches it. `InternalErrorDecoder` short-circuits 3xx into `NotModifiedException`
  before any per-client decoder runs, and the conditional-request machinery replays the cached body.
  Do not add a not-modified branch here.
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

Nothing in this repository calls `GitHubGitDataContract`. It ships fully round-trip tested so the
first consumer does not also have to discover that GitHub's envelope drifted; the pre-bound
consumer-side façade lives in the `skyblock` sibling as `SkyBlockGitDataContract`.

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
