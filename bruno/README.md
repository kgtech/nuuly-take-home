# Bruno collection: Nuuly Inventory final

A [Bruno](https://www.usebruno.com) collection for the final Inventory API: the unversioned spec API (`/inventory`) and the `/v2` API. It is in Bruno's OpenCollection YAML format (`opencollection.yml`, one `folder.yml` per folder, one `.yml` file per request), the same format as the earlier `Nuuly Inventory v1` and `v2` collections. The collection is `Nuuly Inventory final/`; every request has tests (status, Content-Type and, for errors, the exact `text/plain` body) and a short `docs` note naming the decision or README section it shows.

| Folder | What it shows |
| --- | --- |
| Health | liveness, readiness and the two OpenAPI groups (`/v3/api-docs.yaml/inventory`, `/v3/api-docs.yaml/inventory-v2`) |
| Unversioned (spec) | the spec walk: add without a key (200), item, list, purchase, insufficient (400), missing (404), an `Idempotency-Key` refused (OD-4), `limit` ignored and `after`-only lists (OD-5), repeated `after` (400), and one invalid skuId, bad body and non-JSON Accept |
| v2 keyed writes | a fresh UUID key, replay, same key with another quantity (400), missing and malformed key (400), keyed purchase and its replay, insufficient, missing SKU, invalid skuId |
| v2 details | `PUT .../details` create with `If-None-Match: *` (201, ETag `"1"`), again (412), replace with `If-Match` (200, ETag `"2"`), stale tag (412), `If-Match` on an absent SKU (412), GET item (ETag, `Cache-Control: no-store`), list with details |
| v2 paging | seeds four SKUs with keyed adds, then walks them one per page by following the `Link` header (the last page has none), an ignored `limit`, repeated `after` (400) |

The first request of each folder picks fresh SKU IDs (`BR-<time>`) and keys for the run and saves them with `bru.setVar`, so the collection can run again and again against the same database, and every folder also runs alone on an empty database. Requests that follow rejected writes re-read the SKU and check that nothing changed.

## Prerequisite: the service

```bash
docker compose up --build        # from the repo root; app on http://localhost:8080
```

## In the Bruno app

1. Bruno, Open Collection, choose `bruno/Nuuly Inventory final`. (To keep it beside other collections, copy or symlink the folder into your collections directory, e.g. `~/Documents/bruno`.)
2. Select the `Local` environment (top right). Its `baseUrl` is `http://localhost:8080`.
3. Run the whole collection with the Runner, or right-click a folder and Run.

## Headless (Bruno CLI, verified with 4.2.0)

```bash
cd "bruno/Nuuly Inventory final"
npx --yes @usebruno/cli@latest run --env Local -r
```

The exit code is non-zero when a test fails, and every request fails when the service is down. Nothing is added to the repo's manifests; `npx` runs the CLI from its cache.

## Another port

`APP_PORT=18400 docker compose up --build` publishes the app on 18400. Point the collection at it either way:

- CLI: `npx --yes @usebruno/cli@latest run --env Local --env-var baseUrl=http://localhost:18400 -r`
- App: edit `baseUrl` in the `Local` environment.
