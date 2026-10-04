package com.back.rony.backrony.history.infrastructure;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClassificationJpaRepository extends JpaRepository<ClassificationEntity, String> {
    List<ClassificationEntity> findTop100ByOrderByCompletedAtDesc();

    long countByCompletedAtGreaterThanEqual(Instant since);

    long countByResult(String result);

    @Query("select coalesce(avg(o.durationMs), 0) from ClassificationEntity o")
    double averageDuration();
}
