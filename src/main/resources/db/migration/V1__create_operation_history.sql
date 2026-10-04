CREATE TABLE classification_operation (
    id VARCHAR(36) PRIMARY KEY,
    completed_at TIMESTAMP(3) NOT NULL,
    residue VARCHAR(160) NOT NULL,
    category VARCHAR(16) NOT NULL,
    confidence DOUBLE NOT NULL,
    result VARCHAR(16) NOT NULL,
    duration_ms BIGINT NOT NULL,
    source_mode VARCHAR(16) NOT NULL,
    CONSTRAINT ck_classification_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_classification_category CHECK (category IN ('plastic', 'cardboard', 'aluminum')),
    CONSTRAINT ck_classification_result CHECK (result IN ('success', 'failure')),
    CONSTRAINT ck_classification_source CHECK (source_mode IN ('simulation', 'live')),
    CONSTRAINT ck_classification_duration CHECK (duration_ms >= 0)
);
CREATE INDEX ix_classification_completed_at ON classification_operation(completed_at);
CREATE TABLE robot_grasp_cycle (
    id VARCHAR(36) PRIMARY KEY,
    completed_at TIMESTAMP(3) NOT NULL,
    category VARCHAR(16) NOT NULL,
    confidence DOUBLE NOT NULL,
    target_x DOUBLE NOT NULL,
    target_y DOUBLE NOT NULL,
    gripper_angle DOUBLE NOT NULL,
    CONSTRAINT ck_grasp_confidence CHECK (confidence BETWEEN 0.8 AND 1),
    CONSTRAINT ck_grasp_category CHECK (category = 'plastic'),
    CONSTRAINT ck_grasp_gripper CHECK (gripper_angle = 82)
);
CREATE INDEX ix_grasp_completed_at ON robot_grasp_cycle(completed_at);
