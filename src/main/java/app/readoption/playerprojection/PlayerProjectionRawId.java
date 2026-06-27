package app.readoption.playerprojection;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PlayerProjectionRawId implements Serializable {

    private String playerId;
    private int year;
    private String source;
}