package app.readoption.player;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Service
public class PlayerIdMappingService {

    private static final Logger log = LoggerFactory.getLogger(PlayerIdMappingService.class);

    private final PlayerRepository playerRepository;
    private final Resource mappingFile;

    public PlayerIdMappingService(PlayerRepository playerRepository,
                                  @Value("classpath:playerids/db_playerids.csv") Resource mappingFile) {
        this.playerRepository = playerRepository;
        this.mappingFile = mappingFile;
    }

    /**
     * Enriches existing player rows with their ESPN id from the DynastyProcess
     * player-id map. Only players already present in our DB are updated; map
     * rows for unknown players are skipped. Sleeper's own espn_id is null for
     * ~44% of players, so this map — not Sleeper — is the source of truth here.
     */
    @Transactional
    public PlayerIdMappingResult syncEspnIds() {
        if (!mappingFile.exists()) {
            throw new IllegalStateException(
                    "Player id mapping file not found on classpath: " + mappingFile.getDescription());
        }

        Set<String> existingPlayerIds = new HashSet<>(playerRepository.findAllIds());

        int rowsParsed = 0;
        int matched = 0;
        int updated = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = new InputStreamReader(mappingFile.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            for (CSVRecord record : parser) {
                rowsParsed++;

                String sleeperId = record.get("sleeper_id");
                String espnId = record.get("espn_id");

                if (isMissing(sleeperId) || isMissing(espnId)) {
                    continue;                                 // map row missing one side of the join
                }
                if (!existingPlayerIds.contains(sleeperId)) {
                    continue;                                 // player not in our DB; nothing to enrich
                }

                matched++;
                if (playerRepository.updateEspnId(sleeperId, espnId) > 0) {
                    updated++;
                }
            }

        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading player id mapping file", e);
        }

        log.info("Player id mapping: parsed {} rows, matched {} existing players, updated espn_id on {}",
                rowsParsed, matched, updated);

        return new PlayerIdMappingResult(rowsParsed, matched, updated);
    }

    /** DynastyProcess writes the literal string {@code NA} for missing values. */
    private static boolean isMissing(String s) {
        return s == null || s.isBlank() || "NA".equals(s);
    }

    public record PlayerIdMappingResult(int rowsParsed, int matched, int updated) {}
}