import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildIndex, skillTags } from '../../scripts/build-index.mjs';
import { contains, match, resolveCompany, search } from '../search.js';
import { NOW, SNAPSHOT, db } from './fixture.mjs';

const ids = (r) => r.jobs.map((j) => j.id);

test('公开索引不含 JD 原文', () => {
  const json = JSON.stringify(buildIndex(SNAPSHOT));
  assert.ok(!json.includes('这段 JD 原文'));
  assert.ok(!json.includes('description'));
  assert.ok(!json.includes('requirement'));
});

test('构建时遇到没登记的公司要失败', () => {
  const jobs = [{ ...SNAPSHOT.jobs[0], source: 'unknown' }];
  assert.throws(() => buildIndex({ ...SNAPSHOT, jobs }), /缺少：unknown/);
});

test('技能标签按词匹配', () => {
  assert.deepEqual(skillTags('熟悉 javascript'), ['JavaScript']);
  assert.deepEqual(skillTags('golang'), ['Go']);
  assert.deepEqual(skillTags('负责 go-to-market'), []);
  assert.deepEqual(skillTags('google'), []);
  assert.deepEqual(skillTags('c++/k8s'), ['C++', 'Kubernetes']);
});

test('英文按词匹配，同义写法互认', () => {
  assert.ok(contains('熟悉java开发', 'Java'));
  assert.ok(!contains('javascript', 'java'));
  assert.ok(!contains('google', 'go'));
  assert.ok(contains('golang', 'go'));
  assert.ok(contains('大模型', 'LLM'));
});

test('JD 里的技能也能搜到', () => {
  assert.deepEqual(ids(search(db(), { keyword: 'Java' })), ['bytedance-1']);
  assert.deepEqual(ids(search(db(), { keyword: 'PyTorch' })), ['tencent-3']);
});

test('多个词同时命中，可以夹公司和城市', () => {
  assert.deepEqual(ids(search(db(), { keyword: '字节 后端' })), ['bytedance-1']);
  assert.deepEqual(ids(search(db(), { keyword: '上海 前端' })), ['bytedance-2']);
  assert.deepEqual(ids(search(db(), { keyword: '后端 深圳' })), []);
});

test('标题命中排在标签命中前面', () => {
  assert.equal(search(db(), { keyword: '后端' }).jobs[0].title.includes('后端'), true);
});

test('各公司轮流穿插', () => {
  const companies = search(db(), {}).jobs.slice(0, 3).map((j) => j.company);
  assert.equal(new Set(companies).size, 3);
});

test('筛选条件', () => {
  const d = db();
  assert.deepEqual(ids(search(d, { recruitType: '实习' })), ['alibaba-4']);
  assert.deepEqual(ids(search(d, { city: '杭州市' })), ['alibaba-5']);
  assert.equal(search(d, { gradYear: 2027 }).total, 4);
  assert.deepEqual(ids(search(d, { sinceDays: 3 }, NOW)).sort(), ['alibaba-4', 'bytedance-1']);
  assert.deepEqual(ids(search(d, { company: '阿里巴巴', limit: 1, offset: 1 })).length, 1);
});

test('公司简称解析', () => {
  const d = db();
  assert.equal(resolveCompany(d, '字节'), '字节跳动');
  assert.equal(resolveCompany(d, 'ByteDance'), '字节跳动');
  assert.equal(resolveCompany(d, '阿里巴巴集团'), '阿里巴巴');
  assert.equal(resolveCompany(d, '不存在的公司'), null);
});

test('打分理由和硬过滤', () => {
  const d = db();
  const [top] = match(d, { targetRoles: ['后端'], skills: ['Java'], cities: ['北京'], gradYear: 2027 }, { now: NOW });
  assert.equal(top.job.id, 'bytedance-1');
  assert.equal(top.score, 30 + 30 + 10 + 10);
  assert.deepEqual(top.reasons, ['标题命中：后端', '技能 1/1：Java', '城市：北京', '1 天前发布']);

  const skip = new Set(['bytedance-1']);
  assert.ok(!match(d, { targetRoles: ['后端'] }, { skip, now: NOW }).some((r) => r.job.id === 'bytedance-1'));
  assert.deepEqual(match(d, { targetRoles: ['后端'], recruitTypes: ['实习'] }, { now: NOW }).map((r) => r.job.id), ['alibaba-4']);
  assert.deepEqual(match(d, { targetRoles: ['后端'], excludeKeywords: ['实习'], excludedCompanies: ['字节跳动'] }, { now: NOW }), []);
  assert.deepEqual(match(d, {}), []);
});
