package app.readoption.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DynastyProcess writes the literal string {@code NA} for missing values; the
 * 4.4 seed sync surfaced 113 player rows polluted with {@code espn_id = 'NA'}
 * because the sync guarded only against blank. These tests pin the repair:
 * {@code NA} on either side of the join is missing — the row is skipped, never
 * matched, and never written.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerIdMappingService — DynastyProcess 'NA' marker treated as missing")
class PlayerIdMappingServiceTest {

    @Mock private PlayerRepository playerRepository;

    private PlayerIdMappingService serviceFor(String csv) {
        return new PlayerIdMappingService(playerRepository,
                new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("espn_id 'NA' is skipped as missing — never matched, never written")
    void naEspnIdIsSkipped() {
        // 13354 = Eric McAlister, the real 4.4 finding: CSV row exists, espn_id is 'NA'
        when(playerRepository.findAllIds()).thenReturn(List.of("13354", "4046"));
        when(playerRepository.updateEspnId("4046", "3139477")).thenReturn(1);

        String csv = """
                sleeper_id,espn_id
                13354,NA
                4046,3139477
                """;

        PlayerIdMappingService.PlayerIdMappingResult result = serviceFor(csv).syncEspnIds();

        assertThat(result.rowsParsed()).isEqualTo(2);
        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        verify(playerRepository, never()).updateEspnId(eq("13354"), anyString());
        verify(playerRepository, never()).updateEspnId(anyString(), eq("NA"));
    }

    @Test
    @DisplayName("sleeper_id 'NA' is skipped as missing, same as blank")
    void naSleeperIdIsSkipped() {
        when(playerRepository.findAllIds()).thenReturn(List.of("4046"));

        String csv = """
                sleeper_id,espn_id
                NA,4430841
                ,4605951
                4046,
                """;

        PlayerIdMappingService.PlayerIdMappingResult result = serviceFor(csv).syncEspnIds();

        assertThat(result.rowsParsed()).isEqualTo(3);
        assertThat(result.matched()).isZero();
        assertThat(result.updated()).isZero();
        verify(playerRepository, never()).updateEspnId(anyString(), anyString());
    }
}
