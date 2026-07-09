package app.readoption.customization;

import app.readoption.AbstractPostgresTest;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)   // real container, real V10 columns
@DisplayName("LeagueConfig persistence — NUMERIC(4,2) scale, IDENTITY id, JSONB tactics round-trip")
class LeagueConfigRepositoryTest extends AbstractPostgresTest {

    @Autowired private LeagueConfigRepository repository;
    @Autowired private TestEntityManager entityManager;

    private static LeagueConfig.LeagueConfigBuilder validConfig() {
        return LeagueConfig.builder()
                .receptionFormat(ReceptionFormat.HALF_PPR)
                .passingTdPoints(new BigDecimal("4"))
                .interceptionPoints(new BigDecimal("-2"))
                .teReceptionBonus(new BigDecimal("0"))
                .teamCount(12).qbSlots(1).rbSlots(2).wrSlots(2).teSlots(1).flexSlots(1)
                .flexEligible(Set.of(Position.RB, Position.WR, Position.TE))
                .superflexSlots(0).benchSlots(6);
    }

    @Test
    @DisplayName("a fractional interception value (-0.5) round-trips through NUMERIC(4,2) unchanged")
    void fractionalInterceptionRoundTrips() {
        // Through the resolver, as confirm would: the spec carries -0.5 as BigDecimal.
        ScoringSpec scoring = new ScoringSpec(ReceptionFormat.PPR, null, new BigDecimal("-0.5"), false);
        RosterSpec roster = new RosterSpec(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
        LeagueRules resolved = new LeagueRulesResolver()
                .resolve(new LeagueRulesSpec(scoring, roster, null));
        assertThat(resolved.scoring().interceptionPoints()).isEqualByComparingTo("-0.5");

        LeagueConfig saved = repository.save(validConfig()
                .receptionFormat(ReceptionFormat.PPR)
                .interceptionPoints(resolved.scoring().interceptionPoints())
                .build());
        entityManager.flush();
        entityManager.clear();   // force the reload to come from the column, not the cache

        LeagueConfig reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getInterceptionPoints()).isEqualByComparingTo("-0.5");
        assertThat(reloaded.getPassingTdPoints()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("save generates an id and the row is retrievable by it")
    void saveGeneratesIdAndIsRetrievable() {
        LeagueConfig saved = repository.save(validConfig().build());

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findById(saved.getId())).isPresent();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("DraftTactics round-trips through the JSONB column typed — the Phase 2 gap closed")
    void tacticsRoundTripsThroughJsonb() {
        DraftTactics tactics = new DraftTactics(
                PositionalStrategy.HERO_RB,
                RiskPosture.UPSIDE,
                Map.of(Position.QB, 10, Position.TE, 8),
                List.of("stack QB with WR1", "avoid injury-prone RBs"));
        LeagueConfig saved = repository.save(validConfig().tactics(tactics).build());
        entityManager.flush();
        entityManager.clear();

        LeagueConfig reloaded = repository.findById(saved.getId()).orElseThrow();
        // Record equality asserts every field survived jsonb serialize/deserialize,
        // including the enum-keyed map and the freeform tail.
        assertThat(reloaded.getTactics()).isEqualTo(tactics);
    }

    @Test
    @DisplayName("a tactics-free config persists with tactics IS NULL")
    void tacticsFreeConfigPersistsNull() {
        LeagueConfig saved = repository.save(validConfig().tactics(null).build());
        entityManager.flush();
        entityManager.clear();

        LeagueConfig reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTactics()).isNull();
    }
}
