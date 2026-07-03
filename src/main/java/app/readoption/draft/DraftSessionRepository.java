package app.readoption.draft;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftSessionRepository extends JpaRepository<DraftSession, Long> {
}
