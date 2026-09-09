package com.dealanalyzer.buyingcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface NegotiationVersionRepository extends JpaRepository<NegotiationVersionEntity, AnalysisReportKey> {
  interface Payload { String getPayloadJson(); }
  java.util.Optional<Payload> findProjectedById(AnalysisReportKey id);
}
