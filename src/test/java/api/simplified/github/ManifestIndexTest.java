package api.simplified.github;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Covers what a catalogue answers about a document made of more than one layer.
 *
 * <p>A rule reading only the first layer's hash cannot see a companion change, so an override looks
 * like nothing happened. These cases move the companion.
 */
class ManifestIndexTest {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * The wire shape, so a case builds a catalogue the same way a corpus parses one.
     */
    private record Catalogue(
        @NotNull String revision,
        @NotNull ConcurrentMap<String, ConcurrentList<ManifestIndex.Layer>> documents
    ) {}

    private static @NotNull ManifestIndex catalogue(@NotNull ManifestIndex.Layer @NotNull ... layers) {
        ConcurrentList<ManifestIndex.Layer> listed = Concurrent.newList();
        listed.addAll(List.of(layers));

        return GSON.fromJson(
            GSON.toJson(new Catalogue("rev", Concurrent.newMap(Map.of("layered", listed)))),
            ManifestIndex.class
        );
    }

    @Test
    @DisplayName("the fingerprint moves when any layer moves, not only the first")
    void fingerprintComposesEveryLayer() {
        ManifestIndex one = catalogue(new ManifestIndex.Layer("a.json", "aaa"), new ManifestIndex.Layer("b.json", "bbb"));
        ManifestIndex two = catalogue(new ManifestIndex.Layer("a.json", "aaa"), new ManifestIndex.Layer("b.json", "ccc"));

        assertThat(one.fingerprintOf("layered").orElseThrow(), equalTo("aaa:bbb"));
        assertThat(two.fingerprintOf("layered").orElseThrow(), equalTo("aaa:ccc"));
        assertThat(one.fingerprintOf("layered").equals(two.fingerprintOf("layered")), is(false));
    }

    @Test
    @DisplayName("an unnamed document has no fingerprint, which is not the same as an unchanged one")
    void absentDocumentHasNoFingerprint() {
        assertThat(ManifestIndex.empty().fingerprintOf("layered").isEmpty(), is(true));
        assertThat(ManifestIndex.empty().layersOf("layered").isEmpty(), is(true));
    }

    @Test
    @DisplayName("the layers a document names come back in the order the catalogue listed them")
    void layersKeepTheirOrder() {
        ManifestIndex catalogue = catalogue(
            new ManifestIndex.Layer("first.json", "aaa"),
            new ManifestIndex.Layer("second.json", "bbb")
        );

        assertThat(
            catalogue.layersOf("layered").stream().map(ManifestIndex.Layer::path).toList(),
            contains("first.json", "second.json")
        );
        assertThat(catalogue.getRevision(), equalTo("rev"));
    }

}
