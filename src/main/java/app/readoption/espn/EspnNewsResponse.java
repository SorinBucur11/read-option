package app.readoption.espn;

import java.util.List;

/**
 * The slice of ESPN's player-news payload the ingestion needs. {@code type} is
 * load-bearing: the feed multiplexes {@code Rotowire} player blurbs with
 * {@code Story}/editorial items; only Rotowire items land (phase-4.4 D0). Field
 * names match the wire, so no {@code @JsonProperty} needed (Spring's converter
 * ignores the payload's many unknown properties — images, links, etc.).
 *
 * <p>Timestamps stay wire-verbatim strings here; the mapper owns the
 * {@code Instant} parse so a malformed date fails that player's sync loudly
 * instead of degrading silently inside message conversion.
 */
public record EspnNewsResponse(List<Item> feed) {

    public record Item(
            Long id,
            String type,
            String headline,
            String description,
            String story,
            String published,
            String lastModified,
            Boolean premium,
            Long playerId) {
    }
}
