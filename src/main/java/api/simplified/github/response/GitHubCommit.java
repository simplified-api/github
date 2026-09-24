package api.simplified.github.response;

import api.simplified.github.GitHubCorpus;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import org.jetbrains.annotations.NotNull;

/**
 * Gson-bindable mirror of the GitHub "Get a commit" response, returned by
 * {@code GET /repos/{owner}/{repo}/commits/{ref}}.
 *
 * <p>Only the commit sha, its message and its committer's name and date are declared; every
 * other field in the upstream JSON is silently ignored by Gson's reflective binder. The top-level
 * {@link #sha} is the commit a branch points at, which {@link GitHubCorpus#tip()} answers and
 * {@link GitHubCorpus#poll()} compares against the tip its held catalogue was read at;
 * {@link CommitDetail#committer} carries the ISO-8601 timestamp the commit was made at.
 *
 * <p>Instances are produced by {@link Gson#fromJson} inside the
 * {@link Client} response decoder pipeline - never constructed directly
 * by application code, which is why the constructor is private.
 *
 * @see <a href="https://docs.github.com/en/rest/commits/commits?apiVersion=2022-11-28#get-a-commit">GitHub get a commit</a>
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class GitHubCommit {

    /**
     * The commit SHA at the branch tip.
     */
    @SerializedName("sha")
    private final @NotNull String sha;

    /**
     * The nested {@code commit} object carrying author and committer metadata.
     */
    @SerializedName("commit")
    private final @NotNull CommitDetail commit;

    /**
     * Nested {@code commit} object inside the top-level commit response.
     *
     * <p>Narrowed to the two fields the consumer cares about - the commit message for log output
     * and the committer actor for the ISO-8601 timestamp.
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class CommitDetail {

        /**
         * The commit message body as produced by the author.
         */
        @SerializedName("message")
        private final @NotNull String message;

        /**
         * The committer actor carrying name, email, and ISO-8601 date.
         */
        @SerializedName("committer")
        private final @NotNull Actor committer;

    }

    /**
     * A GitHub actor (author or committer) embedded inside {@link CommitDetail}.
     *
     * <p>Fields are kept as plain strings; callers that need a {@code java.time.Instant}
     * can parse {@link #date} lazily via {@code Instant.parse(...)}.
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Actor {

        /**
         * The display name of the actor.
         */
        @SerializedName("name")
        private final @NotNull String name;

        /**
         * The ISO-8601 UTC timestamp at which the commit was authored or committed.
         */
        @SerializedName("date")
        private final @NotNull String date;

    }

}
