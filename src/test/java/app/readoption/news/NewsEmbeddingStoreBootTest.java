package app.readoption.news;

import app.readoption.AbstractPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The empirical pin on V-1: {@code PgVectorStore} with
 * {@code vectorTableValidationsEnabled=true} must accept the V16 table whose
 * {@code metadata} column is <b>jsonb</b> where the store's own template says
 * json — the 2.0.0 validator checks column names and vector dimensions
 * ({@code pg_attribute.atttypmod}), never column types. If a future Spring AI
 * upgrade adds type checks, THIS test fails before the app does.
 *
 * <p>The negative case proves the validator actually ran (a validation that
 * silently no-ops would pass the positive case too).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("news_embedding × PgVectorStore validation — the V-1 pin and the V-2 drift guard")
class NewsEmbeddingStoreBootTest extends AbstractPostgresTest {

    @Autowired private DataSource dataSource;

    private PgVectorStore.PgVectorStoreBuilder builder() {
        return PgVectorStore.builder(new JdbcTemplate(dataSource), new FakeEmbeddingModel())
                .vectorTableName("news_embedding")
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW);
    }

    @Test
    @DisplayName("validation-on store boots against the V16 jsonb table (V-1 closed empirically)")
    void validationAcceptsTheJsonbTable() {
        PgVectorStore store = builder().dimensions(1536).build();

        assertThatCode(store::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a dimension mismatch fails the boot loudly - the validator really runs (V-2)")
    void dimensionDriftFailsTheBoot() {
        PgVectorStore store = builder().dimensions(999).build();

        assertThatThrownBy(store::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");
    }
}
