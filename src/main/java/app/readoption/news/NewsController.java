package app.readoption.news;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final PlayerNewsSyncService syncService;
    private final NewsEmbeddingService embeddingService;

    public NewsController(PlayerNewsSyncService syncService,
                          NewsEmbeddingService embeddingService) {
        this.syncService = syncService;
        this.embeddingService = embeddingService;
    }

    @PostMapping("/sync")
    public PlayerNewsSyncService.NewsSyncReport sync() {
        return syncService.sync();
    }

    /**
     * Deliberately a separate endpoint from sync: ingestion must never wait on a
     * vendor — an OpenAI outage delays the derived build only.
     */
    @PostMapping("/embed")
    public NewsEmbeddingService.NewsEmbedReport embed() {
        return embeddingService.build();
    }
}
