package com.back.rony.backrony.history.infrastructure;

import com.back.rony.backrony.history.domain.Operation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "classification_operation")
public class ClassificationEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(nullable = false, length = 160)
    private String residue;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false, length = 16)
    private String result;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "source_mode", nullable = false, length = 16)
    private String sourceMode;

    protected ClassificationEntity() {}

    public ClassificationEntity(Operation o, String source) {
        id = o.id();
        completedAt = o.completedAt();
        residue = o.residue();
        category = o.category();
        confidence = o.confidence();
        result = o.result();
        durationMs = o.durationMs();
        sourceMode = source;
    }

    public Operation toDomain() {
        return new Operation(id, completedAt, residue, category, confidence, result, durationMs);
    }
}
