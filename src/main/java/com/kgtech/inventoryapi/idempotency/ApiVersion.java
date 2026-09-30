package com.kgtech.inventoryapi.idempotency;

/**
 * The API a keyed request came through. It is part of the request hash (H10), so a key stored by one version never
 * replays on the other: the two answer with different bodies.
 */
public enum ApiVersion {
    UNVERSIONED,
    V2
}
