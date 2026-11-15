package com.awesome.warehouse.repository.stock;

import com.awesome.warehouse.entity.stock.StockRestoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRestoreRepository extends JpaRepository<StockRestoreEntity, Long> {
}
