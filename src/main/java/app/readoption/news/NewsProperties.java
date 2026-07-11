package app.readoption.news;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * News-layer knobs, validated at the startup boundary.
 *
 * @param embeddingModelTag keys the derived {@code news_embedding} rows (part of
 *                          the deterministic UUID and the retrieval filter). A
 *                          <b>property, not derived from provider config</b>: the
 *                          tag names the generation we intend to read, so a
 *                          provider swap is an explicit two-step (re-embed under
 *                          the new tag, then flip this) — never an implicit
 *                          read-miss because someone changed the OpenAI model id.
 * @param topK              server-side retrieval size for searchPlayerNews —
 *                          deliberately NOT a tool parameter; the model gets no
 *                          knob to widen its own context.
 */
@Validated
@ConfigurationProperties(prefix = "readoption.news")
public record NewsProperties(

        @NotBlank String embeddingModelTag,

        @Min(1) @Max(20) int topK
) {
}
