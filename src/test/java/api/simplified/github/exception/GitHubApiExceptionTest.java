package api.simplified.github.exception;

import com.google.gson.Gson;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the 403/429 disambiguation helpers on {@link GitHubApiException}.
 *
 * <p>Every test constructs a primitive {@link ErrorContext} with hand-crafted status, headers,
 * and body bytes and verifies the helper methods return the correct classification. No
 * network I/O and no {@code feign.Response} fixture is required.
 */
class GitHubApiExceptionTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("isPrimaryRateLimit() true when 403 + x-ratelimit-remaining: 0 + message matches")
    void primaryRateLimit403() {
        GitHubApiException ex = build(
            403,
            Map.of("x-ratelimit-remaining", List.of("0")),
            "{\"message\":\"API rate limit exceeded for user ID 1234567.\",\"documentation_url\":\"\"}"
        );

        assertThat(ex.isPrimaryRateLimit(), is(true));
        assertThat(ex.isPermissions(), is(false));
        assertThat(ex.isSecondaryRateLimit(), is(false));
    }

    @Test
    @DisplayName("isPrimaryRateLimit() true when 429 + x-ratelimit-remaining: 0 + message matches")
    void primaryRateLimit429() {
        GitHubApiException ex = build(
            429,
            Map.of("x-ratelimit-remaining", List.of("0")),
            "{\"message\":\"API rate limit exceeded\",\"documentation_url\":\"\"}"
        );

        assertThat(ex.isPrimaryRateLimit(), is(true));
    }

    @Test
    @DisplayName("isPermissions() true on 403 with non-zero remaining and non-rate-limit message")
    void permissions403() {
        GitHubApiException ex = build(
            403,
            Map.of("x-ratelimit-remaining", List.of("4999")),
            "{\"message\":\"Resource not accessible by personal access token\",\"documentation_url\":\"\"}"
        );

        assertThat(ex.isPermissions(), is(true));
        assertThat(ex.isPrimaryRateLimit(), is(false));
        assertThat(ex.isSecondaryRateLimit(), is(false));
    }

    @Test
    @DisplayName("isSecondaryRateLimit() true when message contains 'secondary rate limit'")
    void secondaryRateLimit() {
        GitHubApiException ex = build(
            403,
            Map.of("x-ratelimit-remaining", List.of("4999"), "retry-after", List.of("60")),
            "{\"message\":\"You have exceeded a secondary rate limit\",\"documentation_url\":\"\"}"
        );

        assertThat(ex.isSecondaryRateLimit(), is(true));
        assertThat(ex.isPermissions(), is(false));
    }

    @Test
    @DisplayName("getResponse() falls back to a fresh instance with field-default message on non-JSON body")
    void getResponseFallback() {
        GitHubApiException ex = build(500, Map.of(), "<html>503 oops</html>");

        assertThat(ex.getResponse().getReason(), equalTo("Unknown (body missing or not JSON)"));
    }

    @Test
    @DisplayName("getResponse().getReason() returns the parsed message")
    void getResponseParsed() {
        GitHubApiException ex = build(
            404,
            Map.of(),
            "{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}"
        );

        assertThat(ex.getResponse().getReason(), equalTo("Not Found"));
    }

    private static GitHubApiException build(int status, Map<String, List<String>> headers, String body) {
        Map<String, Collection<String>> headerMap = new HashMap<>();
        headers.forEach(headerMap::put);
        ErrorContext context = new ErrorContext(
            HttpStatus.of(status),
            NetworkDetails.empty(),
            HttpMethod.GET,
            "https://api.github.com/repos/skyblock-simplified/skyblock-data/contents/data/v1/index.json",
            headerMap,
            body.getBytes(StandardCharsets.UTF_8)
        );
        return new GitHubApiException(GSON, context);
    }

}
