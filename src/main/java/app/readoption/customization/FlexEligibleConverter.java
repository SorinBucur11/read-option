package app.readoption.customization;

import app.readoption.scoring.Position;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps {@code Set<Position>} to a sorted CSV of enum names ({@code "RB,TE,WR"}) for
 * the {@code league_config.flex_eligible} varchar column. Sorted so the stored value
 * is deterministic regardless of set iteration order. Enum <i>names</i>, never
 * ordinals — same rule as {@code @Enumerated(EnumType.STRING)}.
 */
@Converter
public class FlexEligibleConverter implements AttributeConverter<Set<Position>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Position> attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    @Override
    public Set<Position> convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (dbData.isBlank()) {
            return EnumSet.noneOf(Position.class);
        }
        return Arrays.stream(dbData.split(","))
                .map(Position::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Position.class)));
    }
}
