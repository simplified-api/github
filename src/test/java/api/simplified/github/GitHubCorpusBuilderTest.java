package api.simplified.github;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers what naming a corpus settles before any request is made.
 *
 * <p>Building one issues nothing, so these run with no network. What they pin is that the branch is
 * a value the caller chooses: every request line takes it as a parameter, and a corpus that answered
 * a constant would read identically here while reaching the wrong repository state.
 */
class GitHubCorpusBuilderTest {

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
    @DisplayName("building issues no request, so an unreachable GitHub does not stop configuration")
    void buildingReachesNothing() {
        assertThat(
            GitHubCorpus.of("no-such-owner", "no-such-repo")
                .branch("no-such-branch")
                .token(GitHubToken.value("not-a-real-token"))
                .build(),
            notNullValue()
        );
    }

}
