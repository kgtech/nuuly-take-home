/**
 * Redis caches (DESIGN-V2 §1, §3): a copy of the most-read stock counts and of completed idempotent responses.
 * Nothing here is authoritative; every call fails soft and the caller falls back to Postgres.
 */
package com.kgtech.inventoryapi.cache;
