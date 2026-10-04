package com.back.rony.backrony.history.infrastructure;

import com.back.rony.backrony.history.application.port.HistoryPort;
import com.back.rony.backrony.history.domain.Operation;
import com.back.rony.backrony.monitoring.domain.Detection;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaHistoryAdapter implements HistoryPort {
    private final ClassificationJpaRepository operations;
    private final GraspJpaRepository grasps;

    public JpaHistoryAdapter(ClassificationJpaRepository operations, GraspJpaRepository grasps) {
        this.operations = operations;
        this.grasps = grasps;
    }

    @Override
    @Transactional
    public void saveOperation(Operation operation, String mode) {
        operations.save(new ClassificationEntity(operation, mode));
    }

    @Override
    public List<Operation> recentOperations() {
        return operations.findTop100ByOrderByCompletedAtDesc().stream()
                .map(ClassificationEntity::toDomain)
                .toList();
    }

    @Override
    public Summary summary(Instant since) {
        return new Summary(
                operations.countByCompletedAtGreaterThanEqual(since),
                operations.count(),
                operations.countByResult("success"),
                operations.countByResult("failure"),
                operations.averageDuration());
    }

    @Override
    public long countGrasps() {
        return grasps.count();
    }

    @Override
    @Transactional
    public void saveGrasp(Detection target) {
        grasps.save(new GraspEntity(target));
    }
}
