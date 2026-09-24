package api.simplified.github;

import dev.simplified.util.StringUtil;
import dev.simplified.util.SystemUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A personal access token, held as the instruction a client authenticates with.
 *
 * <p>Reading it out of the environment is the common case and the one that has a failure mode worth
 * a type: an unset variable is answered where the token is asked for rather than as a 401 on the
 * first write.
 *
 * <p>A token is refused only when it is null or empty. One of whitespace alone is taken and sent
 * as it is.
 *
 * @see GitHubCorpus.Builder#token(GitHubToken)
 */
public final class GitHubToken {

    private final @NotNull String token;

    private GitHubToken(@NotNull String token) {
        this.token = token;
    }

    /**
     * Reads the token out of the named variable through {@link SystemUtil#getEnv(String)}.
     *
     * <p>The name is matched case-insensitively. {@link SystemUtil} reads the OS environment over
     * two {@code .env} files - the class-loader resource at {@code ../.env}, and the file in the
     * directory holding the jar or class directory it was loaded from - so a variable spelled the
     * same in the OS environment and in a file is read from the OS environment.
     *
     * @param variable the name of the variable holding a personal access token
     * @return the write instruction
     * @throws IllegalStateException if no variable of that name is set, or it holds an empty value
     */
    public static @NotNull GitHubToken of(@NotNull String variable) {
        return SystemUtil.getEnv(variable)
            .filter(value -> !StringUtil.isEmpty(value))
            .map(GitHubToken::new)
            .orElseThrow(() -> new IllegalStateException(String.format("'%s' holds no token to write with", variable)));
    }

    /**
     * Takes the token outright, for a caller that already holds one.
     *
     * @param token a personal access token
     * @return the write instruction
     * @throws IllegalStateException if the token is null or empty
     */
    public static @NotNull GitHubToken value(@Nullable String token) {
        if (StringUtil.isEmpty(token))
            throw new IllegalStateException("A write instruction cannot carry an empty token");

        return new GitHubToken(token);
    }

    /**
     * Builds the bearer auth source that sends this token.
     *
     * @return a bearer auth source over this token
     */
    @NotNull GitHubAuth auth() {
        return GitHubAuth.bearer(this.token);
    }

}
