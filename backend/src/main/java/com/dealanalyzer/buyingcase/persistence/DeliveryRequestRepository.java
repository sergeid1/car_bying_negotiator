package com.dealanalyzer.buyingcase.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
interface DeliveryRequestRepository extends JpaRepository<DeliveryRequestEntity, String> {
  java.util.Optional<DeliveryRequestEntity> findByCaseIdAndRequestKey(String caseId, String requestKey);
}
