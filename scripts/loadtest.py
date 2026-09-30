"""Concurrency load test against a running app (stdlib only). Usage: python3 scripts/loadtest.py [port]

Four scenarios, each ending with a correctness check against the API: scarcity (exactly M of N purchases win),
hot-SKU adds (no lost updates), a mixed read/add/purchase run over 500 SKUs (every balance matches the successful
writes), and one Idempotency-Key from many threads on /v2 (stock moves once). Exits 1 if any check fails.
"""
import http.client, json, sys, threading, time, uuid, random, collections

HOST, PORT = "localhost", int(sys.argv[1]) if len(sys.argv) > 1 else 18090
RUN = uuid.uuid4().hex[:6]


def call(conn, method, path, body=None, headers=None):
    h = {"Content-Type": "application/json", "Accept": "application/json"}
    h.update(headers or {})
    conn.request(method, path, json.dumps(body) if body is not None else None, h)
    r = conn.getresponse()
    data = r.read().decode()
    return r.status, data


def run(threads, ops_per_thread, work):
    """work(conn, thread_idx, op_idx) -> status. Returns (statuses Counter, latencies, wall)."""
    lat, stat = [], collections.Counter()
    lock = threading.Lock()

    def worker(t):
        conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
        l, s = [], collections.Counter()
        for i in range(ops_per_thread):
            t0 = time.perf_counter()
            try:
                st = work(conn, t, i)
            except Exception as e:
                st = type(e).__name__
                conn.close()
                conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
            l.append(time.perf_counter() - t0)
            s[st] += 1
        with lock:
            lat.extend(l)
            stat.update(s)

    ts = [threading.Thread(target=worker, args=(t,)) for t in range(threads)]
    w0 = time.perf_counter()
    [t.start() for t in ts]
    [t.join() for t in ts]
    return stat, sorted(lat), time.perf_counter() - w0


def report(name, threads, stat, lat, wall):
    n = len(lat)
    p = lambda q: lat[min(n - 1, int(n * q))] * 1000
    print(f"\n== {name}: {threads} threads, {n} reqs in {wall:.2f}s = {n / wall:.0f} req/s")
    print(f"   latency ms p50={p(.5):.1f} p95={p(.95):.1f} p99={p(.99):.1f} max={lat[-1] * 1000:.1f}")
    print(f"   statuses {dict(stat)}")


def qty(sku):
    c = http.client.HTTPConnection(HOST, PORT)
    st, d = call(c, "GET", f"/inventory/{sku}")
    return json.loads(d)["quantity"] if st == 200 else None


ok = True


def check(label, cond):
    global ok
    ok &= bool(cond)
    print(f"   {'PASS' if cond else 'FAIL'}: {label}")


# 1. Scarcity: 500 units, 1000 single-unit purchase attempts from 100 threads.
sku = f"scarce-{RUN}"
call(http.client.HTTPConnection(HOST, PORT), "POST", f"/inventory/{sku}", {"quantity": 500})
st, lat, wall = run(100, 10, lambda c, t, i: call(c, "POST", f"/inventory/{sku}/purchase", {"quantity": 1})[0])
report("scarcity (500 units, 1000 attempts)", 100, st, lat, wall)
check("exactly 500 succeed", st[200] == 500)
check("exactly 500 rejected 400", st[400] == 500)
check("final quantity 0", qty(sku) == 0)
check("no 5xx / errors", sum(v for k, v in st.items() if k not in (200, 400)) == 0)

# 2. Hot-SKU adds: every request contends for one row.
sku = f"hot-{RUN}"
call(http.client.HTTPConnection(HOST, PORT), "POST", f"/inventory/{sku}", {"quantity": 1})
T, N = 64, 50
st, lat, wall = run(T, N, lambda c, t, i: call(c, "POST", f"/inventory/{sku}", {"quantity": 1})[0])
report("hot-SKU adds", T, st, lat, wall)
check("no lost updates: quantity == 1 + successes", qty(sku) == 1 + st[200])
check("all succeeded", st[200] == T * N)

# 3. Spread across 500 SKUs: mixed add/purchase/get; track expected balance per SKU.
S = 500
skus = [f"mix-{RUN}-{i}" for i in range(S)]
c0 = http.client.HTTPConnection(HOST, PORT)
for s in skus:
    call(c0, "POST", f"/inventory/{s}", {"quantity": 100})
lock = threading.Lock()
delta = collections.Counter()


def mixed(c, t, i):
    s = random.choice(skus)
    r = random.random()
    if r < .5:
        return call(c, "GET", f"/inventory/{s}")[0]
    q = random.randint(1, 5)
    if r < .75:
        stt = call(c, "POST", f"/inventory/{s}", {"quantity": q})[0]
        if stt == 200:
            with lock: delta[s] += q
    else:
        stt = call(c, "POST", f"/inventory/{s}/purchase", {"quantity": q})[0]
        if stt == 200:
            with lock: delta[s] -= q
    return stt


st, lat, wall = run(64, 300, mixed)
report("mixed 50% read / 25% add / 25% purchase over 500 SKUs", 64, st, lat, wall)
bad = [s for s in skus if qty(s) != 100 + delta[s]]
check("every SKU balance equals seed + successful adds - successful purchases", not bad)
check("no negative balances", all(qty(s) >= 0 for s in skus))
check("no 5xx / errors", sum(v for k, v in st.items() if k not in (200, 400)) == 0)

# 4. Idempotency: 32 threads replay the same key; stock must move once.
sku = f"idem-{RUN}"
call(c0, "POST", f"/inventory/{sku}", {"quantity": 10})
key = str(uuid.uuid4())
st, lat, wall = run(32, 5, lambda c, t, i: call(c, "POST", f"/v2/inventory/{sku}/purchase", {"quantity": 3}, {"Idempotency-Key": key})[0])
report("same Idempotency-Key from 32 threads x5", 32, st, lat, wall)
check("all 160 replies 200", st[200] == 160)
check("stock moved once (10 - 3 = 7)", qty(sku) == 7)

print("\nOVERALL:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
