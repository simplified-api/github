package api.simplified.github.exception;

import com.google.gson.annotations.SerializedName;
import dev.simplified.client.exception.ApiErrorResponse;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Parsed error body returned by the GitHub REST API on every non-2xx response.
 *
 * <p>GitHub emits a consistent shape for every failure:
 * <pre>{@code
 * {
 *   "message": "API rate limit exceeded for user ID 1234567.",
 *   "documentation_url": "https://docs.github.com/rest/overview/rate-limits-for-the-rest-api"
 * }
 * }</pre>
 *
 * <p>This mirror implements {@link ApiErrorResponse} so that the framework's
 * {@link dev.simplified.client.exception.ApiException#getResponse()} accessor returns a usable
 * instance. The framework interface requires a single {@code getReason()} accessor; this class
 * maps {@code reason} to the parsed {@code message} field so GitHub's wording is preserved
 * verbatim.
 *
 * <p>Field initializers carry the fallback defaults used when the body is absent or
 * unparseable - {@link dev.simplified.client.exception.JsonApiException} constructs a fresh
 * instance reflectively in that case.
 *
 * @see ApiErrorResponse
 * @see GitHubApiException
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubErrorResponse implements ApiErrorResponse {

    /** The human-readable error message emitted by GitHub. */
    @SerializedName("message")
    protected @NotNull String message = "Unknown (body missing or not JSON)";

    /** The URL to GitHub's documentation for this error class. May be empty. */
    @SerializedName("documentation_url")
    protected @NotNull String documentationUrl = "";

    /**
     * Returns the parsed {@code message} field as the framework-required reason string.
     *
     * @return the error message
     */
    @Override
    public @NotNull String getReason() {
        return this.message;
    }

}
