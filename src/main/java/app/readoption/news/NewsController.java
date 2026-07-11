package app.readoption.news;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final PlayerNewsSyncService syncService;

    public NewsController(PlayerNewsSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/sync")
    public PlayerNewsSyncService.NewsSyncReport sync() {
        return syncService.sync();
    }
}
