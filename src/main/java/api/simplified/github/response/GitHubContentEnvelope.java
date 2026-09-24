package api.simplified.github.response;

import api.simplified.github.GitHubContentsContract;
import api.simplified.github.GitHubCorpus;
import api.simplified.github.exception.GitHubApiException;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import org.jetbrains.annotations.NotNull;

/**
 * Gson-bindable mirror of the GitHub Contents API JSON envelope returned by
 * {@code GET /repos/{owner}/{repo}/contents/{path}} when the caller requests the
 * default {@code application/vnd.github+json} media type.
 *
 * <p>The critical field for the write path is {@link #sha}, which is the
 * git <b>blob</b> SHA of the file at the branch tip. The write path uses this value
 * as the optimistic-concurrency token on the follow-up
 * {@code PUT /repos/{owner}/{repo}/contents/{path}} call: if another writer committed
 * to the same path between the GET and the PUT, GitHub refuses the write with a
 * {@code 409 Conflict} or a {@code 422 Validation failed}, which reaches the caller as a
 * {@link GitHubApiException} carrying that status.
 *
 * <p>Instances are produced by {@link Gson#fromJson} inside the
 * {@link Client} response decoder pipeline - never constructed
 * directly by application code, which is why the constructor is private under
 * {@link RequiredArgsConstructor}.
 *
 * <p>Six of the envelope's fields are declared; Gson's reflective binder ignores every other
 * field of the upstream JSON. {@link GitHubCorpus} reads only {@link #sha} from it, and reads
 * file bodies through {@link GitHubContentsContract#getFileContent} under
 * {@code Accept: application/vnd.github.raw+json}, which answers the raw bytes.
 *
 * <p>GitHub documents the endpoint's answer by file size:
 * <ul>
 *   <li><b>1 MB or smaller</b> - all of the endpoint's features are supported, the default
 *       media type included.</li>
 *   <li><b>Between 1 and 100 MB</b> - only the raw and object custom media types are
 *       supported. Under {@code application/vnd.github.object+json} the envelope carries an
 *       empty {@link #content} and an {@link #encoding} of {@code "none"}. The default media
 *       type is not among the supported ones, and GitHub does not document what it answers for
 *       such a file.</li>
 *   <li><b>Greater than 100 MB</b> - the endpoint is not supported.</li>
 * </ul>
 *
 * @see <a href="https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#get-repository-content">
 *      GitHub get repository content</a>
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class GitHubContentEnvelope {

    /**
     * The file name (without directories).
     */
    @SerializedName("name")
    private final @NotNull String name;

    /**
     * The full repo-root-relative path of the file.
     */
    @SerializedName("path")
    private final @NotNull String path;

    /**
     * The blob SHA of the file at the branch tip. Load-bearing: this is the
     * optimistic-concurrency token passed as the {@code sha} field of the follow-up
     * {@code PUT contents} body.
     */
    @SerializedName("sha")
    private final @NotNull String sha;

    /**
     * The file size in bytes, as reported by the envelope.
     */
    @SerializedName("size")
    private final long size;

    /**
     * The file content in the form {@link #encoding} names. GitHub's schema marks it a required
     * string for a file under the default media type, and its example carries base64 text. It is
     * the empty string for a file between 1 and 100 MB read under the object media type; GitHub
     * does not document it for such a file under the default media type.
     */
    @SerializedName("content")
    private final @NotNull String content;

    /**
     * The encoding of {@link #content}. GitHub's schema types it as a string without listing its
     * values: its examples show {@code "base64"}, and it is {@code "none"} for a file between 1 and
     * 100 MB read under the object media type. GitHub does not document that the default media
     * type always answers {@code "base64"}.
     */
    @SerializedName("encoding")
    private final @NotNull String encoding;

}
