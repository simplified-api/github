package api.simplified.github;

import api.simplified.github.request.CreateBlobRequest;
import api.simplified.github.request.CreateCommitRequest;
import api.simplified.github.request.CreateTreeRequest;
import api.simplified.github.request.UpdateRefRequest;
import api.simplified.github.response.GitBlob;
import api.simplified.github.response.GitCommit;
import api.simplified.github.response.GitRef;
import api.simplified.github.response.GitTree;
import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Gson round-trip tests for the {@link GitHubGitDataContract} DTO surface.
 *
 * <p>Fixtures are narrowed versions of the
 * <a href="https://docs.github.com/en/rest/git?apiVersion=2022-11-28">official GitHub Git
 * Database API documentation</a> responses. The round-trip suite protects against silent
 * schema drift in DTOs that are not yet exercised by a production path.
 */
class GitDataDtoRoundTripTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("GitRef deserializes a branch ref with embedded object details")
    void gitRefFromJson() {
        String json = """
            {
              "ref": "refs/heads/master",
              "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/refs/heads/master",
              "object": {
                "sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "type": "commit",
                "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/commits/aaaa"
              }
            }
            """;

        GitRef ref = GSON.fromJson(json, GitRef.class);

        assertThat(ref, notNullValue());
        assertThat(ref.getRef(), equalTo("refs/heads/master"));
        assertThat(ref.getObject().getSha(), equalTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertThat(ref.getObject().getType(), equalTo("commit"));
    }

    @Test
    @DisplayName("GitCommit deserializes tree ref + parents + optional author/committer")
    void gitCommitFromJson() {
        String json = """
            {
              "sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "message": "Batch update: 3 entities across 2 files",
              "tree": {
                "sha": "cccccccccccccccccccccccccccccccccccccccc",
                "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/trees/cccc"
              },
              "parents": [
                {
                  "sha": "dddddddddddddddddddddddddddddddddddddddd",
                  "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/commits/dddd"
                }
              ],
              "author": {
                "name": "simplified-data-bot",
                "email": "bot@skyblock-simplified.dev",
                "date": "2026-04-09T12:00:00Z"
              }
            }
            """;

        GitCommit commit = GSON.fromJson(json, GitCommit.class);

        assertThat(commit, notNullValue());
        assertThat(commit.getSha(), equalTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertThat(commit.getMessage(), containsString("Batch update"));
        assertThat(commit.getTree().getSha(), equalTo("cccccccccccccccccccccccccccccccccccccccc"));
        assertThat(commit.getParents(), hasSize(1));
        assertThat(commit.getParents().getFirst().getSha(), equalTo("dddddddddddddddddddddddddddddddddddddddd"));
        assertThat(commit.getAuthor(), notNullValue());
        assertThat(commit.getAuthor().getName(), equalTo("simplified-data-bot"));
        assertThat(commit.getCommitter(), nullValue());
    }

    @Test
    @DisplayName("GitTree deserializes recursive tree entries with optional size")
    void gitTreeFromJson() {
        String json = """
            {
              "sha": "cccccccccccccccccccccccccccccccccccccccc",
              "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/trees/cccc",
              "tree": [
                {
                  "path": "data/v1/world/zodiac_events.json",
                  "mode": "100644",
                  "type": "blob",
                  "sha": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "size": 1247,
                  "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/blobs/eeee"
                }
              ],
              "truncated": false
            }
            """;

        GitTree tree = GSON.fromJson(json, GitTree.class);

        assertThat(tree, notNullValue());
        assertThat(tree.getTree(), hasSize(1));
        GitTree.Entry entry = tree.getTree().getFirst();
        assertThat(entry.getPath(), equalTo("data/v1/world/zodiac_events.json"));
        assertThat(entry.getMode(), equalTo("100644"));
        assertThat(entry.getType(), equalTo("blob"));
        assertThat(entry.getSha(), equalTo("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"));
        assertThat(entry.getSize(), equalTo(1247L));
        assertThat(tree.getTruncated(), is(false));
    }

    @Test
    @DisplayName("GitBlob deserializes a create-blob response with null content fields")
    void gitBlobCreateFromJson() {
        String json = """
            {
              "sha": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
              "url": "https://api.github.com/repos/skyblock-simplified/skyblock-data/git/blobs/eeee"
            }
            """;

        GitBlob blob = GSON.fromJson(json, GitBlob.class);

        assertThat(blob, notNullValue());
        assertThat(blob.getSha(), equalTo("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"));
        assertThat(blob.getContent(), nullValue());
        assertThat(blob.getSize(), nullValue());
    }

    @Test
    @DisplayName("CreateBlobRequest serializes content + encoding")
    void createBlobRequestToJson() {
        CreateBlobRequest body = CreateBlobRequest.builder()
            .content("W3siaWQiOiJZRUFSIn1d")
            .encoding("base64")
            .build();

        String json = GSON.toJson(body);

        assertThat(json, containsString("\"content\":\"W3siaWQiOiJZRUFSIn1d\""));
        assertThat(json, containsString("\"encoding\":\"base64\""));
    }

    @Test
    @DisplayName("CreateTreeRequest serializes base_tree + entries with SHA references")
    void createTreeRequestToJson() {
        CreateTreeRequest.TreeEntry entry = CreateTreeRequest.TreeEntry.builder()
            .path("data/v1/world/zodiac_events.json")
            .mode("100644")
            .type("blob")
            .sha("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
            .build();
        CreateTreeRequest body = CreateTreeRequest.builder()
            .baseTree("cccccccccccccccccccccccccccccccccccccccc")
            .tree(Concurrent.newList(entry))
            .build();

        String json = GSON.toJson(body);

        assertThat(json, containsString("\"base_tree\":\"cccccccccccccccccccccccccccccccccccccccc\""));
        assertThat(json, containsString("\"path\":\"data/v1/world/zodiac_events.json\""));
        assertThat(json, containsString("\"mode\":\"100644\""));
        assertThat(json, containsString("\"type\":\"blob\""));
    }

    @Test
    @DisplayName("CreateCommitRequest serializes message + tree + parents + optional author")
    void createCommitRequestToJson() {
        CreateCommitRequest.Actor author = CreateCommitRequest.Actor.builder()
            .name("simplified-data-bot")
            .email("bot@skyblock-simplified.dev")
            .build();
        CreateCommitRequest body = CreateCommitRequest.builder()
            .message("Batch update: 3 entities across 2 files")
            .tree("cccccccccccccccccccccccccccccccccccccccc")
            .parents(Concurrent.newList("dddddddddddddddddddddddddddddddddddddddd"))
            .author(author)
            .build();

        String json = GSON.toJson(body);

        assertThat(json, containsString("\"message\":\"Batch update: 3 entities across 2 files\""));
        assertThat(json, containsString("\"tree\":\"cccccccccccccccccccccccccccccccccccccccc\""));
        assertThat(json, containsString("\"parents\":[\"dddddddddddddddddddddddddddddddddddddddd\"]"));
        assertThat(json, containsString("\"name\":\"simplified-data-bot\""));
    }

    @Test
    @DisplayName("UpdateRefRequest serializes sha and optional force flag")
    void updateRefRequestToJson() {
        UpdateRefRequest body = UpdateRefRequest.builder()
            .sha("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            .force(false)
            .build();

        String json = GSON.toJson(body);

        assertThat(json, containsString("\"sha\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\""));
        assertThat(json, containsString("\"force\":false"));
    }

}
