package app.readoption.playerscoring;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static app.readoption.TestFixtures.player;
import static app.readoption.TestFixtures.projection;
import static app.readoption.TestFixtures.scoring;
import static app.readoption.scoring.ScoringFormat.STANDARD_6PT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("PlayerScoringRepository.findRankedLeaderboard — window functions on real Postgres")
class PlayerScoringRankedRepositoryTest extends AbstractPostgresTest {

    private static final String STD = "STANDARD";              // adpBucket for STANDARD_6PT
    private static final String FMT = STANDARD_6PT.name();      // "STANDARD_6PT"

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerScoringRepository playerScoringRepository;
    @Autowired private PlayerProjectionRepository playerProjectionRepository;

    @BeforeEach
    void seed() {
        // positions live on Player; the rank query reads them via the join
        playerRepository.saveAll(List.of(
                player("qbA", "QB A", "QB"),
                player("qbB", "QB B", "QB"),
                player("rbA", "RB A", "RB"),
                player("rbB", "RB B", "RB"),
                player("rbC", "RB C", "RB")));

        playerScoringRepository.saveAll(List.of(
                scoring("qbA", 2026, STANDARD_6PT, "400.00"),
                scoring("qbB", 2026, STANDARD_6PT, "380.00"),
                scoring("rbA", 2026, STANDARD_6PT, "300.00"),
                scoring("rbB", 2026, STANDARD_6PT, "300.00"),   // tie with rbA on points
                scoring("rbC", 2026, STANDARD_6PT, "200.00")));

        // ADP via the std column; rbC gets NO projection row -> null ADP via LEFT JOIN
        playerProjectionRepository.saveAll(List.of(
                projection("qbA", 2026, new BigDecimal("10.0"), null),
                projection("qbB", 2026, new BigDecimal("20.0"), null),
                projection("rbA", 2026, new BigDecimal("5.0"),  null),
                projection("rbB", 2026, new BigDecimal("15.0"), null)));
        // rbC intentionally absent from projections
    }

    private Map<String, RankedLeaderboardRow> fetchByPlayer(String position) {
        Page<RankedLeaderboardRow> page = playerScoringRepository.findRankedLeaderboard(
                2026, FMT, STD, position, null, PageRequest.of(0, 50));
        return page.getContent().stream()
                .collect(Collectors.toMap(RankedLeaderboardRow::getPlayerId, Function.identity()));
    }

    @Test
    @DisplayName("value ranks order by points desc, RANK skips after a tie, partition resets per position")
    void valueRanks() {
        Map<String, RankedLeaderboardRow> r = fetchByPlayer(null);

        // overall: 400=1, 380=2, 300&300 tie=3, then 200=5 (RANK skips, not 4)
        assertThat(r.get("qbA").getValueRankOverall()).isEqualTo(1);
        assertThat(r.get("qbB").getValueRankOverall()).isEqualTo(2);
        assertThat(r.get("rbA").getValueRankOverall()).isEqualTo(3);
        assertThat(r.get("rbB").getValueRankOverall()).isEqualTo(3);   // tied
        assertThat(r.get("rbC").getValueRankOverall()).isEqualTo(5);   // skip after tie

        // positional: QB partition 1,2 ; RB partition 1,1(tie),3(skip)
        assertThat(r.get("qbA").getValueRankPosition()).isEqualTo(1);
        assertThat(r.get("qbB").getValueRankPosition()).isEqualTo(2);
        assertThat(r.get("rbA").getValueRankPosition()).isEqualTo(1);
        assertThat(r.get("rbB").getValueRankPosition()).isEqualTo(1);   // tied within RB
        assertThat(r.get("rbC").getValueRankPosition()).isEqualTo(3);   // skip after tie
    }

    @Test
    @DisplayName("market ranks order by ADP asc; undrafted player (no projection) gets null market rank but a real value rank")
    void marketRanksAndNullAdp() {
        Map<String, RankedLeaderboardRow> r = fetchByPlayer(null);

        // overall market by ADP asc: rbA(5)=1, qbA(10)=2, rbB(15)=3, qbB(20)=4
        assertThat(r.get("rbA").getMarketRankOverall()).isEqualTo(1);
        assertThat(r.get("qbA").getMarketRankOverall()).isEqualTo(2);
        assertThat(r.get("rbB").getMarketRankOverall()).isEqualTo(3);
        assertThat(r.get("qbB").getMarketRankOverall()).isEqualTo(4);

        // the undrafted player: null market ranks, null adp, but a real value rank
        assertThat(r.get("rbC").getMarketRankOverall()).isNull();
        assertThat(r.get("rbC").getMarketRankPosition()).isNull();
        assertThat(r.get("rbC").getAdp()).isNull();
        assertThat(r.get("rbC").getValueRankOverall()).isEqualTo(5);    // value rank still computed
    }

    @Test
    @DisplayName("ranks are computed over the full field, then the view is filtered: overall rank survives a position filter")
    void rankThenFilter() {
        Map<String, RankedLeaderboardRow> rbOnly = fetchByPlayer("RB");

        // filtered to RB, but valueRankOverall reflects standing among ALL players:
        // rbA is overall 3 (behind the two QBs), NOT 1
        assertThat(rbOnly).containsOnlyKeys("rbA", "rbB", "rbC");
        assertThat(rbOnly.get("rbA").getValueRankOverall()).isEqualTo(3);
        assertThat(rbOnly.get("rbB").getValueRankOverall()).isEqualTo(3);
        assertThat(rbOnly.get("rbC").getValueRankOverall()).isEqualTo(5);

        // positional rank is still per-position
        assertThat(rbOnly.get("rbA").getValueRankPosition()).isEqualTo(1);
        assertThat(rbOnly.get("rbC").getValueRankPosition()).isEqualTo(3);
    }
}