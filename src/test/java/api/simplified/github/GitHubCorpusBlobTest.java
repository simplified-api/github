package api.simplified.github;

import api.simplified.github.request.PutContentRequest;
import api.simplified.github.response.GitHubCommit;
import api.simplified.github.response.GitHubContentEnvelope;
import api.simplified.github.response.GitHubPutResponse;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Covers the blob sha a corpus computes for a file it reads, which is the token a write replacing
 * exactly that text carries.
 *
 * <p>The expected shas are what {@code git hash-object} answers for the same bytes, so a sha computed
 * over anything other than git's own framing of the bytes read fails here.
 */
class GitHubCorpusBlobTest {

    /**
     * A repository answering every file with one body, recording each read as {@code path@ref}.
     */
    private static final class OneBody implements GitHubContentsContract {

        private final byte @NotNull [] body;
        private final @NotNull ConcurrentList<String> fileReads = Concurrent.newList();

        private OneBody(@NotNull String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public @NotNull GitHubCommit getLatestCommit(@NotNull String owner, @NotNull String repo, @NotNull String branch) {
            throw new UnsupportedOperationException("No case reads the tip");
        }

        @Override
        public byte @NotNull [] getFileContent(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull String ref
        ) {
            this.fileReads.add(path + "@" + ref);
            return this.body.clone();
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
            throw new UnsupportedOperationException("No case reads the envelope");
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

    private static @NotNull GitHubCorpus over(@NotNull OneBody repository) {
        return new GitHubCorpus(GitHubCorpus.of("owner", "repo").branch("feat/indexing"), repository, new NoWrites());
    }

    @Test
    @DisplayName("a blob answers the text it read and the sha git names those bytes by")
    void blobShaIsGitsName() {
        GitHubCorpus.Blob blob = over(new OneBody("hello\n")).blob("hello.txt");

        assertThat(blob.text(), equalTo("hello\n"));
        assertThat(blob.sha(), equalTo("ce013625030ba8dba906f756967f9e9ca394464a"));
    }

    @Test
    @DisplayName("a blob sha counts the bytes read rather than the characters they decode to")
    void blobShaCountsBytes() {
        GitHubCorpus.Blob blob = over(new OneBody("caf\u00e9\n")).blob("cafe.txt");

        assertThat(blob.text(), equalTo("caf\u00e9\n"));
        assertThat(blob.sha(), equalTo("572eb43fe8e34fb87d01c69e01151ff696022924"));
    }

    @Test
    @DisplayName("a blob is read at the branch the corpus was named")
    void blobIsReadAtTheBranch() {
        OneBody repository = new OneBody("[]");

        over(repository).blob("data/v1/items/items.json");

        assertThat(repository.fileReads, contains("data/v1/items/items.json@feat/indexing"));
    }

}
