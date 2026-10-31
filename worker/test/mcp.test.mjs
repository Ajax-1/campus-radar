import assert from 'node:assert/strict';
import { test } from 'node:test';
import { db, NOW } from '../../web/test/fixture.mjs';
import { handleRpc } from '../src/mcp.js';

const rpc = (method, params, id = 1) => handleRpc(db(), JSON.stringify({ jsonrpc: '2.0', id, method, params }), NOW);
const call = (name, args) => {
  const { result } = rpc('tools/call', { name, arguments: args }).json;
  return { ...result, data: result.isError ? null : JSON.parse(result.content[0].text) };
};

test('initialize 协商协议版本', () => {
  const r = rpc('initialize', { protocolVersion: '2025-03-26' }).json.result;
  assert.equal(r.protocolVersion, '2025-03-26');
  assert.ok(r.capabilities.tools);
  assert.equal(rpc('initialize', { protocolVersion: '1999-01-01' }).json.result.protocolVersion, '2025-06-18');
});

test('只有三个只读工具', () => {
  const names = rpc('tools/list').json.result.tools.map((t) => t.name);
  assert.deepEqual(names, ['search_jobs', 'get_job', 'list_sources']);
});

test('search_jobs 支持公司简称，不暴露内部字段', () => {
  const { data } = call('search_jobs', { company: '字节', keyword: 'Java' });
  assert.equal(data.total, 1);
  assert.equal(data.jobs[0].company, '字节跳动');
  assert.ok(!('lower' in data.jobs[0]));
});

test('limit 有上限', () => {
  assert.equal(call('search_jobs', { limit: 9999 }).data.jobs.length, 5);
  assert.equal(call('search_jobs', { limit: -1 }).data.jobs.length, 1);
});

test('没收录的公司告诉 Agent 有哪些', () => {
  const r = call('search_jobs', { company: '不存在' });
  assert.equal(r.isError, true);
  assert.match(r.content[0].text, /已收录：.*字节跳动/);
});

test('get_job 指向官网，没有 JD 全文', () => {
  const { data } = call('get_job', { id: 'bytedance-1' });
  assert.equal(data.applyUrl, 'https://example.com/1');
  assert.ok(data.tags.includes('Java'));
  assert.match(data.note, /不含 JD 全文/);
  assert.equal(call('get_job', { id: 'nope' }).isError, true);
});

test('list_sources', () => {
  const { data } = call('list_sources', {});
  assert.equal(data.updatedAt, '2026-10-30T00:00:00Z');
  assert.deepEqual(data.sources.map((s) => s.company).sort(), ['字节跳动', '腾讯', '阿里巴巴'].sort());
});

test('协议错误', () => {
  assert.equal(rpc('nope').json.error.code, -32601);
  assert.equal(rpc('tools/call', { name: 'record_application' }).json.error.code, -32602);
  assert.equal(rpc('tools/call', { name: 'constructor' }).json.error.code, -32602);
  assert.equal(handleRpc(db(), '{bad').status, 400);
  assert.equal(handleRpc(db(), 'null').json.error.code, -32600);
});

test('通知没有响应体', () => {
  const r = handleRpc(db(), '{"jsonrpc":"2.0","method":"notifications/initialized"}');
  assert.equal(r.status, 202);
  assert.equal(r.json, undefined);
});

test('批量请求', () => {
  const body = '[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/x"}]';
  assert.deepEqual(handleRpc(db(), body).json, [{ jsonrpc: '2.0', id: 1, result: {} }]);
});
