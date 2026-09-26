package api.simplified.github;

import com.google.gson.Gson;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import feign.Feign;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import feign.Util;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cases covering what a corpus reads around its own write: once the write returns or throws, a
 * read either client answered from its response cache before it reaches the repository again.
 *
 * <p>Each contract is a Feign proxy assembled as a client assembles its own - a
 * {@link CachingFeignClient} over the transport and an {@link InternalResponseDecoder} storing
 * every answer, both over one {@link ResponseCache} per contract - over a transport answering from
 * memory, so no request leaves the machine. Every answer carries {@code Cache-Control: max-age=60},
 * as GitHub's do, so a read repeated before a write is answered from the cache.
 */
class GitHubCorpusWriteTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull String ORIGIN = "https://api.github.com";
    private static final @NotNull String TIP = "reads GET /repos/owner/repo/commits/master";
    private static final @NotNull String FILE = "reads GET /repos/owner/repo/contents/a.json?ref=master";
    private static final @NotNull String SHA = "writes GET /repos/owner/repo/contents/a.json?ref=master";
    private static final @NotNull String PUT = "writes PUT /repos/owner/repo/contents/a.json";

    private final @NotNull ResponseCache readCache = new ResponseCache(1L << 20, 3_600_000L);
    private final @NotNull ResponseCache writeCache = new ResponseCache(1L << 20, 3_600_000L);

    /**
     * Every request that reached the repository, as the contract it came through, its method and
     * its path.
     */
    private final @NotNull ConcurrentList<String> sent = Concurrent.newList();

    /**
     * Whether the repository takes a write and loses the answer to it.
     */
    private volatile boolean losesWriteAnswers;

    private final @NotNull GitHubContentsContract reads = this.proxy(GitHubContentsContract.class, "reads", this.readCache, "[]");
    private final @NotNull GitHubContentsWriteContract writes = this.proxy(GitHubContentsWriteContract.class, "writes", this.writeCache, "{\"sha\":\"b1\"}");

    /**
     * Builds a Feign proxy over the transport, storing its answers in the given cache.
     *
     * @param contract the contract the proxy implements
     * @param label the name each request through it is recorded under
     * @param cache the response cache the proxy looks answers up in and stores them to
     * @param file the body a file read through it answers
     * @param <C> the contract type
     * @return the proxy
     */
    private <C> @NotNull C proxy(
        @NotNull Class<C> contract,
        @NotNull String label,
        @NotNull ResponseCache cache,
        @NotNull String file
    ) {
        return Feign.builder()
            .client(new CachingFeignClient((request, options) -> this.answer(request, label, file), cache))
            .encoder((body, type, template) -> template.body(GSON.toJson(body).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
            .decoder(new InternalResponseDecoder(
                (response, type) -> GSON.fromJson(Util.toString(response.body().asReader(StandardCharsets.UTF_8)), type),
                cache
            ))
            .retryer(Retryer.NEVER_RETRY)
            .target(contract, ORIGIN);
    }

    /**
     * Answers a request from memory as the repository would, recording it first.
     *
     * @param request the request
     * @param label the name the request is recorded under
     * @param file the body a file read answers
     * @return the answer, fresh for a minute
     * @throws IOException when the request is a write and the repository loses its answer
     */
    private @NotNull feign.Response answer(
        @NotNull Request request,
        @NotNull String label,
        @NotNull String file
    ) throws IOException {
        boolean write = request.httpMethod() == Request.HttpMethod.PUT;
        this.sent.add(String.format("%s %s %s", label, request.httpMethod(), request.url().substring(ORIGIN.length())));

        if (write && this.losesWriteAnswers)
            throw new IOException("The answer to the write was lost");

        String body = write ? "{}" : request.url().contains("/commits/") ? "{\"sha\":\"c1\"}" : file;

        return feign.Response.builder()
            .status(200)
            .reason("OK")
            .request(request)
            .headers(Map.<String, Collection<String>>of("Cache-Control", List.of("max-age=60")))
            .body(body, StandardCharsets.UTF_8)
            .build();
    }

    /**
     * Reads the tip and a file through the read contract and the file's blob sha through the
     * write contract.
     *
     * @param corpus the corpus to read through
     */
    private static void readEach(@NotNull GitHubCorpus corpus) {
        corpus.tip();
        corpus.read("a.json");
        corpus.metadata("a.json");
    }

    /**
     * Writes the file through the write contract under the blob sha the repository answers.
     *
     * @param corpus the corpus to write through
     */
    private static void write(@NotNull GitHubCorpus corpus) {
        corpus.write("a.json", "[]", "b1", "Update a.json");
    }

    /**
     * Builds a corpus over both contracts that drops both caches when it writes, as a corpus
     * {@link GitHubCorpus.Builder#build()} makes drops its two clients' caches.
     *
     * @return the corpus
     */
    private @NotNull GitHubCorpus corpus() {
        return new GitHubCorpus(
            GitHubCorpus.of("owner", "repo"),
            this.reads,
            this.writes,
            Concurrent.newUnmodifiableList(this.readCache, this.writeCache)
        );
    }

    @Test
    @DisplayName("a write drops what both clients cached, so the tip, a file and a blob sha read after it reach the repository")
    void aWriteDropsWhatBothClientsCached() {
        GitHubCorpus corpus = this.corpus();
        readEach(corpus);
        readEach(corpus);

        write(corpus);
        readEach(corpus);

        assertThat(this.sent, contains(TIP, FILE, SHA, PUT, TIP, FILE, SHA));
    }

    @Test
    @DisplayName("a write whose answer is lost still drops what both clients cached, so the reads after it reach the repository")
    void aWriteWhoseAnswerIsLostDropsWhatBothClientsCached() {
        GitHubCorpus corpus = this.corpus();
        readEach(corpus);
        readEach(corpus);

        this.losesWriteAnswers = true;
        assertThrows(RetryableException.class, () -> write(corpus));
        readEach(corpus);

        assertThat(this.sent, contains(TIP, FILE, SHA, PUT, TIP, FILE, SHA));
    }

    @Test
    @DisplayName("a corpus over contracts the caller supplies drops nothing when it writes, so the caller's caches answer the reads after it")
    void aCorpusOverTheCallersContractsDropsNothing() {
        GitHubCorpus corpus = GitHubCorpus.of("owner", "repo").build(this.reads, this.writes);
        readEach(corpus);

        write(corpus);
        readEach(corpus);

        assertThat(this.sent, contains(TIP, FILE, SHA, PUT));
    }

}
