USE rony_local;
SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT * FROM classification_operation ORDER BY completed_at DESC LIMIT 100;
-- Completed grasp plans do not confirm object presence or completed classification.
SELECT * FROM robot_grasp_cycle ORDER BY completed_at DESC LIMIT 100;
