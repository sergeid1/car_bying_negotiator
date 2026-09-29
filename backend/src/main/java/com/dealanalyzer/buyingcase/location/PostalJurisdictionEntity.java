package com.dealanalyzer.buyingcase.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** Census-derived reference data. Says where a postal code is, never whether it is supported. */
@Entity
@Table(name = "postal_jurisdictions")
@IdClass(PostalJurisdictionEntity.Key.class)
class PostalJurisdictionEntity {
  @Id @Column(name = "postal_code", nullable = false, length = 5) String postalCode;
  @Id @Column(nullable = false, length = 64) String jurisdiction;
  @Column(nullable = false) boolean dominant;
  @Column(name = "dataset_version", nullable = false, length = 64) String datasetVersion;

  protected PostalJurisdictionEntity() {}

  record Key(String postalCode, String jurisdiction) implements Serializable {
    Key() {
      this(null, null);
    }

    @Override public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(postalCode, key.postalCode)
          && Objects.equals(jurisdiction, key.jurisdiction);
    }
  }
}
