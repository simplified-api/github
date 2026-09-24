package api.simplified.github;

import api.simplified.github.request.PutContentRequest;
import api.simplified.github.response.GitHubCommit;
import api.simplified.github.response.GitHubContentEnvelope;
import api.simplified.github.response.GitHubPutResponse;
import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Covers what a corpus asks the repository when it holds a catalogue and is asked whether the branch
 * moved, and which ref a file is read at.
 *
 * <p>The repository answers from memory through the same contract the client proxies, so no request
 * leaves the machine. Its catalogues carry a revision one commit behind the tip they sit at, which is
 * what the generator writes: it records the commit it walked, and the catalogue is committed after.
 */
class GitHubCorpusPollTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull String MANIFEST = "data/v1/index.json";

    private Repository repository;
    private GitHubCorpus corpus;

    /**
     * A repository answering from memory: a branch tip a case moves, and the text of each file at
     * each commit. It records every file read as {@code path@ref} and counts the tip reads.
     */
    private static final class Repository implements GitHubContentsContract {

        private volatile @NotNull String tip = "";
        private final @NotNull ConcurrentMap<String, String> files = Concurrent.newMap();
        private final @NotNull ConcurrentList<String> fileReads = Concurrent.newList();
        private final @NotNull AtomicInteger tipReads = new AtomicInteger();

        /**
         * Commits a catalogue at a new tip, whose revision names the commit before it.
         *
         * @param commit the new tip
         * @param revision the commit the catalogue records
         * @param fingerprint the hash its one document's layer carries
         */
        void commit(@NotNull String commit, @NotNull String revision, @NotNull String fingerprint) {
            this.files.put(commit + ":" + MANIFEST, String.format(
                "{\"revision\":\"%s\",\"documents\":{\"items\":[{\"path\":\"data/v1/items/items.json\",\"sha256\":\"%s\"}]}}",
                revision,
                fingerprint
            ));
            this.tip = commit;
        }

        @Override
        public @NotNull GitHubCommit getLatestCommit(@NotNull String owner, @NotNull String repo, @NotNull String branch) {
            this.tipReads.incrementAndGet();
            return GSON.fromJson(String.format("{\"sha\":\"%s\"}", this.tip), GitHubCommit.class);
        }

        @Override
        public byte @NotNull [] getFileContent(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull String ref
        ) {
            this.fileReads.add(path + "@" + ref);
            String body = this.files.get(ref + ":" + path);
            return (body == null ? "[]" : body).getBytes(StandardCharsets.UTF_8);
        }

    }

    /**
     * A write surface no case reaches.
     */
    private static final class NoWrites implements GitHubContentsWriteContract {

        @Override
        public @NotNull GitHubContentEnvelope getFileMetadata(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull String branch
        ) {
            throw new UnsupportedOperationException("No case reads a blob sha");
        }

        @Override
        public @NotNull GitHubPutResponse putFileContent(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull PutContentRequest body
        ) {
            throw new UnsupportedOperationException("No case writes");
        }

    }

    @BeforeEach
    void setUp() {
        this.repository = new Repository();
        this.repository.commit("c1", "c0", "aaa");
        this.corpus = GitHubCorpus.of("owner", "repo").manifest(MANIFEST).build(this.repository, new NoWrites());
    }

    @Test
    @DisplayName("a poll answers empty while the branch stays at the tip the held catalogue was read at, though the catalogue's revision is older")
    void aPollAtTheHeldTipFetchesNothing() {
        Optional<ManifestIndex> first = this.corpus.poll();

        assertThat(first.isPresent(), is(true));
        assertThat(first.orElseThrow().getRevision(), equalTo("c0"));
        assertThat(this.corpus.poll().isPresent(), is(false));
        assertThat(this.corpus.poll().isPresent(), is(false));
        assertThat(this.repository.fileReads, contains(MANIFEST + "@c1"));
        assertThat(this.repository.tipReads.get(), equalTo(3));
    }

    @Test
    @DisplayName("a poll after the branch moved reads the catalogue at the new tip rather than at the branch, and holds both")
    void aMovedTipIsReadAtThatCommit() {
        this.corpus.poll();
        this.repository.commit("c3", "c2", "bbb");

        ManifestIndex moved = this.corpus.poll().orElseThrow();

        assertThat(moved.fingerprintOf("items").orElseThrow(), equalTo("bbb"));
        assertThat(this.corpus.manifest(), sameInstance(moved));
        assertThat(this.corpus.manifestCommit(), equalTo("c3"));
        assertThat(this.repository.fileReads, contains(MANIFEST + "@c1", MANIFEST + "@c3"));
    }

    @Test
    @DisplayName("the first catalogue fetch records the tip it was read at, so the poll after it fetches nothing")
    void theFirstFetchRecordsItsTip() {
        ManifestIndex held = this.corpus.manifest();

        assertThat(this.corpus.manifestCommit(), equalTo("c1"));
        assertThat(this.corpus.poll().isPresent(), is(false));
        assertThat(this.corpus.manifest(), sameInstance(held));
        assertThat(this.repository.fileReads, contains(MANIFEST + "@c1"));
        assertThat(this.repository.tipReads.get(), equalTo(2));
    }

    @Test
    @DisplayName("a read names the branch unless it is handed a commit or ref to read at")
    void aReadNamesTheRefItReadsAt() {
        GitHubCorpus branched = GitHubCorpus.of("owner", "repo")
            .branch("feat/indexing")
            .manifest(MANIFEST)
            .build(this.repository, new NoWrites());

        branched.read("data/v1/items/items.json");
        branched.read("data/v1/items/items.json", "c1");

        assertThat(this.repository.fileReads, contains("data/v1/items/items.json@feat/indexing", "data/v1/items/items.json@c1"));
    }

}
