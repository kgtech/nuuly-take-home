/**
 * Idempotency-Key support for the inventory feature's writes, the spec POSTs (D10, A33, A37): claim, replay and reject
 * in the write's transaction. Not a generic facility: the operations, the skuId and the request hash it stores are
 * the inventory feature's (S8, Y3), though it imports no inventory type.
 */
package com.kgtech.inventoryapi.idempotency;
