package api.simplified.github.exception;

import com.google.gson.Gson;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.JsonApiException;
import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.response.Response;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when an HTTP request to the GitHub REST API fails.
 *
 * <p>Extends {@link JsonApiException}, which already implements
 * {@link Response} so the full HTTP context (status, headers,
 * body, network details, original request) is available on the exception instance. The base
 * class lazily decodes the response body into a {@link GitHubErrorResponse} via the supplied
 * {@link Gson} so callers can reach the GitHub {@code message} and {@code documentation_url}
 * fields without re-parsing.
 *
 * <p>Three helpers disambiguate the common 403/429 confusion surface on the GitHub API:
 * <ul>
 *   <li>{@link #isPrimaryRateLimit()} - 403/429 with {@code x-ratelimit-remaining: 0} and a
 *       message containing {@code "API rate limit exceeded"}.</li>
 *   <li>{@link #isSecondaryRateLimit()} - 403/429 with a message containing {@code "secondary
 *       rate limit"} or {@code "abuse detection"}.</li>
 *   <li>{@link #isPermissions()} - 403 that is neither of the above (PAT scope problem).</li>
 * </ul>
 *
 * <p>A 304 {@code Not Modified} never reaches this class - the framework's
 * {@code InternalErrorDecoder} short-circuits 3xx responses into
 * {@link NotModifiedException} before per-client error decoders
 * run.
 *
 * @see GitHubErrorResponse
 * @see JsonApiException
 * @see ApiException
 */
public final class GitHubApiException extends JsonApiException {

    /**
     * Constructs a new {@code GitHubApiException} from the {@link Gson} used to parse the
     * response body and the primitive HTTP context that triggered the failure.
     *
     * @param gson the Gson instance used to deserialize the GitHub error envelope
     * @param context the primitive HTTP context carrying status, headers, body bytes, and request metadata
     */
    public GitHubApiException(@NotNull Gson gson, @NotNull ErrorContext context) {
        super(context, "GitHub");
        this.resolve(gson, GitHubErrorResponse.class);
    }

    @Override
    public @NotNull GitHubErrorResponse getResponse() {
        return (GitHubErrorResponse) super.getResponse();
    }

    /**
     * Returns whether this failure represents a primary rate-limit exhaustion.
     *
     * <p>Primary rate limit is defined as a 403 or 429 status with both
     * {@code x-ratelimit-remaining: 0} AND a body message containing
     * {@code "API rate limit exceeded"}. Requiring both signals makes the check robust to
     * GitHub tweaking the exact wording of the message text.
     *
     * @return {@code true} when both signals match
     */
    public boolean isPrimaryRateLimit() {
        int code = this.getStatus().getCode();

        if (code != 403 && code != 429)
            return false;

        boolean quotaZero = this.getHeaders()
            .getOptional("x-ratelimit-remaining")
            .flatMap(values -> values.stream().findFirst())
            .map("0"::equals)
            .orElse(false);

        boolean messageMatches = this.getResponse().getReason().contains("API rate limit exceeded");
        return quotaZero && messageMatches;
    }

    /**
     * Returns whether this failure represents a secondary rate-limit or abuse-detection trip.
     *
     * @return {@code true} when the status is 403/429 and the body signals a secondary limit
     */
    public boolean isSecondaryRateLimit() {
        int code = this.getStatus().getCode();

        if (code != 403 && code != 429)
            return false;

        String reason = this.getResponse().getReason();
        return reason.contains("secondary rate limit") || reason.contains("abuse detection");
    }

    /**
     * Returns whether this failure represents a plain permissions rejection rather than a
     * rate-limit trip.
     *
     * @return {@code true} when the status is 403 and neither rate-limit signal matches
     */
    public boolean isPermissions() {
        return this.getStatus().getCode() == 403
            && !this.isPrimaryRateLimit()
            && !this.isSecondaryRateLimit();
    }

}
