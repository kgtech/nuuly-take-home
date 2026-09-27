/** Returns the URL of the rel="next" entry of an RFC 8288 Link header, or null. */
export function nextLink(header: string | null): string | null {
  if (!header) return null;
  for (const part of header.split(',')) {
    const m = /^\s*<([^>]*)>\s*((?:;[^;]*)*)$/.exec(part);
    if (!m || m[1] === undefined) continue;
    const params = (m[2] ?? '').split(';').map((p) => p.trim().toLowerCase());
    if (params.some((p) => p === 'rel=next' || p === 'rel="next"')) return m[1];
  }
  return null;
}
