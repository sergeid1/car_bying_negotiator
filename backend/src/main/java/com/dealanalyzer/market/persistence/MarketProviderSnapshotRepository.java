package com.dealanalyzer.market.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface MarketProviderSnapshotRepository
    extends JpaRepository<MarketProviderSnapshotEntity, String> {}
