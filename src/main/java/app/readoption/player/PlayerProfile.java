package app.readoption.player;

import app.readoption.scoring.ScoringFormat;

import java.util.List;

public record PlayerProfile(
        String playerId,
        String fullName,
        String position,
        String team,
        ScoringFormat scoringFormat,
        List<SeasonScore> history,
        ProjectionScore projection
) {
}