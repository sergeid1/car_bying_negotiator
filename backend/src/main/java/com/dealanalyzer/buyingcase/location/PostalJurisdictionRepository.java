package com.dealanalyzer.buyingcase.location;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PostalJurisdictionRepository
    extends JpaRepository<PostalJurisdictionEntity, PostalJurisdictionEntity.Key> {
  List<PostalJurisdictionEntity> findByPostalCodeOrderByDominantDesc(String postalCode);
}
