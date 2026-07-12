package app.readoption.news;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PlayerNewsRepository extends JpaRepository<PlayerNews, PlayerNewsId> {

    /**
     * The writer's dedup read: which associations have already landed for these
     * candidate items — one query per batch, never an {@code existsById} per row.
     * Returns full {@code (source, newsId, playerId)} ids (V17, review R-1): the
     * same item under a DIFFERENT player is a new fact, not a duplicate, so the
     * writer must compare on the triple.
     */
    @Query("""
            SELECT new app.readoption.news.PlayerNewsId(n.source, n.newsId, n.playerId)
            FROM PlayerNews n
            WHERE n.source = :source AND n.newsId IN :newsIds
            """)
    List<PlayerNewsId> findExistingIds(@Param("source") String source,
                                       @Param("newsIds") Collection<String> newsIds);
}
