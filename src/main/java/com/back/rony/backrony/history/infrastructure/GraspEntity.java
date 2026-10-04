package com.back.rony.backrony.history.infrastructure;

import com.back.rony.backrony.monitoring.domain.Detection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "robot_grasp_cycle")
public class GraspEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "target_x", nullable = false)
    private double targetX;

    @Column(name = "target_y", nullable = false)
    private double targetY;

    @Column(name = "gripper_angle", nullable = false)
    private double gripperAngle;

    protected GraspEntity() {}

    public GraspEntity(Detection target) {
        id = UUID.randomUUID().toString();
        completedAt = Instant.now();
        category = target.category();
        confidence = target.confidence();
        targetX = target.targetMm().x();
        targetY = target.targetMm().y();
        gripperAngle = 82;
    }
}
