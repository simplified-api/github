package api.simplified.github;

import api.simplified.github.request.PutContentRequest;
import api.simplified.github.response.GitHubCommit;
import api.simplified.github.response.GitHubContentEnvelope;
import api.simplified.github.response.GitHubPutResponse;
import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers what naming a corpus settles before a request reaches GitHub: the branch every request
 * names, and the contracts every request is sent through.
 *
 * <p>Three cases build through {@code build()}, which makes both real clients, and each client
 * starts a DNS lookup and a {@code HEAD} probe of {@code api.github.com} on a background thread
 * and drops its failure. Building waits on neither, so those cases pass with no network, but
 * where there is one they reach GitHub. What they pin is that the branch is a value the caller
 * chooses: every request line takes it as a parameter, and a corpus that answered a constant
 * would read identically here while reaching the wrong repository state. A corpus handed the
 * caller's own contracts makes no client and sends its requests through them, so its case
 * reaches nothing but those contracts.
 */
class GitHubCorpusBuilderTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * A repository answering both contracts from memory, recording each request as the method it
     * reached, the repository it named, and the file and ref it named.
     */
    private static final class Recording implements GitHubContentsContract, GitHubContentsWriteContract {

        private final @NotNull ConcurrentList<String> requests = Concurrent.newList();

        @Override
        public @NotNull GitHubCommit getLatestCommit(@NotNull String owner, @NotNull String repo, @NotNull String branch) {
            this.requests.add(String.format("tip %s/%s@%s", owner, repo, branch));
            return GSON.fromJson("{\"sha\":\"c1\"}", GitHubCommit.class);
        }

        @Override
        public byte @NotNull [] getFileContent(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull String ref
        ) {
            this.requests.add(String.format("read %s/%s/%s@%s", owner, repo, path, ref));
            return "[]".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public @NotNull GitHubContentEnvelope getFileMetadata(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull String branch
        ) {
            this.requests.add(String.format("metadata %s/%s/%s@%s", owner, repo, path, branch));
            return GSON.fromJson("{\"sha\":\"b1\"}", GitHubContentEnvelope.class);
        }

        @Override
        public @NotNull GitHubPutResponse putFileContent(
            @NotNull String owner,
            @NotNull String repo,
            @NotNull String path,
            @NotNull PutContentRequest body
        ) {
            this.requests.add(String.format("write %s/%s/%s@%s", owner, repo, path, body.getBranch()));
            return GSON.fromJson("{}", GitHubPutResponse.class);
        }

    }

    @Test
    @DisplayName("a corpus that names no branch works against master")
    void branchDefaultsToMaster() {
        assertThat(GitHubCorpus.of("simplified-api", "skyblock").build().getBranch(), equalTo("master"));
    }

    @Test
    @DisplayName("a corpus works against the branch it was named")
    void branchIsTheOneNamed() {
        GitHubCorpus corpus = GitHubCorpus.of("simplified-api", "skyblock")
            .branch("feat/indexing")
            .manifest("data/v1/index.json")
            .build();

        assertThat(corpus.getBranch(), equalTo("feat/indexing"));
    }

    @Test
    @DisplayName("a corpus builds for a repository, branch and token GitHub would refuse, without waiting on an answer from GitHub")
    void buildingWaitsOnNoAnswer() {
        assertThat(
            GitHubCorpus.of("no-such-owner", "no-such-repo")
                .branch("no-such-branch")
                .token(GitHubToken.value("not-a-real-token"))
                .build(),
            notNullValue()
        );
    }

    @Test
    @DisplayName("a corpus built over the caller's contracts sends every request through them, naming the builder's repository and branch")
    void theCallersContractsAnswerEveryRequest() {
        Recording repository = new Recording();
        GitHubCorpus corpus = GitHubCorpus.of("owner", "repo")
            .branch("feat/indexing")
            .build(repository, repository);

        assertThat(corpus.tip(), equalTo("c1"));
        assertThat(corpus.read("a.json"), equalTo("[]"));
        assertThat(corpus.metadata("a.json"), equalTo("b1"));
        corpus.write("a.json", "[]", "b1", "Update a.json");

        assertThat(repository.requests, contains(
            "tip owner/repo@feat/indexing",
            "read owner/repo/a.json@feat/indexing",
            "metadata owner/repo/a.json@feat/indexing",
            "write owner/repo/a.json@feat/indexing"
        ));
    }

}
