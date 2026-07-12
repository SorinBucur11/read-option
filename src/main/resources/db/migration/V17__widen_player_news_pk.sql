-- Phase 4.4.1: the landing PK must carry the player association.
-- One ESPN item legitimately appears in several players' feeds (trades,
-- signings); (source, news_id) collapsed those associations (review R-1).
ALTER TABLE player_news DROP CONSTRAINT player_news_pkey;
ALTER TABLE player_news ADD PRIMARY KEY (source, news_id, player_id);
