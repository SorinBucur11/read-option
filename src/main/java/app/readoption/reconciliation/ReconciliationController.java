package app.readoption.reconciliation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives the staging→mart reconciliation. {@code dryRun=true} returns the CV
 * distribution for threshold calibration without writing, calling the model, or
 * re-scoring. Failures surface as RFC 9457 ProblemDetail via the global handler.
 */
@RestController
@RequestMapping("/api/projections/reconcile")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/{season}")
    public ReconciliationReport reconcile(@PathVariable int season,
                                          @RequestParam(defaultValue = "false") boolean dryRun) {
        return reconciliationService.reconcile(season, dryRun);
    }
}
