package com.kgtech.inventoryapi.inventory;

/**
 * Business results of a stock write; never thrown (R1, U1). Not a WriteResult: a write returns WriteResult<Add> or
 * WriteResult<Purchase>, which wraps one (A38).
 */
public sealed interface StockOutcome {

    sealed interface Add extends StockOutcome permits Ok, Overflow {
    }

    sealed interface Purchase extends StockOutcome permits Ok, NotFound, Insufficient {
    }

    record Ok(long quantity) implements Add, Purchase {
    }

    record NotFound() implements Purchase {
    }

    record Insufficient() implements Purchase {
    }

    record Overflow() implements Add {
    }
}
