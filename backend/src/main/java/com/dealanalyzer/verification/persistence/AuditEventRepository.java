package com.dealanalyzer.verification.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEventRepository extends JpaRepository<AuditEventEntity, String> {}
