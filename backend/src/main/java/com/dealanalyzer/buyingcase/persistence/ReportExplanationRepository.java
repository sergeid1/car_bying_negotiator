package com.dealanalyzer.buyingcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ReportExplanationRepository extends JpaRepository<ReportExplanationEntity, AnalysisReportKey> {
  interface Payload { String getPayloadJson(); }
  java.util.Optional<Payload> findProjectedById(AnalysisReportKey id);
}
