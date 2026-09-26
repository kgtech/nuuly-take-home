package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads through Spring Data JPA; atomic writes through the InventoryWrites fragment (D3, S1).
 * Extends the bare Repository so no save/delete methods exist (G5).
 */
interface SkuRepository extends Repository<Sku, String>, InventoryWrites {

    @Query(value = """
            SELECT (SELECT COALESCE(SUM(l.quantity_delta), 0) FROM inventory_ledger l WHERE l.sku_id = s.sku_id)::bigint
            FROM sku s WHERE s.sku_id = :skuId""", nativeQuery = true)
    Optional<Long> findQuantity(@Param("skuId") String skuId);

    @Query(value = """
            SELECT s.sku_id AS skuId, COALESCE(SUM(l.quantity_delta), 0)::bigint AS quantity
            FROM sku s LEFT JOIN inventory_ledger l ON l.sku_id = s.sku_id
            GROUP BY s.sku_id ORDER BY s.sku_id""", nativeQuery = true)
    List<SkuQuantity> findAllQuantities();

    @Query(value = """
            SELECT p.sku_id AS skuId,
                   (SELECT COALESCE(SUM(l.quantity_delta), 0) FROM inventory_ledger l WHERE l.sku_id = p.sku_id)::bigint
                       AS quantity
            FROM (SELECT s.sku_id FROM sku s WHERE s.sku_id > :after ORDER BY s.sku_id LIMIT :limit) p
            ORDER BY p.sku_id""", nativeQuery = true)
    List<SkuQuantity> findQuantitiesAfter(@Param("after") String after, @Param("limit") long limit);
}
