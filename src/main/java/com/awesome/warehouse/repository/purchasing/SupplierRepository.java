package com.awesome.warehouse.repository.purchasing;

import com.awesome.warehouse.entity.purchasing.SupplierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<SupplierEntity, Long> {

    boolean existsByNameIgnoreCase(String name);
}
