package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.request.PutContentRequest;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.exception.PreconditionFailedException;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.request.Contract;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * A document corpus published as files in a GitHub repository.
 *
 * <p>This is the one place the client assembly lives. The two {@code Accept} media types are not
 * interchangeable - the raw one is the only Contents encoding that returns a body above one
 * megabyte, and the JSON one is the only one that returns the envelope {@link #metadata} reads and
 * the one a write is sent under - so a second hand-built pair drifts the moment one of them is
 * copied without the other. Nothing stops that except having nothing to copy.
 *
 * <p>Everything here deals in paths, bytes and shas: {@link #read} answers a file's text at the
 * branch or at a named commit, {@link #blob} its text together with the blob sha of the bytes read,
 * {@link #metadata} its blob sha, {@link #write} replaces it, {@link #tip} answers the branch tip,
 * {@link #manifest} the catalogue the corpus publishes and {@link #manifestCommit} the commit that
 * catalogue was read at, and {@link #poll} replaces the held catalogue once the branch moves. What
 * any of that means to a consumer's types is the consumer's, and nothing about a corpus assumes
 * there is one.
 *
 * <p>The catalogue is always read at a commit sha rather than at the branch. A branch read can be
 * answered from the client's response cache for up to a minute after the branch moves, where a
 * commit names content that never changes.
 *
 * <p>The failures each request method documents are those of the clients {@link Builder#build()}
 * makes, which raise {@link NotModifiedException} for a 3xx status,
 * {@link PreconditionFailedException} for a 412, {@link RateLimitException} for a 429 and
 * {@link GitHubApiException} for any other non-2xx status. Each client also raises
 * {@link RateLimitException} before a request is sent, when its rate-limit gate refuses it: the gate
 * keeps one bucket for {@code api.github.com}, set from the {@code X-RateLimit-Limit},
 * {@code X-RateLimit-Remaining} and {@code X-RateLimit-Reset} headers of GitHub's live responses,
 * and refuses every request once the bucket has none left, until the reset GitHub named. A corpus
 * built over contracts the caller supplies raises whatever those contracts raise.
 *
 * <p>A catalogue body that is empty or the JSON literal {@code null} raises
 * {@link IllegalStateException}, and one that is malformed or does not read as a catalogue object
 * raises Gson's {@link JsonSyntaxException}; either way the corpus keeps the catalogue it held
 * before, if any.
 *
 * @see GitHubToken
 * @see ManifestIndex
 */
public final class GitHubCorpus {

    private static final @NotNull String API_VERSION = "2022-11-28";
    private static final @NotNull String RAW_ACCEPT = "application/vnd.github.raw+json";
    private static final @NotNull String JSON_ACCEPT = "application/vnd.github+json";

    private final @NotNull String owner;
    private final @NotNull String repo;
    private final @NotNull String branch;
    private final @NotNull String manifestPath;
    private final @NotNull Gson gson;
    private final @NotNull GitHubContentsContract reads;
    private final @NotNull GitHubContentsWriteContract writes;

    /**
     * The catalogue held from the last fetch, or {@code null} before one.
     */
    private volatile @Nullable ManifestIndex manifest;

    /**
     * The branch tip the held catalogue was read at, or {@code null} before one.
     */
    private volatile @Nullable String manifestCommit;

    private GitHubCorpus(@NotNull Builder builder) {
        this(builder, builder.token == null ? GitHubAuth.unauthenticated() : builder.token.auth());
    }

    private GitHubCorpus(@NotNull Builder builder, @NotNull GitHubAuth auth) {
        this(
            builder,
            contract(GitHubContentsContract.class, RAW_ACCEPT, auth, builder.gsonSettings),
            contract(GitHubContentsWriteContract.class, JSON_ACCEPT, auth, GsonSettings.defaults())
        );
    }

    /**
     * Constructs a corpus answering every request through the given contracts, whether the builder
     * made them or the caller supplied them.
     *
     * @param builder the repository, branch, catalogue path and parser settings
     * @param reads the contract files and the branch tip are read through
     * @param writes the contract blob shas are read and files are written through
     */
    private GitHubCorpus(
        @NotNull Builder builder,
        @NotNull GitHubContentsContract reads,
        @NotNull GitHubContentsWriteContract writes
    ) {
        this.owner = builder.owner;
        this.repo = builder.repo;
        this.branch = builder.branch;
        this.manifestPath = builder.manifestPath;
        this.gson = builder.gsonSettings.create();
        this.reads = reads;
        this.writes = writes;
    }

    /**
     * Names the repository a corpus is published from.
     *
     * <p>The generic contracts take the owner and the repo as method parameters so one proxy serves
     * any number of repositories; this binds them once, in the module that owns the parameter.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @return a builder over that repository
     */
    public static @NotNull Builder of(@NotNull String owner, @NotNull String repo) {
        return new Builder(owner, repo);
    }

    /**
     * The branch every request is made against.
     *
     * @return the branch name
     */
    public @NotNull String getBranch() {
        return this.branch;
    }

    /**
     * Reads the text of one file at the branch.
     *
     * <p>The client's response cache can answer this from before the branch moved, for up to a
     * minute. A caller that needs the text exactly as some commit holds it reads at that commit.
     *
     * @param path the repo-root-relative file path
     * @return the file content, decoded as UTF-8
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    public @NotNull String read(@NotNull String path) throws GitHubApiException {
        return this.read(path, this.branch);
    }

    /**
     * Reads the text of one file at a commit or a ref.
     *
     * <p>A commit sha names content that never changes, so a read at one answers the same text
     * however long the client's response cache holds it.
     *
     * @param path the repo-root-relative file path
     * @param ref the commit sha, branch or tag to read at
     * @return the file content, decoded as UTF-8
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    public @NotNull String read(@NotNull String path, @NotNull String ref) throws GitHubApiException {
        return new String(this.reads.getFileContent(this.owner, this.repo, path, ref), StandardCharsets.UTF_8);
    }

    /**
     * Reads the text of one file at the branch together with its blob sha.
     *
     * <p>The sha is computed from the bytes the text was decoded from, the way git names a blob, so
     * the two always describe the same content and a write under the sha lands only over the body
     * the caller read. A body the client's response cache replays from before the branch moved
     * carries that older body's sha, and GitHub refuses a write under it rather than letting it
     * overwrite the newer commit.
     *
     * @param path the repo-root-relative file path
     * @return the file's text and the blob sha of its bytes, read at the branch
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    public @NotNull Blob blob(@NotNull String path) throws GitHubApiException {
        byte[] bytes = this.reads.getFileContent(this.owner, this.repo, path, this.branch);
        return new Blob(new String(bytes, StandardCharsets.UTF_8), blobSha(bytes));
    }

    /**
     * Reads the blob sha of one file, which is the token a write against it has to carry.
     *
     * @param path the repo-root-relative file path
     * @return the git blob sha at the branch tip
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    public @NotNull String metadata(@NotNull String path) throws GitHubApiException {
        return this.writes.getFileMetadata(this.owner, this.repo, path, this.branch).getSha();
    }

    /**
     * Replaces the text of one file as a single commit.
     *
     * <p>The blob sha is the optimistic-concurrency token: GitHub refuses the write when the file
     * has moved since that sha was read, which is what makes a lost update a failure rather than a
     * silent overwrite.
     *
     * @param path the repo-root-relative file path
     * @param content the text to commit
     * @param sha the blob sha the caller expects the file to still carry
     * @param message the commit message
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429, including the
     *         409 or 422 a stale sha raises
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    public void write(
        @NotNull String path,
        @NotNull String content,
        @NotNull String sha,
        @NotNull String message
    ) throws GitHubApiException {
        this.writes.putFileContent(
            this.owner,
            this.repo,
            path,
            PutContentRequest.builder()
                .message(message)
                .content(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)))
                .sha(sha)
                .branch(this.branch)
                .build()
        );
    }

    /**
     * Reads the commit the branch currently points at.
     *
     * @return the tip commit sha
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    public @NotNull String tip() throws GitHubApiException {
        return this.reads.getLatestCommit(this.owner, this.repo, this.branch).getSha();
    }

    /**
     * The corpus catalogue, fetched once and held until {@link #poll()} replaces it.
     *
     * <p>The first fetch reads the branch tip and then the catalogue at that commit, and holds both,
     * so {@link #manifestCommit()} names the commit the catalogue came from.
     *
     * @return the catalogue
     * @throws GitHubApiException if the tip or the catalogue read answers a non-2xx status other
     *         than a 3xx, a 412 or a 429
     * @throws NotModifiedException if either read answers a 3xx status
     * @throws PreconditionFailedException if either read answers a 412 status
     * @throws RateLimitException if either read answers a 429 status, or the client's rate-limit gate
     *         refuses either before it is sent
     * @throws IllegalStateException if the catalogue body is empty or the JSON literal {@code null}
     * @throws JsonSyntaxException if the catalogue body is malformed or does not read as a catalogue
     */
    public @NotNull ManifestIndex manifest() throws GitHubApiException {
        ManifestIndex held = this.manifest;

        if (held == null) {
            synchronized (this) {
                if (this.manifest == null)
                    this.hold(this.tip());

                held = this.manifest;
            }
        }

        return held;
    }

    /**
     * The branch tip the held catalogue was read at, fetching the catalogue first when none is held.
     *
     * <p>A file read at this commit comes out of the same tree the held catalogue did, however long
     * the client's response cache keeps the answer.
     *
     * @return the commit sha
     * @throws GitHubApiException if the tip or the catalogue read answers a non-2xx status other
     *         than a 3xx, a 412 or a 429
     * @throws NotModifiedException if either read answers a 3xx status
     * @throws PreconditionFailedException if either read answers a 412 status
     * @throws RateLimitException if either read answers a 429 status, or the client's rate-limit gate
     *         refuses either before it is sent
     * @throws IllegalStateException if the catalogue body is empty or the JSON literal {@code null}
     * @throws JsonSyntaxException if the catalogue body is malformed or does not read as a catalogue
     */
    public @NotNull String manifestCommit() throws GitHubApiException {
        this.manifest();
        return Objects.requireNonNull(this.manifestCommit);
    }

    /**
     * Asks whether the branch has moved since the held catalogue was read.
     *
     * <p>One request rules out the whole corpus: a tip that has not moved cannot have moved any file
     * under it. When it has, the catalogue is read at the new tip - a commit, which the client's
     * response cache cannot answer from before the move - and held with that tip. The answer is a
     * catalogue rather than an instruction: what to do about a change belongs to whoever consumes it,
     * never to whoever noticed.
     *
     * @return the new catalogue, empty when the branch is still at the tip the held one was read at
     * @throws GitHubApiException if the tip or the catalogue read answers a non-2xx status other
     *         than a 3xx, a 412 or a 429
     * @throws NotModifiedException if either read answers a 3xx status
     * @throws PreconditionFailedException if either read answers a 412 status
     * @throws RateLimitException if either read answers a 429 status, or the client's rate-limit gate
     *         refuses either before it is sent
     * @throws IllegalStateException if the catalogue body is empty or the JSON literal {@code null}
     * @throws JsonSyntaxException if the catalogue body is malformed or does not read as a catalogue
     */
    public synchronized @NotNull Optional<ManifestIndex> poll() throws GitHubApiException {
        String tip = this.tip();

        if (this.manifest != null && tip.equals(this.manifestCommit))
            return Optional.empty();

        return Optional.of(this.hold(tip));
    }

    /**
     * Reads the catalogue at one commit and holds it with that commit.
     *
     * <p>The commit is published before the catalogue, so a reader that finds a catalogue held also
     * finds the commit it was read at.
     *
     * @param commit the commit sha to read the catalogue at
     * @return the catalogue now held
     * @throws GitHubApiException if the catalogue read answers a non-2xx status other than a 3xx, a
     *         412 or a 429
     * @throws NotModifiedException if the catalogue read answers a 3xx status
     * @throws PreconditionFailedException if the catalogue read answers a 412 status
     * @throws RateLimitException if the catalogue read answers a 429 status, or the client's rate-limit
     *         gate refuses it before it is sent
     * @throws IllegalStateException if the catalogue body is empty or the JSON literal {@code null}
     * @throws JsonSyntaxException if the catalogue body is malformed or does not read as a catalogue
     */
    private @NotNull ManifestIndex hold(@NotNull String commit) throws GitHubApiException {
        ManifestIndex fetched = this.gson.fromJson(this.read(this.manifestPath, commit), ManifestIndex.class);

        if (fetched == null)
            throw new IllegalStateException(
                String.format("'%s/%s' answered no catalogue at '%s'", this.owner, this.repo, this.manifestPath)
            );

        this.manifestCommit = commit;
        this.manifest = fetched;
        return fetched;
    }

    /**
     * Builds one Feign proxy with the auth, media type, API version and error decoding every call
     * against this API needs.
     */
    private static <C extends Contract> @NotNull C contract(
        @NotNull Class<C> contract,
        @NotNull String accept,
        @NotNull GitHubAuth auth,
        @NotNull GsonSettings gsonSettings
    ) {
        Client<C> client = Client.create(
            ClientConfig.builder(contract, gsonSettings)
                .withHeader("Accept", accept)
                .withHeader("X-GitHub-Api-Version", API_VERSION)
                .withDynamicHeader("Authorization", auth)
                .withErrorDecoder(GitHubApiException::new)
                .build()
        );

        return client.getContract();
    }

    /**
     * Names bytes the way git names a blob - SHA-1 over {@code blob}, a space, the byte length, a
     * NUL and the bytes themselves.
     *
     * @param bytes the blob's content
     * @return the sha as lowercase hex
     */
    private static @NotNull String blobSha(byte @NotNull [] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Every Java platform implements SHA-1", exception);
        }
    }

    /**
     * One file's text with the git blob sha of the bytes it was decoded from.
     *
     * @param text the file content, decoded as UTF-8
     * @param sha the git blob sha of the bytes the text was decoded from, the token a write replacing
     *        exactly this content carries
     */
    public record Blob(@NotNull String text, @NotNull String sha) {}

    /**
     * Names the repository, the branch, the catalogue path, the parser and the auth a corpus works
     * under, and builds it over clients of its own or over contracts the caller supplies.
     */
    public static final class Builder {

        private final @NotNull String owner;
        private final @NotNull String repo;
        private @NotNull String branch = "master";
        private @NotNull String manifestPath = "index.json";
        private @NotNull GsonSettings gsonSettings = GsonSettings.defaults();
        private @Nullable GitHubToken token;

        private Builder(@NotNull String owner, @NotNull String repo) {
            this.owner = owner;
            this.repo = repo;
        }

        /**
         * Names the branch every request is made against.
         *
         * @param branch the branch name
         * @return this builder
         */
        public @NotNull Builder branch(@NotNull String branch) {
            this.branch = branch;
            return this;
        }

        /**
         * Names the catalogue's path within the repository.
         *
         * @param path the repo-root-relative path of the catalogue
         * @return this builder
         */
        public @NotNull Builder manifest(@NotNull String path) {
            this.manifestPath = path;
            return this;
        }

        /**
         * Names the settings documents and the catalogue are parsed with.
         *
         * @param settings the parser settings
         * @return this builder
         */
        public @NotNull Builder gson(@NotNull GsonSettings settings) {
            this.gsonSettings = settings;
            return this;
        }

        /**
         * Names the token every request carries.
         *
         * <p>A write needs one. A read does not, but an unauthenticated one is capped at sixty
         * requests an hour per address, so a caller doing more than a handful supplies one anyway.
         *
         * @param token the personal access token
         * @return this builder
         */
        public @NotNull Builder token(@NotNull GitHubToken token) {
            this.token = token;
            return this;
        }

        /**
         * Builds the corpus over two clients it makes, each carrying the token when one is named.
         *
         * <p>Nothing here waits on GitHub. Each of the two clients builds its proxy and connection
         * pool at once and starts a DNS lookup and a {@code HEAD} probe of {@code api.github.com}
         * on a background thread, whose failure is dropped, so an unreachable GitHub does not stop
         * a session being configured - the first read or write is what reports it.
         *
         * @return the corpus
         */
        public @NotNull GitHubCorpus build() {
            return new GitHubCorpus(this);
        }

        /**
         * Builds the same corpus as {@link #build()}, answering every request through the given
         * contracts rather than through clients it makes.
         *
         * <p>The two contracts replace the two clients, and with them everything a client carries:
         * the {@code Accept} media type each is pinned to, the API version header, the settings a
         * response is decoded with, the error decoding into {@link GitHubApiException}, and the
         * auth. The token named on this builder is not applied to them - the contracts carry
         * whatever auth they need. The repository, the branch, the catalogue path and the settings
         * the catalogue is parsed with still come from this builder, and no client is made, so
         * building starts no probe of {@code api.github.com}.
         *
         * <p>This serves a caller answering the requests itself - an in-memory double, a recording
         * or caching proxy, a gateway. Whatever answers them answers as the clients do:
         * {@code reads} returns a file as its raw bytes, whatever its size, and {@code writes}
         * returns the envelope a blob sha is read from and takes the body a file is written with.
         *
         * @param reads the contract files and the branch tip are read through
         * @param writes the contract blob shas are read and files are written through
         * @return the corpus
         */
        public @NotNull GitHubCorpus build(
            @NotNull GitHubContentsContract reads,
            @NotNull GitHubContentsWriteContract writes
        ) {
            return new GitHubCorpus(this, reads, writes);
        }

    }

}
