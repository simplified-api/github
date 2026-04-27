package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.request.PutContentRequest;
import api.simplified.github.response.GitHubContentEnvelope;
import api.simplified.github.response.GitHubPutResponse;
import dev.simplified.client.exception.PreconditionFailedException;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import feign.Param;
import feign.RequestLine;
import org.jetbrains.annotations.NotNull;

/**
 * Feign contract for the write surface of the GitHub Contents API.
 *
 * <p>Sibling of {@link GitHubContentsContract} rather than an extension because the two
 * contracts require different static {@code Accept} headers. The read-path contract pins
 * {@code application/vnd.github.raw+json} so the Contents endpoint returns raw file bodies;
 * this write contract requires {@code application/vnd.github+json} so the Contents endpoint
 * returns the JSON envelope (whose {@code sha} field is the optimistic-concurrency token) and
 * so the {@code PUT} endpoint accepts a standard JSON body. Two contracts means two clients,
 * which means two distinct header sets.
 *
 * <p>The optimistic-concurrency flow is:
 * <ol>
 *   <li>{@link #getFileMetadata} - read the current blob {@code sha} from the envelope.</li>
 *   <li>{@link #putFileContent} - write a new version with the previously observed {@code sha}
 *       attached to the request body. GitHub rejects with {@code 409} or {@code 422} when the
 *       branch tip has moved since the metadata fetch.</li>
 * </ol>
 *
 * @see GitHubContentsContract
 */
@Route("api.github.com")
public interface GitHubContentsWriteContract extends Contract {

    /**
     * Fetches the Contents API JSON envelope for the given path on the {@code master} branch.
     *
     * <p>The envelope's {@link GitHubContentEnvelope#getSha()} field carries the git
     * <b>blob</b> SHA at the branch tip - the optimistic-concurrency token consumed by the
     * follow-up {@link #putFileContent} call.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param path the repo-root-relative file path
     * @return the Contents API envelope with {@code sha}, {@code size}, and base64 {@code content}
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("GET /repos/{owner}/{repo}/contents/{path}?ref=master")
    @NotNull GitHubContentEnvelope getFileMetadata(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("path") @NotNull String path
    ) throws GitHubApiException;

    /**
     * Writes a new version of the file at the given path via the Contents API {@code PUT}
     * endpoint, using the supplied blob SHA as the optimistic-concurrency token.
     *
     * <p>The request body must carry {@link PutContentRequest#getSha()} set to the blob SHA
     * previously observed via {@link #getFileMetadata}. A stale SHA produces a {@code 409
     * Conflict} that the framework maps to {@link PreconditionFailedException}.
     *
     * <p>GitHub produces a fresh commit on the target branch for every successful PUT, so a
     * batch that touches N distinct files produces N commits.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param path the repo-root-relative file path
     * @param body the PUT body carrying message, base64 content, and blob SHA
     * @return the GitHub PUT response envelope with the new blob SHA and commit SHA
     * @throws GitHubApiException on any non-2xx status (including 409 Conflict for SHA mismatch)
     */
    @RequestLine("PUT /repos/{owner}/{repo}/contents/{path}")
    @NotNull GitHubPutResponse putFileContent(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("path") @NotNull String path,
        @NotNull PutContentRequest body
    ) throws GitHubApiException;

}
