import { buildIndex } from '../../scripts/build-index.mjs';
import { hydrate } from '../search.js';

export const NOW = Date.parse('2026-10-30T12:00:00Z');

const job = (source, externalId, title, extra = {}) => ({
  source,
  externalId,
  title,
  category: null,
  recruitType: 'CAMPUS',
  gradYear: 2027,
  cities: ['北京'],
  description: null,
  requirement: null,
  applyUrl: 'https://example.com/' + externalId,
  publishedAt: null,
  ...extra,
});

export const SNAPSHOT = {
  version: 1,
  generatedAt: '2026-10-30T00:00:00Z',
  jobs: [
    job('bytedance', '1', '后端开发工程师', { category: '研发', requirement: '熟悉 Java、Redis 和分布式系统。这段 JD 原文不能进入公开索引', publishedAt: '2026-10-29T00:00:00Z' }),
    job('bytedance', '2', '前端开发工程师', { category: '研发', requirement: '熟悉 JavaScript 和 React', cities: ['上海'], publishedAt: '2026-10-01T00:00:00Z' }),
    job('tencent', '3', '大模型算法工程师', { category: '技术', requirement: '熟悉 PyTorch，了解 LLM', gradYear: null, cities: [] }),
    job('alibaba', '4', '后端开发实习生', { recruitType: 'INTERN', requirement: 'golang，go-to-market', publishedAt: '2026-10-28T00:00:00Z' }),
    job('alibaba', '5', 'Google Ads 运营', { category: '运营', requirement: '负责 go-to-market', cities: ['杭州'] }),
  ],
};

export const db = () => hydrate(JSON.parse(JSON.stringify(buildIndex(SNAPSHOT, 'https://example.com/mcp'))));
