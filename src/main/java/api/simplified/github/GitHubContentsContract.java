package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.response.GitHubCommit;
import dev.simplified.client.Client;
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
 * <p>The {@link #getFileContent(String, String, String)} method requires the
 * {@code application/vnd.github.raw+json} {@code Accept} media type to be set as a static
 * client header. That media type is the only Contents API encoding that returns the raw file
 * body directly for files larger than 1 MB. Without it the Contents endpoint returns a base64
 * envelope capped at 1 MB and rejects any larger blob.
 *
 * <p>Conditional {@code If-None-Match} requests are handled automatically by the {@link Client}
 * library: a matching cached response triggers an auto-attached header on outbound {@code GET}s
 * and a transparent cache replay on {@code 304}.
 *
 * @see <a href="https://docs.github.com/en/rest?apiVersion=2022-11-28">GitHub REST API v3</a>
 */
@Route("api.github.com")
public interface GitHubContentsContract extends Contract {

    /**
     * Fetches the current tip commit on the {@code master} branch of the given repository.
     *
     * <p>Uses the single-commit-by-ref endpoint ({@code /commits/master}) rather than the
     * listing endpoint ({@code /commits?sha=master&per_page=1}). The listing endpoint serves
     * responses through GitHub's 60-second edge cache and can return stale commit SHAs; the
     * single-commit-by-ref endpoint resolves {@code master} via the git protocol ref lookup
     * and is always fresh.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @return the current tip commit on master
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("GET /repos/{owner}/{repo}/commits/master")
    @NotNull GitHubCommit getLatestMasterCommit(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo
    ) throws GitHubApiException;

    /**
     * Fetches the raw file body at the given path on the {@code master} branch.
     *
     * <p>Returns the literal file bytes when the client is configured with the
     * {@code application/vnd.github.raw+json} media type. The return type is {@code byte[]}
     * rather than {@code String} because the framework's response decoder attempts to parse
     * raw JSON bodies when the target type is {@code String}, which fails on JSON-object
     * bodies. Routing through the binary-body decoder avoids that path entirely.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param path the repo-root-relative file path
     * @return the raw file body bytes
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("GET /repos/{owner}/{repo}/contents/{path}?ref=master")
    byte @NotNull [] getFileContent(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("path") @NotNull String path
    ) throws GitHubApiException;

}
