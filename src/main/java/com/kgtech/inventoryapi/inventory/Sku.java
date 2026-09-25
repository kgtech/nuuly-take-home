package com.kgtech.inventoryapi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A SKU row; stock lives in inventory_ledger (V1). */
@Entity
@Table(name = "sku")
class Sku {

    @Id
    @Column(name = "sku_id", length = 64)
    private String skuId;

    protected Sku() {
    }

    String getSkuId() {
        return skuId;
    }
}
