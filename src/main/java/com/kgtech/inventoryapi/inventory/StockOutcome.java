package com.kgtech.inventoryapi.inventory;

import java.util.Optional;

/** Business results of a stock write; never thrown (R1, U1). */
public sealed interface StockOutcome extends WriteResult {

    sealed interface Add extends StockOutcome permits Ok, Overflow {
    }

    sealed interface Purchase extends StockOutcome permits Ok, NotFound, Insufficient {
    }

    /**
     * The write went through: the new quantity, and, on /v2, the SKU's details read in the same transaction (empty on
     * the spec path and for a SKU without details).
     */
    record Ok(long quantity, Optional<SkuDetails> details) implements Add, Purchase {

        public Ok(long quantity) {
            this(quantity, Optional.empty());
        }
    }

    record NotFound() implements Purchase {
    }

    record Insufficient() implements Purchase {
    }

    record Overflow() implements Add {
    }
}
