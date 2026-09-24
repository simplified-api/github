package api.simplified.github;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Covers the {@code Authorization} header a bearer auth source supplies, including for the null
 * and empty tokens an unset or empty environment variable reads as.
 */
class GitHubAuthTest {

    @Test
    @DisplayName("a bearer source over a null token supplies no Authorization header")
    void nullTokenSuppliesNoHeader() {
        assertThat(GitHubAuth.bearer(null).get(), equalTo(Optional.empty()));
    }

    @Test
    @DisplayName("a bearer source over an empty token supplies no Authorization header")
    void emptyTokenSuppliesNoHeader() {
        assertThat(GitHubAuth.bearer("").get(), equalTo(Optional.empty()));
    }

    @Test
    @DisplayName("a bearer source over a token supplies 'Bearer' and the token")
    void tokenSuppliesBearerHeader() {
        assertThat(GitHubAuth.bearer("t").get(), equalTo(Optional.of("Bearer t")));
    }

}
