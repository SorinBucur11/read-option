package app.readoption.customization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/league/parse} — the plain-English league description. */
public record ParseRequest(
        @NotBlank @Size(max = 5000) String description) {
}
