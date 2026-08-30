package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.request.PutContentRequest;
import api.simplified.github.response.GitHubContentEnvelope;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.request.Contract;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.persistence.JpaModel;
import dev.simplified.persistence.exception.JpaException;
import dev.simplified.persistence.store.FileFetcher;
import dev.simplified.persistence.store.ManifestIndex;
import dev.simplified.persistence.store.Source;
import dev.simplified.persistence.store.WriteRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
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
 * <p>Reading and writing are separate capabilities. {@link #reading()} answers a {@link Source};
 * {@link #writing(GitHubToken)} answers a {@link Source.Writable}, and omitting the token is how an
 * origin a caller may read but not update is expressed.
 *
 * @see GitHubToken
 */
public final class GitHubCorpus {

    private static final @NotNull String API_VERSION = "2022-11-28";
    private static final @NotNull String RAW_ACCEPT = "application/vnd.github.raw+json";
    private static final @NotNull String JSON_ACCEPT = "application/vnd.github+json";

    /**
     * The branch every contract's request line pins.
     */
    private static final @NotNull String BRANCH = "master";

    private final @NotNull String owner;
    private final @NotNull String repo;
    private final @NotNull String manifestPath;
    private final @NotNull Gson gson;
    private final @NotNull GitHubContentsContract read;

    /**
     * The catalogue held from the last fetch, or {@code null} before one.
     */
    private volatile @Nullable ManifestIndex manifest;

    private GitHubCorpus(@NotNull Builder builder) {
        this.owner = builder.owner;
        this.repo = builder.repo;
        this.manifestPath = builder.manifestPath;
        this.gson = builder.gsonSettings.create();
        this.read = contract(GitHubContentsContract.class, RAW_ACCEPT, GitHubAuth.unauthenticated(), builder.gsonSettings);
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
     * The corpus catalogue, fetched once and held.
     *
     * @return the catalogue
     * @throws JpaException if the catalogue cannot be fetched or parsed
     */
    public @NotNull ManifestIndex manifest() throws JpaException {
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
     * about a change belongs to whoever hydrates, never to whoever noticed.
     *
     * @return the new catalogue, empty when the corpus is still at the held revision
     * @throws JpaException if the tip commit cannot be read
     */
    public @NotNull Optional<ManifestIndex> poll() throws JpaException {
        ManifestIndex held = this.manifest;

        try {
            String tip = this.read.getLatestMasterCommit(this.owner, this.repo).getSha();

            if (held != null && held.getRevision().equals(tip))
                return Optional.empty();
        } catch (GitHubApiException exception) {
            throw failed(exception, "read the tip commit of '%s/%s'", this.owner, this.repo);
        }

        ManifestIndex fetched = this.fetchManifest();
        this.manifest = fetched;
        return Optional.of(fetched);
    }

    /**
     * The read half of the corpus.
     *
     * @return a source reading each type out of the layers the catalogue names for it
     */
    public @NotNull Source reading() {
        return Source.documents(this::manifest, this.fetcher(), this.gson);
    }

    /**
     * The write half of the corpus, for a caller holding an instruction to update it.
     *
     * @param token the write instruction
     * @return a source that also writes
     */
    public @NotNull Source.Writable writing(@NotNull GitHubToken token) {
        return new Commits(token);
    }

    /**
     * Reads one layer's bytes off the corpus.
     */
    private @NotNull FileFetcher fetcher() {
        return path -> {
            try {
                return new String(this.read.getFileContent(this.owner, this.repo, path), StandardCharsets.UTF_8);
            } catch (GitHubApiException exception) {
                throw failed(exception, "read '%s' from '%s/%s'", path, this.owner, this.repo);
            }
        };
    }

    private @NotNull ManifestIndex fetchManifest() throws JpaException {
        String body = this.fetcher().fetchFile(this.manifestPath);
        ManifestIndex fetched = this.gson.fromJson(body, ManifestIndex.class);

        if (fetched == null)
            throw new JpaException("'%s/%s' answered no catalogue at '%s'", this.owner, this.repo, this.manifestPath);

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

    private static @NotNull JpaException failed(
        @NotNull GitHubApiException exception,
        @NotNull String what,
        @NotNull Object... args
    ) {
        return new JpaException(
            exception,
            "Failed to " + String.format(what, args) + " (HTTP %d): %s",
            exception.getStatus().getCode(),
            exception.getResponse().getReason()
        );
    }

    /**
     * The write half: a whole layer rewritten as one commit.
     */
    private final class Commits implements Source.Writable {

        private final @NotNull Source reads = GitHubCorpus.this.reading();
        private final @NotNull GitHubContentsWriteContract writes;

        private Commits(@NotNull GitHubToken token) {
            this.writes = contract(
                GitHubContentsWriteContract.class,
                JSON_ACCEPT,
                token.auth(),
                GsonSettings.defaults()
            );
        }

        /** {@inheritDoc} */
        @Override
        public <T extends JpaModel> @NotNull ConcurrentList<T> read(@NotNull Class<T> type) throws JpaException {
            return this.reads.read(type);
        }

        /**
         * {@inheritDoc}
         *
         * <p>A document is a whole file, so a write is: read the layers, apply the rows to the
         * merged result, and PUT the first layer carrying all of it. Granularity is the origin's
         * problem rather than the caller's, and here the origin's granularity is the file.
         */
        @Override
        public <T extends JpaModel> void write(@NotNull WriteRequest<T> request) throws JpaException {
            if (request.rows().isEmpty())
                return;

            ConcurrentList<ManifestIndex.Layer> layers = GitHubCorpus.this.manifest()
                .layersOf(JpaModel.documentOf(request.type()));

            if (layers.isEmpty())
                throw new JpaException("The corpus names no document for '%s'", request.type().getName());

            String path = layers.getFirst().path();
            ConcurrentMap<String, T> merged = JpaModel.keyed(request.type(), this.reads.read(request.type()));
            ConcurrentMap<String, T> applied = JpaModel.keyed(request.type(), request.rows());

            if (request.operation() == WriteRequest.Operation.DELETE)
                applied.keySet().forEach(merged::remove);
            else
                merged.putAll(applied);

            Type listType = TypeToken.getParameterized(ConcurrentList.class, request.type()).getType();
            String body = GitHubCorpus.this.gson.toJson(Concurrent.newUnmodifiableList(merged.values()), listType);
            this.put(path, body, request);
        }

        private <T extends JpaModel> void put(
            @NotNull String path,
            @NotNull String body,
            @NotNull WriteRequest<T> request
        ) throws JpaException {
            String owner = GitHubCorpus.this.owner;
            String repo = GitHubCorpus.this.repo;

            try {
                // The precondition the caller named wins; without one the current blob sha is read
                // and used, which still refuses a write over a file that moved under us.
                String sha = request.getPrecondition().orElseGet(() -> {
                    GitHubContentEnvelope envelope = this.writes.getFileMetadata(owner, repo, path);
                    return envelope.getSha();
                });

                this.writes.putFileContent(
                    owner,
                    repo,
                    path,
                    PutContentRequest.builder()
                        .message(String.format("Update %s", path))
                        .content(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)))
                        .sha(sha)
                        .branch(BRANCH)
                        .build()
                );
            } catch (GitHubApiException exception) {
                throw failed(exception, "write '%s' to '%s/%s'", path, owner, repo);
            }
        }

    }

    /**
     * Names the repository, the catalogue path and the parser a corpus reads with.
     */
    public static final class Builder {

        private final @NotNull String owner;
        private final @NotNull String repo;
        private @NotNull String manifestPath = "index.json";
        private @NotNull GsonSettings gsonSettings = GsonSettings.defaults();

        private Builder(@NotNull String owner, @NotNull String repo) {
            this.owner = owner;
            this.repo = repo;
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
