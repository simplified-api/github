package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.request.PutContentRequest;
import com.google.gson.Gson;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * A document corpus published as files in a GitHub repository.
 *
 * <p>This is the one place the client assembly lives. The two {@code Accept} media types are not
 * interchangeable - the raw one is the only Contents encoding that returns a body above one
 * megabyte, and the JSON one is the only one that returns the envelope carrying the blob sha a
 * write needs - so a second hand-built pair drifts the moment one of them is copied without the
 * other. Nothing stops that except having nothing to copy.
 *
 * <p>Everything here deals in paths, bytes and shas: {@link #read} answers a file's text,
 * {@link #metadata} its blob sha, {@link #write} replaces it, {@link #tip} answers the branch tip
 * and {@link #manifest} the catalogue the corpus publishes. What any of that means to a consumer's
 * types is the consumer's, and nothing about a corpus assumes there is one.
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

    private GitHubCorpus(@NotNull Builder builder) {
        this.owner = builder.owner;
        this.repo = builder.repo;
        this.branch = builder.branch;
        this.manifestPath = builder.manifestPath;
        this.gson = builder.gsonSettings.create();

        GitHubAuth auth = builder.token == null
            ? GitHubAuth.unauthenticated()
            : builder.token.auth();

        this.reads = contract(GitHubContentsContract.class, RAW_ACCEPT, auth, builder.gsonSettings);
        this.writes = contract(GitHubContentsWriteContract.class, JSON_ACCEPT, auth, GsonSettings.defaults());
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
     * Reads the text of one file.
     *
     * @param path the repo-root-relative file path
     * @return the file content, decoded as UTF-8
     * @throws GitHubApiException on any non-2xx status
     */
    public @NotNull String read(@NotNull String path) throws GitHubApiException {
        return new String(this.reads.getFileContent(this.owner, this.repo, path, this.branch), StandardCharsets.UTF_8);
    }

    /**
     * Reads the blob sha of one file, which is the token a write against it has to carry.
     *
     * @param path the repo-root-relative file path
     * @return the git blob sha at the branch tip
     * @throws GitHubApiException on any non-2xx status
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
     * @throws GitHubApiException on any non-2xx status, including the conflict a stale sha raises
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
     * @throws GitHubApiException on any non-2xx status
     */
    public @NotNull String tip() throws GitHubApiException {
        return this.reads.getLatestCommit(this.owner, this.repo, this.branch).getSha();
    }

    /**
     * The corpus catalogue, fetched once and held.
     *
     * @return the catalogue
     * @throws GitHubApiException if the catalogue cannot be fetched
     * @throws IllegalStateException if the repository answers a body that is no catalogue
     */
    public @NotNull ManifestIndex manifest() throws GitHubApiException {
        ManifestIndex held = this.manifest;

        if (held == null) {
            synchronized (this) {
                if (this.manifest == null)
                    this.manifest = this.fetchManifest();

                held = this.manifest;
            }
        }

        return held;
    }

    /**
     * Asks whether the corpus has moved since the held catalogue was taken.
     *
     * <p>One request rules out the whole corpus, because a revision that has not moved cannot have
     * moved any document under it. The answer is a catalogue rather than an instruction: what to do
     * about a change belongs to whoever consumes it, never to whoever noticed.
     *
     * @return the new catalogue, empty when the corpus is still at the held revision
     * @throws GitHubApiException if the tip commit cannot be read
     */
    public @NotNull Optional<ManifestIndex> poll() throws GitHubApiException {
        ManifestIndex held = this.manifest;

        if (held != null && held.getRevision().equals(this.tip()))
            return Optional.empty();

        ManifestIndex fetched = this.fetchManifest();
        this.manifest = fetched;
        return Optional.of(fetched);
    }

    private @NotNull ManifestIndex fetchManifest() throws GitHubApiException {
        ManifestIndex fetched = this.gson.fromJson(this.read(this.manifestPath), ManifestIndex.class);

        if (fetched == null)
            throw new IllegalStateException(
                String.format("'%s/%s' answered no catalogue at '%s'", this.owner, this.repo, this.manifestPath)
            );

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
     * Names the repository, the branch, the catalogue path, the parser and the auth a corpus works
     * under.
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
         * Builds the corpus.
         *
         * <p>No request is issued: the client builds its proxy and its connection pool on the first
         * call, so an unreachable GitHub does not stop a session being configured.
         *
         * @return the corpus
         */
        public @NotNull GitHubCorpus build() {
            return new GitHubCorpus(this);
        }

    }

}
