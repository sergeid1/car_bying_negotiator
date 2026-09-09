package com.dealanalyzer.buyingcase.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
interface ExportJobRepository extends JpaRepository<ExportJobEntity, String> {
  java.util.Optional<ExportJobEntity> findByCaseIdAndRequestKey(String caseId, String requestKey);
}
