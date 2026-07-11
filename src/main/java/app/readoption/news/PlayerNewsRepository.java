package app.readoption.news;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PlayerNewsRepository extends JpaRepository<PlayerNews, PlayerNewsId> {

    /**
     * The writer's dedup read: which of these ids have already landed — one query
     * per batch, never an {@code existsById} per row.
     */
    @Query("SELECT n.newsId FROM PlayerNews n WHERE n.source = :source AND n.newsId IN :newsIds")
    List<String> findExistingNewsIds(@Param("source") String source,
                                     @Param("newsIds") Collection<String> newsIds);
}
