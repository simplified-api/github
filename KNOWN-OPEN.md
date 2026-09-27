# Known open

Open items in `github`. Each stays here until it is closed or accepted.

> #### The read contract promises conditional requests and 304 replays that never happen
> `GitHubContentsContract`'s class javadoc says the client attaches `If-None-Match` to a `GET` when
> it holds a matching cached response and replays the cached body on a `304`. Neither happens
> against GitHub. An answer to a request carrying a token names `Cookie` in its `Vary`, and the
> client stores no response whose `Vary` names a `Cookie` the request does not carry. An answer to
> an unauthenticated request is stored for GitHub's `max-age=60`, but it carries no
> `stale-if-error` window, and the client's cache holds an entry only until its freshness plus that
> window has passed, so it is gone once stale and never revalidated. The contract's methods and
> the failures they document are unaffected.
>
> - Affected: `src/main/java/api/simplified/github/GitHubContentsContract.java:30-33` - class javadoc
> - Type: **GAP**
> - Status: **OPEN**

> #### The corpus's cache docs describe a token-holding corpus's reads as cached
> `GitHubCorpus`'s class javadoc, and those of `tip`, `read`, `blob`, `metadata`, `write` and
> `poll`, say a read at the branch can be answered from the client's response cache for GitHub's
> `max-age`, so another writer's commit can go unseen for up to a minute, and that a write drops
> both clients' caches so the corpus's own reads after it reach GitHub. That holds for a corpus
> reading without a token, whose answers GitHub sends with a `Vary` of
> `Accept, Accept-Encoding, X-Requested-With`. With a token, GitHub's `Vary` also names
> `Authorization` and `Cookie`, and the client stores no response whose `Vary` names a `Cookie` the
> request does not carry, so a token-holding corpus - the only kind that writes - caches nothing:
> every read reaches GitHub, and the drop after a write has nothing to drop. CLAUDE.md says the
> same, and skyblock's `CorpusOrigin` and `SkyBlockData.writing` docs repeat it for the corpus's
> writer.
>
> - Affected: `src/main/java/api/simplified/github/GitHubCorpus.java:45-55` - class javadoc, `:158` -
>   `read`, `:202` - `blob`, `:224` - `metadata`, `:252` - `write`, `:292` - `tip`, `:365` - `poll`;
>   `CLAUDE.md:97-101`;
>   `Simplified-Api/skyblock/src/main/java/api/simplified/skyblock/CorpusOrigin.java:46-49` -
>   `writing`, `:109-114` - `refreshedLayersOf`, `:186-190` - `edit`; `Simplified-Api/skyblock/src/main/java/api/simplified/skyblock/SkyBlockData.java:156-161`
> - Type: **GAP**
> - Status: **OPEN**
