package api.simplified.github;

import dev.simplified.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Token-based authentication source for the GitHub REST API.
 *
 * <p>Plugged into the framework's dynamic-header pipeline as the value supplier for the
 * {@code Authorization} header. Returning an empty {@link Optional} on every invocation
 * degrades the client to unauthenticated mode (60 requests per hour per IP) without failing
 * the proxy build; returning {@code Optional.of("Bearer <token>")} authenticates the request.
 *
 * <p>The interface is a {@link Supplier} subtype so it drops in directly anywhere the
 * framework expects {@code Supplier<Optional<String>>} as the dynamic header source.
 *
 * @see <a href="https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api">
 *      GitHub REST authentication</a>
 */
@FunctionalInterface
public interface GitHubAuth extends Supplier<Optional<String>> {

    /**
     * Builds a bearer-token auth source from the given personal access token.
     *
     * <p>A null or empty token degrades to {@link #unauthenticated()}. That is what lets the
     * result of {@link System#getenv(String)} pass straight through without a branch at the call
     * site - null when the variable is unset, empty when it is set to nothing. A token of
     * whitespace alone is not empty, and is sent as it is.
     *
     * @param token the personal access token, or {@code null} for none
     * @return an auth source carrying the {@code Bearer <token>} header value, or the
     *         unauthenticated source when the token is null or empty
     */
    static @NotNull GitHubAuth bearer(@Nullable String token) {
        if (StringUtil.isEmpty(token))
            return unauthenticated();

        Optional<String> header = Optional.of("Bearer " + token);
        return () -> header;
    }

    /**
     * Returns a sentinel auth source that never supplies an {@code Authorization} header.
     *
     * @return the unauthenticated supplier
     */
    static @NotNull GitHubAuth unauthenticated() {
        return Optional::empty;
    }

}
