package app.readoption.sleeper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO parsing against the REAL probe payloads (plus the labeled
 * executor-authored pre_draft/drafting pair — see the fixture README). These
 * pin the wire facts the sync's gates depend on: {@code draft_order} null until
 * drafting, {@code reversal_round} absent-vs-zero, {@code is_keeper} null on
 * every observed pick.
 */
@DisplayName("Sleeper draft DTOs — parsing pinned against probe payloads")
class SleeperDraftDtoTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private <T> T read(String resource, Class<T> type) {
        try (InputStream in = getClass().getResourceAsStream("/sleeper/" + resource)) {
            assertThat(in).as("fixture /sleeper/" + resource).isNotNull();
            return MAPPER.readValue(in, type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed reading fixture " + resource, e);
        }
    }

    private List<SleeperDraftPick> readPicks(String resource) {
        try (InputStream in = getClass().getResourceAsStream("/sleeper/" + resource)) {
            assertThat(in).as("fixture /sleeper/" + resource).isNotNull();
            return MAPPER.readValue(in, new TypeReference<List<SleeperDraftPick>>() {});
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed reading fixture " + resource, e);
        }
    }

    @Test
    @DisplayName("pre_draft object: draft_order null, reversal_round ABSENT -> field null")
    void preDraftObject() {
        SleeperDraft draft = read("draft-pre_draft.json", SleeperDraft.class);

        assertThat(draft.status()).isEqualTo("pre_draft");
        assertThat(draft.type()).isEqualTo("snake");
        assertThat(draft.draftOrder()).isNull();
        assertThat(draft.settings().reversalRound()).isNull();   // absent, NOT zero
        assertThat(draft.settings().teams()).isEqualTo(10);
        assertThat(draft.settings().rounds()).isEqualTo(15);
    }

    @Test
    @DisplayName("drafting object: draft_order populated with the user at slot 7")
    void draftingObject() {
        SleeperDraft draft = read("draft-drafting.json", SleeperDraft.class);

        assertThat(draft.status()).isEqualTo("drafting");
        assertThat(draft.draftOrder()).containsEntry("87732859926102016", 7);
        assertThat(draft.settings().reversalRound()).isNull();
    }

    @Test
    @DisplayName("league draft object (p4): reversal_round present as 0 -> field zero, not null")
    void leagueDraftObject() {
        SleeperDraft draft = read("draft-league-complete.json", SleeperDraft.class);

        assertThat(draft.draftId()).isEqualTo("1382308407742062592");
        assertThat(draft.status()).isEqualTo("complete");
        assertThat(draft.type()).isEqualTo("snake");
        assertThat(draft.season()).isEqualTo("2026");
        assertThat(draft.settings().reversalRound()).isZero();   // explicit 0 — the absent/zero distinction
        assertThat(draft.settings().pickTimer()).isEqualTo(120);
        assertThat(draft.settings().teams()).isEqualTo(10);
        assertThat(draft.settings().rounds()).isEqualTo(15);
        assertThat(draft.draftOrder()).containsEntry("87732859926102016", 4);
    }

    @Test
    @DisplayName("p6a live 3-pick array parses; is_keeper null throughout")
    void livePicksArray() {
        List<SleeperDraftPick> picks = readPicks("picks-live-3.json");

        assertThat(picks).hasSize(3);
        SleeperDraftPick first = picks.get(0);
        assertThat(first.pickNo()).isEqualTo(1);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.draftSlot()).isEqualTo(1);
        assertThat(first.playerId()).isEqualTo("9221");
        assertThat(first.rosterId()).isEqualTo(2);
        assertThat(picks).allSatisfy(pick -> assertThat(pick.isKeeper()).isNull());
    }

    @Test
    @DisplayName("p5 full 150-pick array parses; is_keeper null on all 150")
    void completePicksArray() {
        List<SleeperDraftPick> picks = readPicks("picks-complete-150.json");

        assertThat(picks).hasSize(150);
        assertThat(picks).allSatisfy(pick -> assertThat(pick.isKeeper()).isNull());
        SleeperDraftPick last = picks.get(149);
        assertThat(last.pickNo()).isEqualTo(150);
        assertThat(last.round()).isEqualTo(15);
        assertThat(last.draftSlot()).isEqualTo(10);
        assertThat(last.playerId()).isEqualTo("8042");
    }

    @Test
    @DisplayName("user object (p1 shape): user_id resolves")
    void userObject() {
        SleeperUser user = MAPPER.readValue(
                "{\"user_id\":\"87732859926102016\",\"username\":\"sosososik\",\"avatar\":\"nfl_nyg\"}",
                SleeperUser.class);

        assertThat(user.userId()).isEqualTo("87732859926102016");
        assertThat(user.username()).isEqualTo("sosososik");
    }
}
