package api.simplified.github.exception;

import com.google.gson.Gson;
import dev.simplified.client.exception.ApiException;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Thrown when an HTTP request to the GitHub REST API fails.
 *
 * <p>Extends the framework {@link ApiException}, which already implements
 * {@link dev.simplified.client.response.Response} so the full HTTP context (status, headers,
 * body, network details, original request) is available on the exception instance. On top of
 * that this class parses the response body into a {@link GitHubErrorResponse} via the supplied
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
 * {@link dev.simplified.client.exception.NotModifiedException} before per-client error decoders
 * run.
 *
 * <p>This subclass deliberately does NOT follow the five-constructor pattern from the project
 * exception style guide because the {@link ApiException} base only exposes
 * {@code (String methodKey, feign.Response response, String name)} and
 * {@code (FeignException, feign.Response, String)}. Every existing subclass in {@code
 * minecraft-api} ({@code HypixelApiException}, {@code SbsApiException}, {@code MojangApiException})
 * uses the same single-constructor form.
 *
 * @see GitHubErrorResponse
 * @see ApiException
 */
@Getter
public final class GitHubApiException extends ApiException {

    /** The parsed GitHub error body. */
    private final @NotNull GitHubErrorResponse githubResponse;

    /**
     * Constructs a new {@code GitHubApiException} from the Feign method key, the raw response
     * that triggered the failure, and the {@link Gson} used to parse the response body.
     *
     * @param methodKey the Feign method key identifying the failing contract method
     * @param response the raw Feign response carrying status, headers, and body
     * @param gson the Gson instance used to deserialize the GitHub error envelope
     */
    public GitHubApiException(
        @NotNull String methodKey,
        @NotNull feign.Response response,
        @NotNull Gson gson
    ) {
        super(methodKey, response, "GitHub");
        this.githubResponse = this.getBody()
            .map(json -> Optional.ofNullable(super.fromJson(gson, json, GitHubErrorResponse.class))
                .orElseGet(GitHubErrorResponse::unknown))
            .orElseGet(GitHubErrorResponse::unknown);
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

        boolean messageMatches = this.githubResponse.getReason().contains("API rate limit exceeded");
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

        String reason = this.githubResponse.getReason();
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
