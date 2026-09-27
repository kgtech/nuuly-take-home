import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api, type ApiResult, type InventoryItem, type ListParams } from '../api/client';
import { skuHref } from '../hooks/useHashRoute';
import { ErrorText, Loading } from '../components/Messages';

type PageRequest = { n: number; pageNo: number; params: ListParams; run: () => Promise<ApiResult<InventoryItem[]>> };
type PageResult = { n: number; result: ApiResult<InventoryItem[]> };

function firstPage(params: ListParams, n: number): PageRequest {
  return { n, pageNo: 1, params, run: () => api.listInventory(params) };
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
    <section aria-labelledby={`${id}-h`}>
      <h1 id={`${id}-h`} tabIndex={-1} ref={headingRef}>
        Inventory
      </h1>
      <form onSubmit={apply} className="paging-form" aria-label="Paging" noValidate>
        <div className="field">
          <label htmlFor={`${id}-limit`}>Page size (1–250)</label>
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
          <label htmlFor={`${id}-after`}>After SKU ID</label>
          <input
            id={`${id}-after`}
            type="text"
            value={afterInput}
            onChange={(e) => setAfterInput(e.target.value)}
            autoComplete="off"
          />
        </div>
        <button type="submit">Apply</button>
      </form>

      {loading && <Loading what="inventory" />}
      {result && !result.ok && (
        <>
          <ErrorText text={result.errorText} />
          <button type="button" onClick={retry}>
            Retry
          </button>
        </>
      )}
      {result?.ok && result.data.length === 0 && (
        <p className="muted">
          No SKUs yet. <a href="#/add">Add stock</a> to create one.
        </p>
      )}
      {result?.ok && result.data.length > 0 && (
        <>
          <p className="muted">
            Page {request.pageNo}: {result.data.length} SKU{result.data.length === 1 ? '' : 's'}
          </p>
          <table>
            <thead>
              <tr>
                <th scope="col">SKU</th>
                <th scope="col" className="num">
                  Quantity
                </th>
              </tr>
            </thead>
            <tbody>
              {result.data.map((item) => (
                <tr key={item.skuId}>
                  <td>
                    <a href={skuHref(item.skuId ?? '')}>{item.skuId}</a>
                  </td>
                  <td className="num">{item.quantity}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {result.next !== null && (
            <button type="button" onClick={() => nextPage(result.next ?? '')}>
              Next page
            </button>
          )}
        </>
      )}
    </section>
  );
}
