import { hydrate, search } from '../../web/search.js';
import raw from '../generated/index.txt';
import { handleRpc } from './mcp.js';

// 在全局作用域解析和预热：这里有 1 秒启动预算，而每次请求只有 10ms CPU
const db = hydrate(JSON.parse(raw));
for (let i = 0; i < 30; i++) search(db, { keyword: '大模型 go 字节', limit: 20 });

const MAX_BODY = 64 * 1024;

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Accept, Mcp-Protocol-Version, Mcp-Session-Id',
};

const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'X-Robots-Tag': 'noindex', ...CORS, ...headers },
});

async function mcp(request, env) {
  if (request.method !== 'POST') {
    // 这里没有服务端推送，按规范 GET 返回 405
    return json({ error: 'method not allowed' }, 405, { Allow: 'POST, OPTIONS' });
  }
  if (env.LIMITER) {
    const { success } = await env.LIMITER.limit({ key: request.headers.get('CF-Connecting-IP') || 'unknown' });
    if (!success) return json({ error: 'rate limit exceeded' }, 429, { 'Retry-After': '60' });
  }
  if (Number(request.headers.get('Content-Length') || 0) > MAX_BODY) return json({ error: 'request too large' }, 413);
  const body = await request.text();
  if (body.length > MAX_BODY) return json({ error: 'request too large' }, 413);
  const { status, json: out } = handleRpc(db, body);
  return out === undefined ? new Response(null, { status, headers: CORS }) : json(out, status);
}

export default {
  fetch(request, env) {
    const { pathname } = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (pathname === '/mcp') return mcp(request, env);
    if (pathname === '/') {
      return json({ name: 'campus-radar', mcp: '/mcp', updatedAt: db.generatedAt, openJobs: db.jobs.length, repo: 'https://github.com/Ajax-1/campus-radar' });
    }
    return json({ error: 'not found' }, 404);
  },
};
