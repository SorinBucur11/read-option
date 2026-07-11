package app.readoption.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit {@link PgVectorStore} bean — deliberately the core artifact, not the
 * starter: we own the construction (Flyway owns the DDL, so
 * {@code initializeSchema=false}) and skip the autoconfig surface.
 *
 * <p>{@code vectorTableValidationsEnabled=true} turns the store's schema check
 * into a startup drift guard (V-2): if a future migration or Spring AI upgrade
 * breaks the column/dimension contract with {@code news_embedding}, the context
 * fails at boot, not on the first draft-day search. Dimensions are pinned to the
 * DDL's VECTOR(1536), never inferred from the embedding model.
 */
@Configuration
public class VectorStoreConfig {

    static final String NEWS_EMBEDDING_TABLE = "news_embedding";
    static final int EMBEDDING_DIMENSIONS = 1536;

    // The store validates the table in afterPropertiesSet — it must initialize
    // AFTER Flyway has built news_embedding; PgVectorStore is not one of the
    // types Boot auto-orders behind database initialization.
    @Bean
    @DependsOnDatabaseInitialization
    public PgVectorStore newsVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(NEWS_EMBEDDING_TABLE)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .dimensions(EMBEDDING_DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .build();
    }
}
