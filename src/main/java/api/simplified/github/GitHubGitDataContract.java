package api.simplified.github;

import api.simplified.github.exception.GitHubApiException;
import api.simplified.github.request.CreateBlobRequest;
import api.simplified.github.request.CreateCommitRequest;
import api.simplified.github.request.CreateTreeRequest;
import api.simplified.github.request.UpdateRefRequest;
import api.simplified.github.response.GitBlob;
import api.simplified.github.response.GitCommit;
import api.simplified.github.response.GitRef;
import api.simplified.github.response.GitTree;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import feign.Param;
import feign.RequestLine;
import org.jetbrains.annotations.NotNull;

/**
 * Feign contract for the GitHub Git Database (Git Data) REST API.
 *
 * <p>Exposes the seven endpoints required for a multi-file batched commit: read the current
 * branch tip and its tree, stage new blobs, build a tree overlay, create a parented commit,
 * and finally fast-forward the branch ref to that commit.
 *
 * <p>{@link #createBlob}, {@link #createTree}, and {@link #createCommit} all return detached
 * git objects that are not reachable from any branch until {@link #updateRef} moves a ref onto
 * the new commit SHA.
 *
 * @see <a href="https://docs.github.com/en/rest/git?apiVersion=2022-11-28">GitHub Git Database API</a>
 */
@Route("api.github.com")
public interface GitHubGitDataContract extends Contract {

    /**
     * Fetches the git reference for a branch by name.
     *
     * <p>Accepts the short branch name (e.g. {@code "master"}), not the fully-qualified ref
     * path (e.g. {@code "refs/heads/master"}) - the path prefix is hardcoded in the request line.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param branch the branch name
     * @return the current ref including the target commit SHA
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("GET /repos/{owner}/{repo}/git/refs/heads/{branch}")
    @NotNull GitRef getRef(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("branch") @NotNull String branch
    ) throws GitHubApiException;

    /**
     * Fetches a git commit object by SHA.
     *
     * <p>Returns the Git Data API commit envelope, which is narrower than the Commits REST
     * envelope carried by {@link api.simplified.github.response.GitHubCommit}. The commit
     * carries a reference to its tree via {@link GitCommit.TreeRef#getSha()}.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param sha the commit SHA
     * @return the commit object
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("GET /repos/{owner}/{repo}/git/commits/{sha}")
    @NotNull GitCommit getCommit(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("sha") @NotNull String sha
    ) throws GitHubApiException;

    /**
     * Fetches a git tree object by SHA.
     *
     * <p>Setting {@code recursive=1} expands every nested subtree inline up to GitHub's
     * truncation cap; passing {@code "0"} or empty returns only the top-level entries with
     * subtrees represented as SHA references.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param sha the tree SHA
     * @param recursive {@code "1"} to expand subtrees inline, {@code "0"} or empty otherwise
     * @return the tree object
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("GET /repos/{owner}/{repo}/git/trees/{sha}?recursive={recursive}")
    @NotNull GitTree getTree(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("sha") @NotNull String sha,
        @Param("recursive") @NotNull String recursive
    ) throws GitHubApiException;

    /**
     * Creates a new git blob from the supplied content.
     *
     * <p>The returned {@link GitBlob} carries only the SHA and URL on create responses; the
     * content, size, and encoding fields are null. The SHA is the input to a follow-up
     * {@link #createTree} call's entries.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param body the blob content and encoding
     * @return the created blob reference
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("POST /repos/{owner}/{repo}/git/blobs")
    @NotNull GitBlob createBlob(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @NotNull CreateBlobRequest body
    ) throws GitHubApiException;

    /**
     * Creates a new git tree from the supplied entries, optionally layered on top of an existing
     * base tree.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param body the tree entries plus optional {@code base_tree} SHA
     * @return the created tree object
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("POST /repos/{owner}/{repo}/git/trees")
    @NotNull GitTree createTree(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @NotNull CreateTreeRequest body
    ) throws GitHubApiException;

    /**
     * Creates a new git commit from the supplied tree and parent SHAs.
     *
     * <p>The commit is detached - no branch points at it until a follow-up {@link #updateRef}
     * call moves a ref to this commit's SHA.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param body the commit metadata plus tree and parent SHAs
     * @return the created commit object
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("POST /repos/{owner}/{repo}/git/commits")
    @NotNull GitCommit createCommit(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @NotNull CreateCommitRequest body
    ) throws GitHubApiException;

    /**
     * Moves a branch ref to a new commit SHA via {@code PATCH git/refs/heads/{branch}}.
     *
     * <p>When {@link UpdateRefRequest#getForce()} is {@code null} or {@code false}, GitHub
     * enforces a fast-forward check and rejects the update with {@code 422} if the new commit
     * is not a descendant of the current tip - the optimistic-concurrency hook for batched
     * write paths.
     *
     * @param owner the repository owner login
     * @param repo the repository name
     * @param branch the branch name
     * @param body the new SHA and optional force flag
     * @return the updated ref reflecting the new target
     * @throws GitHubApiException on any non-2xx status
     */
    @RequestLine("PATCH /repos/{owner}/{repo}/git/refs/heads/{branch}")
    @NotNull GitRef updateRef(
        @Param("owner") @NotNull String owner,
        @Param("repo") @NotNull String repo,
        @Param("branch") @NotNull String branch,
        @NotNull UpdateRefRequest body
    ) throws GitHubApiException;

}
