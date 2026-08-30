package api.simplified.github;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The instruction that lets a {@link GitHubCorpus} write.
 *
 * <p>Handing one over is the whole of the difference between a corpus a caller may read and one it
 * may update, so a caller holding no instruction has no write half to reach for - not by cast, not
 * by configuration.
 *
 * @see GitHubCorpus#writing(GitHubToken)
 */
public final class GitHubToken {

    private final @NotNull String token;

    private GitHubToken(@NotNull String token) {
        this.token = token;
    }

    /**
     * Reads the token out of the named environment variable.
     *
     * @param variable the environment variable holding a personal access token
     * @return the write instruction
     * @throws IllegalStateException if the variable is unset or blank
     */
    public static @NotNull GitHubToken of(@NotNull String variable) {
        String value = Optional.ofNullable(System.getenv(variable)).orElse("");

        if (value.isBlank())
            throw new IllegalStateException(String.format("'%s' holds no token to write with", variable));

        return new GitHubToken(value);
    }

    /**
     * Takes the token outright, for a caller that already holds one.
     *
     * @param token a personal access token
     * @return the write instruction
     * @throws IllegalStateException if the token is blank
     */
    public static @NotNull GitHubToken value(@NotNull String token) {
        if (token.isBlank())
            throw new IllegalStateException("A write instruction cannot carry a blank token");

        return new GitHubToken(token);
    }

    /**
     * The authentication the token drives.
     *
     * @return a bearer auth source over this token
     */
    @NotNull GitHubAuth auth() {
        return GitHubAuth.bearer(this.token);
    }

}
