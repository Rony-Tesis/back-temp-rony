package com.back.rony.backrony.history.infrastructure;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClassificationJpaRepository extends JpaRepository<ClassificationEntity, String> {
    List<ClassificationEntity> findTop100ByOrderByCompletedAtDesc();

    long countBySourceModeAndResult(String sourceMode, String result);

    interface Totals {
        long getProcessedToday();

        long getTotal();

        long getSuccessful();

        long getFailed();

        double getAverageDurationMs();

        long getUnverified();
    }

    @Query(
            value =
                    """
            SELECT COALESCE(SUM(CASE WHEN completed_at >= :since THEN 1 ELSE 0 END), 0) AS processedToday,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END), 0) AS successful,
                   COALESCE(SUM(CASE WHEN result = 'failure' THEN 1 ELSE 0 END), 0) AS failed,
                   COALESCE(AVG(duration_ms), 0) AS averageDurationMs,
                   COALESCE(SUM(CASE WHEN result = 'unverified' THEN 1 ELSE 0 END), 0) AS unverified
            FROM classification_operation
            """,
            nativeQuery = true)
    Totals totalsSince(@org.springframework.data.repository.query.Param("since") Instant since);
}
