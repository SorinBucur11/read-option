package app.readoption.customization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeagueParsingService — system prompt loads from the classpath file, fail-fast when blank")
class LeagueParsingServiceTest {

    @Mock private ChatClient.Builder builder;
    @Mock private ChatClient chatClient;

    private final CustomizationProperties properties =
            new CustomizationProperties("claude-sonnet-4-6", 5);

    @BeforeEach
    void stubBuilder() {
        // lenient: the fail-fast tests throw before the builder is ever touched.
        lenient().when(builder.defaultSystem(anyString())).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(chatClient);
    }

    @Test
    @DisplayName("the full prompt file becomes the default system prompt")
    void loadsFullPromptFromClasspath() throws IOException {
        Resource prompt = new ClassPathResource("prompts/league-parser.txt");
        String expected = prompt.getContentAsString(StandardCharsets.UTF_8);

        new LeagueParsingService(builder, properties, new ObjectMapper(), prompt);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(builder).defaultSystem(captor.capture());
        assertThat(captor.getValue()).isEqualTo(expected);
        // The old properties-value prompt was ~1600 chars; a truncated load would be shorter.
        assertThat(captor.getValue().length()).isGreaterThan(1000);
        assertThat(captor.getValue()).contains("NEVER originate a scoring number");
        assertThat(captor.getValue().trim()).endsWith("no prose around it.");
    }

    @Test
    @DisplayName("a blank prompt file fails startup, not the first request")
    void blankPromptFailsFast() {
        Resource blank = new ByteArrayResource("   \n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() ->
                new LeagueParsingService(builder, properties, new ObjectMapper(), blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("league-parser system prompt is empty");
    }

    @Test
    @DisplayName("a missing prompt file fails startup")
    void missingPromptFailsFast() {
        Resource missing = new ClassPathResource("prompts/does-not-exist.txt");

        assertThatThrownBy(() ->
                new LeagueParsingService(builder, properties, new ObjectMapper(), missing))
                .isInstanceOf(UncheckedIOException.class);
    }
}
