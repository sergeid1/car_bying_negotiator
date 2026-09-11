package com.dealanalyzer.pilot;

import org.springframework.data.jpa.repository.JpaRepository;

interface PilotObservationRepository extends JpaRepository<PilotObservationEntity, String> {}
