ALTER TABLE robot_grasp_cycle DROP CHECK ck_grasp_category;
ALTER TABLE robot_grasp_cycle DROP CHECK ck_grasp_gripper;
ALTER TABLE robot_grasp_cycle ADD CONSTRAINT ck_grasp_category CHECK (category IN ('plastic', 'aluminum', 'cardboard'));
ALTER TABLE robot_grasp_cycle ADD CONSTRAINT ck_grasp_gripper CHECK (gripper_angle IN (80, 82));
ALTER TABLE classification_operation DROP CHECK ck_classification_result;
ALTER TABLE classification_operation ADD CONSTRAINT ck_classification_result CHECK (result IN ('success', 'failure', 'unverified'));
ALTER TABLE classification_operation ADD COLUMN destination VARCHAR(160);
ALTER TABLE classification_operation ADD COLUMN deposit_confirmed BOOLEAN;
