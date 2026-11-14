package com.awesome.warehouse.repository.fulfilment;

import com.awesome.warehouse.entity.fulfilment.WarehouseLocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseLocationRepository extends JpaRepository<WarehouseLocationEntity, String> {

    List<WarehouseLocationEntity> findAllByOrderByPriorityAscCodeAsc();
}
