package app.readoption.news;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Deterministic 1536-dim fake — the embedding twin of the mocked ChatModel
 * (phase-4.4 D2): same text always embeds to the same vector, so retrieval tests
 * are stable and no test ever calls the live provider.
 *
 * <p>Components are strictly positive on purpose: PgVectorStore's similarity SQL
 * excludes rows at cosine distance ≥ 1 (its default threshold), and any two
 * positive vectors keep cosine similarity &gt; 0 — a fake with signed components
 * could silently filter out valid rows and fake a NO_NEWS_FOUND.
 */
class FakeEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 1536;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                .mapToObj(i -> new Embedding(vectorFor(request.getInstructions().get(i)), i))
                .toList();
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorFor(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vectorFor(String text) {
        Random random = new Random(text == null ? 0 : text.hashCode());
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = 0.05f + random.nextFloat();   // strictly positive, deterministic
        }
        return vector;
    }
}
