package com.dealanalyzer.pilot;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface PilotProtocolRepository extends JpaRepository<PilotProtocolEntity, Integer> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PilotProtocolEntity p where p.id = 1")
  PilotProtocolEntity lock();
}
