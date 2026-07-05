package app.readoption.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conversation memory for the draft agent, keyed on {@code sessionId}. In-memory
 * by design for 4.2 — a draft is a single sitting; JDBC-backed memory (restart /
 * multi-instance survival) is a deferred increment. {@code ToolCallingManager} is
 * not defined here: the Spring AI tool autoconfiguration provides it, and the
 * agent only needs the default execution behavior (tool exceptions surface to the
 * model as error tool-responses, which is exactly the honest-degradation path).
 */
@Configuration
public class AgentConfig {

    @Bean
    public ChatMemory draftAgentChatMemory(AgentProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(properties.memoryWindow())
                .build();
    }
}
