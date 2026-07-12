package app.readoption.news;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * The landing key is the ASSOCIATION, not the item: one ESPN item legitimately
 * appears in several players' feeds (trades, signings name both players), so
 * {@code playerId} is part of the identity (Phase 4.4.1, review R-1).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PlayerNewsId implements Serializable {

    private String source;
    private String newsId;
    private String playerId;
}
