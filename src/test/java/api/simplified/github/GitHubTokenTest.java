package api.simplified.github;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers which tokens are refused where they are made - a null or empty value, and a variable no
 * environment carries - and which are taken.
 *
 * <p>No case sets an environment variable or writes a {@code .env} file. The variable the refusal
 * case reads is named afresh from a random UUID on every run, so no environment carries it.
 */
class GitHubTokenTest {

    @Test
    @DisplayName("a null token is refused")
    void nullValueIsRefused() {
        assertThrows(IllegalStateException.class, () -> GitHubToken.value(null));
    }

    @Test
    @DisplayName("an empty token is refused")
    void emptyValueIsRefused() {
        assertThrows(IllegalStateException.class, () -> GitHubToken.value(""));
    }

    @Test
    @DisplayName("a variable no environment carries is refused")
    void unsetVariableIsRefused() {
        String variable = "GITHUB_TOKEN_UNSET_" + UUID.randomUUID().toString().replace('-', '_');
        assertThrows(IllegalStateException.class, () -> GitHubToken.of(variable));
    }

    @Test
    @DisplayName("a token is taken and sent as a bearer Authorization header")
    void tokenIsSentAsBearer() {
        assertThat(GitHubToken.value("t").auth().get(), equalTo(Optional.of("Bearer t")));
    }

    @Test
    @DisplayName("a token of whitespace alone is taken and sent as it is")
    void whitespaceTokenIsTaken() {
        assertThat(GitHubToken.value(" ").auth().get(), equalTo(Optional.of("Bearer  ")));
    }

}
