package app.readoption.playerscoring;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PlayerScoringId implements Serializable {

    private String playerId;
    private int year;
    private String scoringFormat;
}
