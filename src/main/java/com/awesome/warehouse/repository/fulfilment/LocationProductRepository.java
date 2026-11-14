package com.awesome.warehouse.repository.fulfilment;

import com.awesome.warehouse.entity.fulfilment.LocationProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationProductRepository extends JpaRepository<LocationProductEntity, Long> {

    List<LocationProductEntity> findAllByOrderByLocationCodeAscCategoryAsc();

    boolean existsByLocationCodeAndCategory(String locationCode, String category);
}
