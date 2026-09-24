package api.simplified.github.request;

import api.simplified.github.GitHubContentsWriteContract;
import api.simplified.github.response.GitHubContentEnvelope;
import com.google.gson.annotations.SerializedName;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Request body for the {@code PUT /repos/{owner}/{repo}/contents/{path}} call on
 * {@link GitHubContentsWriteContract}.
 *
 * <p>Serialized to JSON by Feign's {@code GsonEncoder} on the outbound request. Every field
 * listed here is a literal GitHub Contents API field - no framework-specific metadata is added.
 * {@link #sha} is the optimistic-concurrency token: the blob SHA a prior
 * {@link GitHubContentEnvelope#sha content envelope} fetch read, which GitHub requires to update a
 * file that exists. It is omitted only to create a file that does not exist yet.
 *
 * <p>The {@link #branch} and {@link #committer} fields are optional per GitHub's API and
 * default to {@code null} here - omitted from the serialized payload by Gson's default
 * behavior.
 *
 * @see <a href="https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#create-or-update-file-contents">
 *      GitHub create or update file contents</a>
 */
@Getter
@ClassBuilder
public final class PutContentRequest {

    /**
     * The commit message body written to the git log.
     */
    @SerializedName("message")
    private final @NotNull String message;

    /**
     * The new file content, base64-encoded per GitHub's Contents API convention.
     */
    @SerializedName("content")
    private final @NotNull String content;

    /**
     * The blob SHA the file being replaced is expected to carry at the branch tip, absent when
     * creating a file that does not exist yet. GitHub refuses the write with a {@code 409} or a
     * {@code 422} when the file no longer carries it, so a write cannot land over a change to the
     * file the caller never read; each accepted write is its own commit on the branch.
     */
    @SerializedName("sha")
    private final @NotNull String sha;

    /**
     * Optional target branch name; defaults to the repo default branch when omitted.
     */
    @SerializedName("branch")
    private final @Nullable String branch;

    /**
     * Optional committer metadata; defaults to the authenticated user when omitted.
     */
    @SerializedName("committer")
    private final @Nullable Committer committer;

    /**
     * Nested committer identity block on a {@link PutContentRequest}. Carries the name and
     * email displayed in the resulting git commit. Defaults to the authenticated PAT user
     * when omitted.
     */
    @Getter
    @ClassBuilder
    public static final class Committer {

        /**
         * The display name attached to the commit author field.
         */
        @SerializedName("name")
        private final @NotNull String name;

        /**
         * The email address attached to the commit author field.
         */
        @SerializedName("email")
        private final @NotNull String email;

    }

}
