package com.dealanalyzer.pilot;

import org.springframework.data.jpa.repository.JpaRepository;

interface PilotEventRepository extends JpaRepository<PilotEventEntity, String> {}
