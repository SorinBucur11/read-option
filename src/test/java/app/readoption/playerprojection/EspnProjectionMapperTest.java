package app.readoption.playerprojection;

import app.readoption.espn.EspnPlayersResponse;
import app.readoption.espn.EspnStatId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EspnProjectionMapper — ESPN season projection lands in raw")
class EspnProjectionMapperTest {

    private final EspnProjectionMapper mapper = new EspnProjectionMapper(new ObjectMapper());

    private static EspnPlayersResponse.Player espnPlayer() {
        EspnPlayersResponse.StatEntry season = new EspnPlayersResponse.StatEntry(
                2026, 1, 0, 0, 280.5,
                Map.of(EspnStatId.PASSING_YARDS, 4200.0, EspnStatId.PASSING_TD, 30.0));
        return new EspnPlayersResponse.Player(
                12345L, "Patrick Mahomes", 0, 12,
                new EspnPlayersResponse.Ownership(1.5), List.of(season));
    }

    @Test
    @DisplayName("team is copied from the resolved player and games_played is 17")
    void teamAndGames() {
        Optional<PlayerProjectionRaw> row = mapper.toRaw("P1", 2026, "KC", espnPlayer());

        assertThat(row).isPresent();
        assertThat(row.get().getTeam()).isEqualTo("KC");
        assertThat(row.get().getGamesPlayed()).isEqualTo(17);
    }

    @Test
    @DisplayName("two_pt_conv stays null (deliberate — ESPN two-pt not reliably mappable)")
    void twoPtConvIsNull() {
        Optional<PlayerProjectionRaw> row = mapper.toRaw("P1", 2026, "KC", espnPlayer());

        assertThat(row).isPresent();
        assertThat(row.get().getTwoPtConv()).isNull();
    }

    @Test
    @DisplayName("source_payload serializes the StatEntry it mapped from")
    void sourcePayloadPresent() {
        Optional<PlayerProjectionRaw> row = mapper.toRaw("P1", 2026, "KC", espnPlayer());

        assertThat(row).isPresent();
        assertThat(row.get().getSourcePayload()).isNotNull();
    }
}
