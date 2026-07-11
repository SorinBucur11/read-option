package app.readoption;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context boot doubles as the EV-1 pin: two model providers on the
 * classpath (Anthropic chat + OpenAI embeddings), selection disambiguated by
 * {@code spring.ai.model.chat=anthropic} / {@code spring.ai.model.embedding=openai}
 * — exactly one ChatModel (Anthropic) and exactly one EmbeddingModel (OpenAI)
 * may exist, or the agent's constructor injection is ambiguous. Boot also runs
 * the PgVectorStore schema validation against the Flyway-built {@code
 * news_embedding} table (the V-2 startup drift guard).
 *
 * <p>The OpenAI key is a dummy: bean construction never calls the API, and no
 * test may ever call a live model.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key-never-called")
class ReadOptionApplicationTests extends AbstractPostgresTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
		String[] chatModels = context.getBeanNamesForType(ChatModel.class);
		assertThat(chatModels).hasSize(1);
		assertThat(context.getBean(ChatModel.class).getClass().getName())
				.contains("Anthropic");

		String[] embeddingModels = context.getBeanNamesForType(EmbeddingModel.class);
		assertThat(embeddingModels).hasSize(1);
		assertThat(context.getBean(EmbeddingModel.class).getClass().getName())
				.contains("OpenAi");
	}

}
