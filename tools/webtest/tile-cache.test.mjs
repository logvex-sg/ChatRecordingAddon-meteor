/* Exercises the shipped tile cache out of app.html against a fake fetch.
 *
 * The bug this guards: fetchTile cached a tile forever (options.refresh was never passed by any
 * caller), while the client re-reads chunks every 30 s. A tile therefore kept its first picture for
 * the life of the page — the "map gets stuck" report. The test drives the real function, not a copy.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, '../../src/main/resources/companion/app.html'), 'utf8');
const script = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1])
  .sort((a, b) => b.length - a.length)[0];

// Slice out the cache block and give it the few names it needs.
const start = script.indexOf('const tileCache = new Map();');
const end = script.indexOf('// ---------------------------------------------------------------- item icons');
if (start < 0 || end < 0) throw new Error('could not locate the tile cache block');

let clock = 1_000_000;
let fetches = 0;
let payload = 'v1';

const fakeFetch = async () => {
  fetches++;
  const body = new TextEncoder().encode(payload);
  return {
    ok: true,
    status: 200,
    arrayBuffer: async () => body.buffer
  };
};

const build = new Function('fetch', 'AbortController', 'setTimeout', 'clearTimeout',
  'Date', 'TextEncoder', 'Uint8Array',
  'const inflateTile = async b => b;\n' +
  script.slice(start, end) +
  '\nreturn { fetchTile, tileCache, TILE_TTL_MS };');

const api = build(fakeFetch, AbortController, () => 0, () => {},
  { now: () => clock }, TextEncoder, Uint8Array);

const node = { id: 'n1', url: 'http://x', token: 't' };
const checks = [];
const check = (name, cond, detail = '') => {
  checks.push({ name, ok: !!cond, detail });
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name + (detail ? '  [' + detail + ']' : ''));
};

// 1. First read fetches.
const first = await api.fetchTile(node, 'minecraft:overworld', 0, 0);
check('first read fetches the tile', fetches === 1 && first !== null);
check('first read returns the payload', new TextDecoder().decode(first) === 'v1');

// 2. A second read inside the window is served from cache.
const second = await api.fetchTile(node, 'minecraft:overworld', 0, 0);
check('a read inside the TTL does not refetch', fetches === 1, 'fetches=' + fetches);

// 3. Past the TTL it refetches, and picks up new contents.
clock += api.TILE_TTL_MS + 1;
payload = 'v2';
const third = await api.fetchTile(node, 'minecraft:overworld', 0, 0);
check('a read past the TTL refetches', fetches === 2, 'fetches=' + fetches);
check('the refetched tile carries the new contents',
  new TextDecoder().decode(third) === 'v2');

// 4. An explicit refresh always refetches, whatever the age.
payload = 'v3';
const forced = await api.fetchTile(node, 'minecraft:overworld', 0, 0, { refresh: true });
check('an explicit refresh bypasses the cache', fetches === 3);
check('an explicit refresh returns fresh contents',
  new TextDecoder().decode(forced) === 'v3');

// 5. Two different nodes must not share a cache entry.
payload = 'other';
await api.fetchTile({ id: 'n2', url: 'http://y', token: 't' }, 'minecraft:overworld', 0, 0);
check('a different node does not reuse another node\'s tile', fetches === 4);

// 6. A tile whose advertised revision changed is refetched at once, without waiting out the TTL.
//    This is the case that made the map sit one refresh window behind: the signature changed, so the
//    grid was rebuilt, but fetchTile handed back the cached bytes for up to 30 s more.
clock += 10;
payload = 'rev2';
const bumped = await api.fetchTile(node, 'minecraft:overworld', 0, 0, { rev: 200 });
check('a changed revision refetches inside the TTL', fetches === 5, 'fetches=' + fetches);
check('the bumped tile carries the new contents',
  new TextDecoder().decode(bumped) === 'rev2');

// 7. The same revision is served from cache again, so a revision does not defeat caching.
clock += 10;
await api.fetchTile(node, 'minecraft:overworld', 0, 0, { rev: 200 });
check('an unchanged revision still uses the cache', fetches === 5, 'fetches=' + fetches);

// 8. A client that sends no revision falls back to the TTL, never refetching on every refresh.
clock += 10;
payload = 'norev';
await api.fetchTile(node, 'minecraft:overworld', 0, 0, {});
check('no revision keeps the TTL behaviour', fetches === 5, 'fetches=' + fetches);

const failed = checks.filter(c => !c.ok);
console.log('\n' + (checks.length - failed.length) + '/' + checks.length + ' checks passed');
process.exit(failed.length === 0 ? 0 : 1);
