package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.response.GitHubCommit;
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
 * Feign contract for the read surface of the GitHub Contents and Commits REST APIs.
 *
 * <p>Owner and repository are supplied as method parameters so a single proxy instance can be
 * reused across any number of repositories. Authentication, the {@code X-GitHub-Api-Version}
 * header, and the {@code Accept} media type are wired by the caller's {@link Client}
 * configuration.
 *
 * <p>The {@link #getFileContent(String, String, String, String)} method requires the
 * {@code application/vnd.github.raw+json} {@code Accept} media type to be set as a static
 * client header. That media type is the only Contents API encoding that returns the file's body
 * for a file between 1 and 100 MB; GitHub supports no media type above 100 MB. The default media
 * type is documented only for files up to 1 MB, and GitHub does not say what it answers for a
 * larger one.
 *
 * <p>Conditional {@code If-None-Match} requests are handled automatically by the {@link Client}
 * library: a matching cached response triggers an auto-attached header on outbound {@code GET}s
 * and a transparent cache replay on {@code 304}. A {@code 304} the client holds no cached body for
 * is raised as {@link NotModifiedException}.
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
 * @see <a href="https://docs.github.com/en/rest?apiVersion=2022-11-28">GitHub REST API v3</a>
 */
@Route("api.github.com")
public interface GitHubContentsContract extends Contract {

    /**
     * Fetches the current tip commit on the given branch of the given repository.
     *
     * <p>Uses the single-commit-by-ref endpoint ({@code /commits/{branch}}) rather than the
     * listing endpoint ({@code /commits?sha={branch}&per_page=1}). The listing endpoint serves
     * responses through GitHub's 60-second edge cache and can return stale commit SHAs; the
     * single-commit-by-ref endpoint resolves the ref via the git protocol ref lookup and is
     * always fresh.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param branch the branch name
     * @return the current tip commit on that branch
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    @RequestLine("GET /repos/{owner}/{repo}/commits/{branch}")
    @NotNull GitHubCommit getLatestCommit(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("branch") @NotNull String branch
    ) throws GitHubApiException;

    /**
     * Fetches the raw file body at the given path as the given ref holds it.
     *
     * <p>Returns the literal file bytes when the client is configured with the
     * {@code application/vnd.github.raw+json} media type. The return type is {@code byte[]}
     * rather than {@code String} because the framework's response decoder attempts to parse
     * raw JSON bodies when the target type is {@code String}, which fails on JSON-object
     * bodies. Routing through the binary-body decoder avoids that path entirely.
     *
     * <p>The ref is a branch, a tag or a commit sha. A commit sha names content that never changes,
     * so a cached answer for one is never out of date.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param path the repo-root-relative file path
     * @param ref the branch, tag or commit sha to read at
     * @return the raw file body bytes
     * @throws GitHubApiException on a non-2xx status other than a 3xx, a 412 or a 429
     * @throws NotModifiedException on a 3xx status
     * @throws PreconditionFailedException on a 412 status
     * @throws RateLimitException on a 429 status, or before the request is sent when the client's
     *         rate-limit gate refuses it
     */
    @RequestLine("GET /repos/{owner}/{repo}/contents/{path}?ref={ref}")
    byte @NotNull [] getFileContent(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("path") @NotNull String path,
        @Param("ref") @NotNull String ref
    ) throws GitHubApiException;

}
