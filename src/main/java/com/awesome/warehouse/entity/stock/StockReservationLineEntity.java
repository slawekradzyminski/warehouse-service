package com.awesome.warehouse.entity.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One reserved product with the quantity and ledger version the deduction left. */
@Entity
@Table(name = "stock_reservation_lines")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservationLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Eager: the native image cannot create lazy to-one proxies (NativeEntityMappingTest).
    @ManyToOne(optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private StockReservationEntity reservation;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "quantity_after", nullable = false)
    private int quantityAfter;

    @Column(name = "ledger_version", nullable = false)
    private long ledgerVersion;
}
