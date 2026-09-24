package api.simplified.github;

import api.simplified.github.response.GitHubCommit;
import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Gson round-trip tests for the Commits API DTO, {@link GitHubCommit}.
 *
 * <p>The fixture is a narrowed copy of the "Get a commit" example response in GitHub's
 * <a href="https://docs.github.com/en/rest/commits/commits?apiVersion=2022-11-28#get-a-commit">Commits
 * API documentation</a>. No network I/O; no Feign proxy construction; no Spring context.
 */
class CommitsApiDtoRoundTripTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("GitHubCommit deserializes the top-level sha and the nested commit message and committer")
    void commitFromJson() {
        String json = """
            {
              "url": "https://api.github.com/repos/octocat/Hello-World/commits/6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "commit": {
                "url": "https://api.github.com/repos/octocat/Hello-World/git/commits/6dcb09b5b57875f334f61aebed695e2e4193db5e",
                "author": {
                  "name": "Monalisa Octocat",
                  "email": "mona@github.com",
                  "date": "2011-04-14T16:00:49Z"
                },
                "committer": {
                  "name": "Monalisa Octocat",
                  "email": "mona@github.com",
                  "date": "2011-04-14T16:00:49Z"
                },
                "message": "Fix all the bugs",
                "tree": {
                  "url": "https://api.github.com/repos/octocat/Hello-World/tree/6dcb09b5b57875f334f61aebed695e2e4193db5e",
                  "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e"
                },
                "comment_count": 0
              }
            }
            """;

        GitHubCommit commit = GSON.fromJson(json, GitHubCommit.class);

        assertThat(commit, notNullValue());
        assertThat(commit.getSha(), equalTo("6dcb09b5b57875f334f61aebed695e2e4193db5e"));
        assertThat(commit.getCommit().getMessage(), equalTo("Fix all the bugs"));
        assertThat(commit.getCommit().getCommitter().getName(), equalTo("Monalisa Octocat"));
        assertThat(commit.getCommit().getCommitter().getDate(), equalTo("2011-04-14T16:00:49Z"));
    }

}
