// 岗位检索和打分，网页和 Worker 共用同一份，结果才会一致。
// 规则和 Terms、JobQueryService、JobMatcher 保持一致；公开索引没有 JD 全文，JD 相关的判断改用技能标签。

const SYNONYMS = {
  go: ['golang', 'go语言'],
  golang: ['go', 'go语言'],
  javascript: ['js'],
  js: ['javascript'],
  typescript: ['ts'],
  k8s: ['kubernetes'],
  kubernetes: ['k8s'],
  'c++': ['cpp'],
  cpp: ['c++'],
  postgresql: ['postgres', 'pg'],
  llm: ['大模型'],
  大模型: ['llm'],
  机器学习: ['machine learning'],
  深度学习: ['deep learning'],
};

export const RECRUIT_TYPES = ['校招', '实习'];
export const MAX_LIMIT = 50;

export function variants(term) {
  const t = term.trim().toLowerCase();
  return [...new Set([t, ...(SYNONYMS[t] || [])])];
}

const isWordChar = (c) => (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');

function containsOne(text, term, ascii = [...term].some(isWordChar)) {
  if (!ascii) return text.includes(term);
  for (let at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + 1)) {
    const end = at + term.length;
    const leftOk = at === 0 || !isWordChar(text[at - 1]) || !isWordChar(term[0]);
    const rightOk = end === text.length || !isWordChar(text[end]) || !isWordChar(term[term.length - 1]);
    if (leftOk && rightOk) return true;
  }
  return false;
}

// 英文按词匹配，java 不会命中 javascript，go 不会命中 google
export function contains(lowerText, term) {
  if (!lowerText || !term || !term.trim()) return false;
  return variants(term).some((v) => containsOne(lowerText, v));
}

const stripCitySuffix = (city) => (city.endsWith('市') && city.length > 2 ? city.slice(0, -1) : city);

/**
 * 把索引展开成方便检索的对象。第一次启动时做一次，之后每次查询只扫描小写好的字段
 */
export function hydrate(index) {
  const companies = index.companies.map((c) => ({
    ...c,
    keys: [c.company, ...(c.aliases || [])].map((a) => a.toLowerCase()),
  }));
  const jobs = index.jobs.map(([id, company, title, category, recruitType, gradYear, cities, applyUrl, publishedAt, tags]) => {
    const c = companies[company];
    const tagNames = tags.map((t) => index.tags[t]);
    return {
      id,
      source: c.source,
      company: c.company,
      title,
      category,
      recruitType: RECRUIT_TYPES[recruitType],
      gradYear,
      cities,
      applyUrl,
      publishedAt: publishedAt == null ? null : new Date(publishedAt * 86400000).toISOString().slice(0, 10),
      publishedDay: publishedAt,
      tags: tagNames,
      lower: {
        title: title.toLowerCase(),
        category: (category || '').toLowerCase(),
        tags: tagNames.join(' ').toLowerCase(),
        company: c.keys.join(' '),
        cities: cities.join(' ').toLowerCase(),
      },
    };
  });
  jobs.forEach((j) => { j.lower.all = Object.values(j.lower).join('\n'); });
  // 排好一次，之后过滤结果天然按发布时间倒序
  jobs.sort(byNewest);
  return {
    version: index.version,
    generatedAt: index.generatedAt,
    mcpUrl: index.mcpUrl || null,
    tags: index.tags,
    companies,
    jobs,
    byId: new Map(jobs.map((j) => [j.id, j])),
  };
}

/**
 * 公司简称解析成标准公司名：先精确匹配，再看是否只包含在一家公司的叫法里
 */
export function resolveCompany(db, input) {
  const key = (input || '').trim().toLowerCase().replace(/\s+/g, '');
  if (!key) return null;
  const exact = db.companies.find((c) => c.keys.some((k) => k.replace(/\s+/g, '') === key));
  if (exact) return exact.company;
  const eligible = (s) => ([...s].every((ch) => ch.charCodeAt(0) < 128) ? s.length >= 4 : s.length >= 2);
  if (!eligible(key)) return null;
  const fuzzy = db.companies.filter((c) => c.keys.some((k) => eligible(k) && (k.includes(key) || key.includes(k))));
  return fuzzy.length === 1 ? fuzzy[0].company : null;
}

const words = (keyword) => [...new Set((keyword || '').trim().toLowerCase().split(/\s+/).filter(Boolean))];

// 没指定公司时按公司轮流穿插，避免岗位最多的公司刷屏
function interleave(ranked) {
  const groups = new Map();
  for (const j of ranked) {
    if (!groups.has(j.company)) groups.set(j.company, []);
    groups.get(j.company).push(j);
  }
  const queues = [...groups.values()];
  const out = [];
  for (let i = 0; out.length < ranked.length; i++) {
    for (const q of queues) if (i < q.length) out.push(q[i]);
  }
  return out;
}

// 查询时每个词预先展开，一次查询要扫一万多个岗位，这里省下的时间决定 Worker 会不会超 CPU 限额
const compile = (word) => variants(word).map((v) => [v, [...v].some(isWordChar)]);

function hit(text, compiled) {
  for (const [v, ascii] of compiled) if (containsOne(text, v, ascii)) return true;
  return false;
}

// 每个词：标题 5 分，职能类别 2 分，技能标签 1 分，公司和城市 1 分。一个都没命中返回 -1
function keywordScore(j, list) {
  let total = 0;
  for (const w of list) {
    if (!hit(j.lower.all, w)) return -1;
    total += (hit(j.lower.title, w) ? 5 : 0) + (hit(j.lower.category, w) ? 2 : 0) + (hit(j.lower.tags, w) ? 1 : 0)
      + (hit(j.lower.company, w) || hit(j.lower.cities, w) ? 1 : 0);
  }
  return total;
}

function byNewest(a, b) {
  return (b.publishedDay ?? -1) - (a.publishedDay ?? -1);
}

/**
 * @returns {{total: number, jobs: object[]}} total 是过滤后的总数，jobs 按 offset 和 limit 截取
 */
export function search(db, q = {}, now = Date.now()) {
  const list = words(q.keyword).map(compile);
  const city = q.city ? stripCitySuffix(q.city.trim()) : null;
  const fromDay = q.sinceDays > 0 ? Math.floor(now / 86400000) - q.sinceDays : null;
  const scores = new Map();
  const hits = db.jobs.filter((j) => {
    if (q.company && j.company !== q.company) return false;
    if (city && !j.cities.includes(city)) return false;
    if (q.recruitType && j.recruitType !== q.recruitType) return false;
    if (q.gradYear && j.gradYear !== q.gradYear) return false;
    if (fromDay != null && (j.publishedDay == null || j.publishedDay < fromDay)) return false;
    if (!list.length) return true;
    const s = keywordScore(j, list);
    if (s < 0) return false;
    scores.set(j, s);
    return true;
  });
  if (list.length) hits.sort((a, b) => scores.get(b) - scores.get(a));
  const ranked = q.company ? hits : interleave(hits);
  const offset = q.offset > 0 ? q.offset : 0;
  return { total: ranked.length, jobs: ranked.slice(offset, offset + (q.limit > 0 ? q.limit : 20)) };
}

function freshness(j, nowDay) {
  if (j.publishedDay == null) return null;
  const days = nowDay - j.publishedDay;
  if (days <= 3) return { score: 10, reason: days <= 0 ? '今天发布' : days + ' 天前发布' };
  if (days <= 7) return { score: 6, reason: days + ' 天前发布' };
  return days <= 14 ? { score: 3 } : null;
}

// 理由里用用户原来的写法，而不是小写后的匹配词
const original = (values, hits) => (values || []).filter((v) => hits.includes(v.trim().toLowerCase())).map((v) => v.trim());

function scoreJob(j, p, roles, skills, nowDay) {
  const reasons = [];
  let score = 0;

  const inTitle = roles.filter((r) => contains(j.lower.title, r));
  const inCategory = roles.filter((r) => contains(j.lower.category, r));
  const inTags = roles.filter((r) => contains(j.lower.tags, r));
  let roleScore = 0;
  if (inTitle.length) {
    roleScore = Math.min(40, 30 + 5 * (inTitle.length - 1));
    reasons.push('标题命中：' + original(p.targetRoles, inTitle).join('、'));
  } else if (inCategory.length) {
    roleScore = 20;
    reasons.push('职能类别命中：' + original(p.targetRoles, inCategory).join('、'));
  } else if (inTags.length) {
    roleScore = 10;
    reasons.push('JD 提到：' + original(p.targetRoles, inTags).join('、'));
  }
  score += roleScore;

  const skillHits = skills.filter((s) => contains(j.lower.title, s) || contains(j.lower.tags, s));
  if (skillHits.length) {
    score += Math.round((30 * skillHits.length) / skills.length);
    reasons.push('技能 ' + skillHits.length + '/' + skills.length + '：' + original(p.skills, skillHits).join('、'));
  }
  if (!roleScore && !skillHits.length) return null;

  const cities = p.cities || [];
  if (cities.length) {
    if (!j.cities.length) {
      score += 5;
    } else if (j.cities.includes(cities[0])) {
      score += 10;
      reasons.push('城市：' + cities[0]);
    } else {
      score += 7;
      const other = cities.find((c) => j.cities.includes(c));
      if (other) reasons.push('城市：' + other);
    }
  }

  if ((p.preferredCompanies || []).includes(j.company)) {
    score += 10;
    reasons.push('偏好公司');
  }

  const fresh = freshness(j, nowDay);
  if (fresh) {
    score += fresh.score;
    if (fresh.reason) reasons.push(fresh.reason);
  }
  return { job: j, score: Math.min(score, 100), reasons };
}

/**
 * 按档案打分。硬过滤和本地版相同：招聘类型、届别（没标届别的保留）、排除公司、标题排除词、
 * 意向城市（没写城市的保留）、已在投递记录里的岗位。
 */
export function match(db, profile, { limit = 30, maxPerCompany = 4, skip = new Set(), now = Date.now() } = {}) {
  const lower = (list) => [...new Set((list || []).map((v) => v.trim().toLowerCase()).filter(Boolean))];
  const roles = lower(profile.targetRoles);
  const skills = lower(profile.skills);
  if (!roles.length && !skills.length) return [];
  const cities = profile.cities || [];
  const types = profile.recruitTypes || [];
  const excluded = profile.excludedCompanies || [];
  const keywords = lower(profile.excludeKeywords);
  const nowDay = Math.floor(now / 86400000);
  const scored = [];
  for (const j of db.jobs) {
    if (skip.has(j.id)) continue;
    if (types.length && !types.includes(j.recruitType)) continue;
    if (profile.gradYear && j.gradYear != null && j.gradYear !== profile.gradYear) continue;
    if (excluded.includes(j.company)) continue;
    if (cities.length && j.cities.length && !cities.some((c) => j.cities.includes(c))) continue;
    if (keywords.some((k) => contains(j.lower.title, k))) continue;
    const r = scoreJob(j, profile, roles, skills, nowDay);
    if (r) scored.push(r);
  }
  scored.sort((a, b) => b.score - a.score);
  const perCompany = new Map();
  const out = [];
  for (const r of scored) {
    if (out.length >= limit) break;
    const n = (perCompany.get(r.job.company) || 0) + 1;
    perCompany.set(r.job.company, n);
    if (!maxPerCompany || n <= maxPerCompany) out.push(r);
  }
  return out;
}
