package com.dealanalyzer.buyingcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnalysisReportRepository
    extends JpaRepository<AnalysisReportEntity, AnalysisReportKey> {
  interface Payload { String getPayloadJson(); }
  java.util.Optional<Payload> findProjectedById(AnalysisReportKey id);
}
