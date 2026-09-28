import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api, type ApiResult, type InventoryItem, type ListParams } from '../api/client';
import { skuHref } from '../hooks/useHashRoute';
import { ErrorText, Loading } from '../components/Messages';
import { FindSku } from '../components/FindSku';
import { ArrowRight } from '../components/Icons';
import { BRAND } from '../brand';

type PageRequest = { n: number; pageNo: number; params: ListParams; run: () => Promise<ApiResult<InventoryItem[]>> };
type PageResult = { n: number; result: ApiResult<InventoryItem[]> };

function firstPage(params: ListParams, n: number): PageRequest {
  return { n, pageNo: 1, params, run: () => api.listInventory(params) };
}

/** Badge thresholds from the design (FE27): 0 is rented out, 1–3 almost gone, above that available. */
export const LOW_STOCK = 3;
export function availability(quantity: number): { text: string; tone: 'out' | 'low' | 'available' } {
  if (quantity <= 0) return { text: 'Rented out', tone: 'out' };
  if (quantity <= LOW_STOCK) return { text: 'Almost gone', tone: 'low' };
  return { text: 'Available', tone: 'available' };
}

export function InventoryList() {
  const id = useId();
  const [limitInput, setLimitInput] = useState('');
  const [afterInput, setAfterInput] = useState('');
  const [request, setRequest] = useState<PageRequest>(() => firstPage({}, 0));
  const [response, setResponse] = useState<PageResult | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    let live = true;
    void request.run().then((result) => {
      if (live) setResponse({ n: request.n, result });
    });
    return () => {
      live = false;
    };
  }, [request]);

  const apply = (e: FormEvent) => {
    e.preventDefault();
    const params: ListParams = {};
    if (limitInput.trim() !== '') params.limit = Number(limitInput);
    if (afterInput !== '') params.after = afterInput;
    setRequest((r) => firstPage(params, r.n + 1));
  };

  const retry = () => setRequest((r) => ({ ...r, n: r.n + 1 }));

  const nextPage = (url: string) => {
    setRequest((r) => ({ n: r.n + 1, pageNo: r.pageNo + 1, params: r.params, run: () => api.listInventoryAt(url) }));
    headingRef.current?.focus();
  };

  const loading = response === null || response.n !== request.n;
  const result = loading ? null : response.result;

  return (
    <section aria-labelledby={`${id}-h`} className="page">
      <div className="page-head">
        <h1 id={`${id}-h`} tabIndex={-1} ref={headingRef}>
          {BRAND}
        </h1>
        <p className="subtitle">Every SKU, sorted by ID. Open one to add stock or record a purchase.</p>
      </div>

      <div className="cards">
        <FindSku />
        <section className="card" aria-labelledby={`${id}-paging`}>
          <h2 id={`${id}-paging`}>Page size + start</h2>
          <form onSubmit={apply} className="paging-form" aria-label="Paging" noValidate>
            <div className="field">
              <label htmlFor={`${id}-limit`}>Per page (1–250)</label>
              <input
                id={`${id}-limit`}
                type="number"
                inputMode="numeric"
                min={1}
                max={250}
                value={limitInput}
                onChange={(e) => setLimitInput(e.target.value)}
                placeholder="250"
              />
            </div>
            <div className="field">
              <label htmlFor={`${id}-after`}>After SKU</label>
              <input
                id={`${id}-after`}
                type="text"
                value={afterInput}
                onChange={(e) => setAfterInput(e.target.value)}
                autoComplete="off"
              />
            </div>
            <button type="submit" className="secondary">
              Apply
            </button>
          </form>
        </section>
      </div>

      {loading && <Loading what="inventory" />}
      {result && !result.ok && (
        <div className="stack">
          <ErrorText text={result.errorText} />
          <button type="button" className="secondary" onClick={retry}>
            Retry
          </button>
        </div>
      )}
      {result?.ok && result.data.length === 0 && (
        <div className="empty">
          <h3>The closet is empty</h3>
          <p>
            No SKUs yet. <a href="#/add">Add stock</a> to create one.
          </p>
        </div>
      )}
      {result?.ok && result.data.length > 0 && (
        <div className="stack">
          <div className="list-head" style={{ alignSelf: 'stretch' }}>
            <span>
              Page {request.pageNo} · {result.data.length} SKU{result.data.length === 1 ? '' : 's'}
            </span>
            <span className="right">Sorted by SKU ID</span>
          </div>
          <div className="table-wrap" style={{ alignSelf: 'stretch' }}>
            <table>
              <thead>
                <tr>
                  <th scope="col">SKU</th>
                  <th scope="col">Availability</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                </tr>
              </thead>
              <tbody>
                {result.data.map((item) => {
                  const badge = availability(item.quantity ?? 0);
                  return (
                    <tr key={item.skuId}>
                      <td>
                        <a href={skuHref(item.skuId ?? '')} className="row-link">
                          {item.skuId}
                        </a>
                      </td>
                      <td>
                        <span className={`badge ${badge.tone}`}>{badge.text}</span>
                      </td>
                      <td className="num">{(item.quantity ?? 0).toLocaleString()}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {result.next !== null && (
            <button type="button" onClick={() => nextPage(result.next ?? '')}>
              Next page
              <ArrowRight />
            </button>
          )}
        </div>
      )}
    </section>
  );
}
