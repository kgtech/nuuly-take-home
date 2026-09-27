package com.kgtech.inventoryapi.inventory;

/** Business results of a stock write; never thrown (R1, U1). */
public sealed interface StockOutcome extends WriteResult {

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
