package api.simplified.github;

import org.jetbrains.annotations.NotNull;

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
     * <p>A blank or empty token degrades to {@link #unauthenticated()} so callers can pass an
     * unset environment variable straight through without branching.
     *
     * @param token the personal access token, possibly blank
     * @return an auth source carrying the {@code Bearer <token>} header value, or empty when
     *         the token is blank
     */
    static @NotNull GitHubAuth bearer(@NotNull String token) {
        if (token.isBlank())
            return unauthenticated();

        String headerValue = "Bearer " + token;
        return () -> Optional.of(headerValue);
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
