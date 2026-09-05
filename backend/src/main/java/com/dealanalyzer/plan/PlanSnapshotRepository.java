package com.dealanalyzer.plan;

import java.util.Optional;
import org.springframework.data.repository.Repository;

interface PlanSnapshotRepository extends Repository<PlanSnapshotEntity, String> {
  PlanSnapshotEntity save(PlanSnapshotEntity entity);
  Optional<PlanSnapshotEntity> findByCaseIdAndKindAndIdempotencyKey(String caseId, String kind, String key);
  Optional<PlanSnapshotEntity> findTopByCaseIdAndKindOrderByVersionDesc(String caseId, String kind);
  Optional<PlanSnapshotEntity> findByCaseIdAndKindAndVersion(String caseId, String kind, int version);
}
