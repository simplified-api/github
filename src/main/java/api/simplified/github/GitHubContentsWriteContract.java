package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.request.PutContentRequest;
import api.simplified.github.response.GitHubContentEnvelope;
import api.simplified.github.response.GitHubPutResponse;
import dev.simplified.client.Client;
import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.exception.PreconditionFailedException;
import dev.simplified.client.exception.RateLimitException;
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
 *       attached to the request body. GitHub refuses the write with a {@code 409 Conflict} or a
 *       {@code 422 Validation failed} when the file no longer carries that {@code sha}.</li>
 * </ol>
 *
 * <p>The failures each method documents are those of a {@link Client} configured with
 * {@code withErrorDecoder(GitHubApiException::new)}, as {@link GitHubCorpus} configures its own.
 * The client raises {@link NotModifiedException} for a 3xx status,
 * {@link PreconditionFailedException} for a 412 and {@link RateLimitException} for a 429 before its
 * error decoder runs, and hands every other non-2xx status to {@link GitHubApiException}.
 *
 * <p>The client also raises {@link RateLimitException} before a request is sent, when its rate-limit
 * gate refuses it. The gate keeps one bucket for {@code api.github.com}, set from the
 * {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining} and {@code X-RateLimit-Reset} headers of
 * GitHub's live responses, and refuses every request once the bucket has none left, until the reset
 * GitHub named.
 *
 * @see GitHubContentsContract
 * @see <a href="https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28">GitHub
 *      repository contents</a>
 */
@Route("api.github.com")
public interface GitHubContentsWriteContract extends Contract {

    /**
     * Fetches the Contents API JSON envelope for the given path on the given branch.
     *
     * <p>The envelope's {@link GitHubContentEnvelope#sha sha} field carries the git
     * <b>blob</b> SHA at the branch tip - the optimistic-concurrency token consumed by the
     * follow-up {@link #putFileContent} call.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param path the repo-root-relative file path
     * @param branch the branch name
     * @return the Contents API envelope with {@code sha}, {@code size}, and base64 {@code content}
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    @RequestLine("GET /repos/{owner}/{repo}/contents/{path}?ref={branch}")
    @NotNull GitHubContentEnvelope getFileMetadata(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("path") @NotNull String path,
        @Param("branch") @NotNull String branch
    ) throws GitHubApiException;

    /**
     * Writes a new version of the file at the given path via the Contents API {@code PUT}
     * endpoint, using the supplied blob SHA as the optimistic-concurrency token.
     *
     * <p>To update a file, the request body carries {@link PutContentRequest#sha sha} set to the
     * blob SHA previously observed via {@link #getFileMetadata}; a body that omits it creates a file
     * that does not exist yet. GitHub refuses a stale SHA with a
     * {@code 409 Conflict} or a {@code 422 Validation failed}, either of which reaches the caller
     * as a {@link GitHubApiException} carrying that status; the framework raises
     * {@link PreconditionFailedException} only for a {@code 412}.
     *
     * <p>GitHub produces a fresh commit on the target branch for every successful PUT, so a
     * batch that touches N distinct files produces N commits.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param path the repo-root-relative file path
     * @param body the PUT body carrying message, base64 content, and blob SHA
     * @return the GitHub PUT response envelope with the new blob SHA and commit SHA
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429, including the
     *         409 or 422 a stale sha raises
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    @RequestLine("PUT /repos/{owner}/{repo}/contents/{path}")
    @NotNull GitHubPutResponse putFileContent(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("path") @NotNull String path,
        @NotNull PutContentRequest body
    ) throws GitHubApiException;

}
