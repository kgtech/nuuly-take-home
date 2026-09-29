package com.kgtech.inventoryapi.inventory;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.idempotency.IdempotencyKey;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore.Keyed;
import com.kgtech.inventoryapi.idempotency.Operation;

/**
 * Stock writes: one READ COMMITTED transaction each, a conditional row update plus a ledger row (DESIGN-V2 §2); with
 * an Idempotency-Key the write runs through IdempotencyStore (A33). Reads are one row lookup (§9: no cache). The v2
 * details operations (§8) share the transaction template.
 */
@Service
public class InventoryService {

    /** R8: the largest page. */
    public static final int MAX_LIMIT = 250;
    private static final BigInteger MAX_LIMIT_BIG = BigInteger.valueOf(MAX_LIMIT);
    /** R4: ASCII digits with an optional sign; anything else is ignored. */
    private static final Pattern LIMIT = Pattern.compile("[+-]?[0-9]+");
    /** G9, C2: an absent or ignored limit means the largest page. */
    public static final int DEFAULT_LIMIT = MAX_LIMIT;
    /** Every sku_id is non-empty, so the empty cursor starts before all of them. */
    private static final String FIRST = "";

    private final StockRepository stock;
    private final DetailsRepository details;
    private final IdempotencyStore idempotency;
    private final KeyedResponses responses;
    private final TransactionTemplate transaction;

    InventoryService(StockRepository stock, DetailsRepository details, IdempotencyStore idempotency,
            KeyedResponses responses, PlatformTransactionManager transactionManager) {
        this.stock = stock;
        this.details = details;
        this.idempotency = idempotency;
        this.responses = responses;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** A malformed skuId is 400 "Invalid request" (G11). */
    public WriteResult add(String skuId, int quantity, String idempotencyKey) {
        requirePositive(quantity);
        return write(Operation.ADD, skuId, idempotencyKey, Integer.toString(quantity), new WriteResult.InvalidRequest(),
                () -> stock.add(skuId, quantity)
                        .<WriteResult>map(InventoryService::ok)
                        .orElseGet(StockOutcome.Overflow::new));
    }

    /** A malformed skuId is 404 "SKU not found": GET and purchase have no 400 for it in the spec (G11). */
    public WriteResult purchase(String skuId, int quantity, String idempotencyKey) {
        requirePositive(quantity);
        return write(Operation.PURCHASE, skuId, idempotencyKey, Integer.toString(quantity), new StockOutcome.NotFound(),
                () -> stock.purchase(skuId, quantity)
                        .<WriteResult>map(InventoryService::ok)
                        .orElseGet(() -> stock.exists(skuId) ? new StockOutcome.Insufficient()
                                : new StockOutcome.NotFound()));
    }

    /**
     * DESIGN-V2 §8 "Create": one READ COMMITTED transaction in lock order sku row → details row → ledger. The SKU
     * row's INSERT decides create (row back) or 409 (no row); initial stock goes through the spec's add path and
     * ledger row. A new row at 0 cannot overflow an int32 initial quantity. A malformed skuId is 400.
     */
    public WriteResult create(String skuId, CreateSku request, String idempotencyKey) {
        return write(Operation.CREATE, skuId, idempotencyKey, request.fingerprint(), new WriteResult.InvalidRequest(),
                () -> {
                    if (!details.claimSku(skuId)) {
                        return new DetailsOutcome.AlreadyExists();
                    }
                    long version = details.insert(skuId, request.details());
                    long quantity = 0;
                    if (request.initialQuantity() > 0) {
                        quantity = stock.add(skuId, request.initialQuantity())
                                .orElseThrow(() -> new IllegalStateException("a new SKU cannot overflow: " + skuId))
                                .quantity();
                    }
                    return new DetailsOutcome.Created(
                            new SkuItem(skuId, quantity, Optional.of(request.details()), version));
                });
    }

    /**
     * U3 after the controller's body validation: the Idempotency-Key format (S3), then the skuId (G11, S2), each once,
     * before any I/O and never stored. Without a key (G8) the write runs in the service's own READ COMMITTED
     * transaction. With one, IdempotencyStore.run claims the key, runs the write and stores its response in one
     * transaction: run opens a READ COMMITTED transaction, or joins a READ COMMITTED or DEFAULT caller's transaction
     * and refuses any other isolation (IllegalStateException); when joined, the caller's now() (T1) and rollback scope
     * apply (A33).
     */
    private WriteResult write(Operation operation, String skuId, String idempotencyKey, String canonicalRequest,
            WriteResult malformedSkuId, Supplier<WriteResult> action) {
        if (idempotencyKey == null) {
            return SkuId.isValid(skuId) ? transaction.execute(status -> action.get()) : malformedSkuId;
        }
        Optional<UUID> key = IdempotencyKey.parse(idempotencyKey);
        if (key.isEmpty()) {
            return new WriteResult.InvalidRequest();
        }
        if (!SkuId.isValid(skuId)) {
            return malformedSkuId;
        }
        return switch (idempotency.run(key.get(), operation, skuId, canonicalRequest,
                () -> responses.toStored(skuId, action.get()))) {
            case Keyed.Response response -> new WriteResult.Stored(response.response());
            case Keyed.Invalid _ -> new WriteResult.InvalidRequest();
        };
    }

    /** DESIGN-V2 §8 "Edit": full replacement in one transaction; never touches the balance row or the ledger. */
    public ReplaceResult replaceDetails(String skuId, SkuDetails replacement, DetailsPrecondition precondition) {
        if (!SkuId.isValid(skuId)) {
            return new ReplaceResult.InvalidRequest();
        }
        return transaction.execute(status -> details.replace(skuId, replacement, precondition)
                .<ReplaceResult>map(version -> new ReplaceResult.Replaced(details.find(skuId)
                        .orElseThrow(() -> new IllegalStateException("replaced details vanished: " + skuId))))
                .orElseGet(() -> switch (precondition) {
                    // Unconditional: the only way to get no row is a missing SKU; no probe, so a create that commits
                    // between the two statements can never turn it into a 412 (review R-03).
                    case DetailsPrecondition.Any _, DetailsPrecondition.Exists _ -> new ReplaceResult.NotFound();
                    case DetailsPrecondition.Versions _, DetailsPrecondition.Absent _ -> details.exists(skuId) ? new ReplaceResult.VersionMismatch()
                            : new ReplaceResult.NotFound();
                }));
    }

    /**
     * OD-6, OD-11: PUT /v2/inventory/{skuId}/details in one READ COMMITTED transaction; it never touches stock or the
     * sku row's quantity. Without a condition it creates an absent SKU at quantity 0 (claim the sku row, then insert
     * the details) or replaces the details of an existing one. Versions and Exists never create: on an absent SKU
     * they fail (RFC 9110 §13.1.1). Absent creates only, and fails on an existing SKU (§13.1.2). A malformed skuId
     * is 400 before any I/O.
     */
    public PutResult putDetails(String skuId, SkuDetails replacement, DetailsPrecondition precondition) {
        if (!SkuId.isValid(skuId)) {
            return new PutResult.InvalidRequest();
        }
        return transaction.execute(status -> switch (precondition) {
            case DetailsPrecondition.Any _ -> details.claimSku(skuId) ? created(skuId, replacement)
                    : replaced(skuId, details.replace(skuId, replacement, precondition));
            case DetailsPrecondition.Absent _ -> details.claimSku(skuId) ? created(skuId, replacement)
                    : new PutResult.PreconditionFailed();
            case DetailsPrecondition.Exists _, DetailsPrecondition.Versions _ ->
                    replaced(skuId, details.replace(skuId, replacement, precondition));
        });
    }

    private PutResult created(String skuId, SkuDetails replacement) {
        return new PutResult.Created(new SkuItem(skuId, 0, Optional.of(replacement), details.insert(skuId, replacement)));
    }

    private PutResult replaced(String skuId, Optional<Long> version) {
        return version.<PutResult>map(v -> new PutResult.Replaced(details.find(skuId)
                        .orElseThrow(() -> new IllegalStateException("replaced details vanished: " + skuId))))
                .orElseGet(PutResult.PreconditionFailed::new);
    }

    /** DESIGN-V2 §8 "Reads": G11 first, then one join. */
    public Optional<SkuItem> findSku(String skuId) {
        if (!SkuId.isValid(skuId)) {
            return Optional.empty();
        }
        return details.find(skuId);
    }

    /** The v2 list: the same page rules as {@link #list} with the details joined (§8 "Reads"). */
    public SkuPage listSkus(String limit, String after) {
        int n = parseLimit(limit);
        String cursor = truncateAtNul(after);
        List<SkuItem> items = details.page(cursor == null ? FIRST : cursor, n + 1L);
        if (items.size() <= n) {
            return new SkuPage(items, Optional.empty());
        }
        List<SkuItem> page = items.subList(0, n);
        return new SkuPage(List.copyOf(page), Optional.of(new InventoryPage.Next(n, page.getLast().skuId())));
    }

    /**
     * G11 first (no I/O for a malformed id, C-05), then one primary-key lookup at READ COMMITTED autocommit, which
     * never waits on a writer (DESIGN-V2 §9).
     */
    public Optional<InventoryItem> find(String skuId) {
        if (!SkuId.isValid(skuId)) {
            return Optional.empty();
        }
        return stock.find(skuId).map(balance -> new InventoryItem(skuId, balance.quantity()));
    }

    /** One page of SKUs in sku_id (COLLATE "C") order, at most 250 (G9, R4, C2). */
    public InventoryPage list(String limit, String after) {
        int n = parseLimit(limit);
        String cursor = truncateAtNul(after);
        List<InventoryItem> items = stock.page(cursor == null ? FIRST : cursor, n + 1L);
        if (items.size() <= n) {
            return new InventoryPage(items, Optional.empty());
        }
        List<InventoryItem> page = items.subList(0, n);
        return new InventoryPage(List.copyOf(page), Optional.of(new InventoryPage.Next(n, page.getLast().skuId())));
    }

    private static WriteResult ok(Balance balance) {
        return new StockOutcome.Ok(balance.quantity());
    }

    /** R4, R8, C2: a positive ASCII integer, clamped to 250; blank, non-numeric, zero or negative → 250. */
    private static int parseLimit(String raw) {
        if (raw == null || !LIMIT.matcher(raw).matches()) {
            return DEFAULT_LIMIT;
        }
        BigInteger value = new BigInteger(raw);
        if (value.signum() <= 0) {
            return DEFAULT_LIMIT;
        }
        return value.min(MAX_LIMIT_BIG).intValueExact();
    }

    /** R4: after is never validated. Postgres text cannot hold NUL, so the cursor is cut there. */
    private static String truncateAtNul(String after) {
        if (after == null) {
            return null;
        }
        int nul = after.indexOf('\0');
        return nul < 0 ? after : after.substring(0, nul);
    }

    private static void requirePositive(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
    }
}
