package com.dealanalyzer.mandate;

import com.dealanalyzer.mandate.MandateEntities.MandateEntity;
import com.dealanalyzer.mandate.MandateEntities.RevocationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface MandateRepository extends Repository<MandateEntity, String> {
  MandateEntity save(MandateEntity entity);
  Optional<MandateEntity> findByCaseIdAndIdempotencyKey(String caseId, String key);
  Optional<MandateEntity> findByCaseIdAndVersion(String caseId, int version);
  Optional<MandateEntity> findTopByCaseIdOrderByVersionDesc(String caseId);
  List<MandateEntity> findByCaseIdOrderByVersionDesc(String caseId);

  /** The current version of each case's mandate, unrevoked, with expiry inside the window. */
  @Query("""
      select m from MandateEntity m
      where m.expiresAt > :after and m.expiresAt <= :before
        and m.version = (select max(o.version) from MandateEntity o where o.caseId = m.caseId)
        and not exists (select r from RevocationEntity r where r.mandateId = m.id)
      order by m.expiresAt, m.id
      """)
  List<MandateEntity> currentExpiringBetween(@Param("after") Instant after, @Param("before") Instant before);
}

interface RevocationRepository extends Repository<RevocationEntity, String> {
  RevocationEntity save(RevocationEntity entity);
  Optional<RevocationEntity> findById(String mandateId);
  List<RevocationEntity> findByCaseId(String caseId);
}
