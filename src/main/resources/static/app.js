'use strict';
// 岗位标题和 JD 来自外部网站，这个页面又能读到个人档案，所以一律用文本节点渲染，不拼 HTML。

const REPO = 'https://github.com/Ajax-1/campus-radar';
const CITIES = ['北京', '上海', '深圳', '杭州', '广州', '成都', '南京', '武汉', '西安', '苏州'];
const STAGES = ['想投', '已投递', '笔试/测评', '面试中', 'Offer', '未通过', '已放弃'];

const state = { status: null, tracked: new Map(), jobs: { keyword: '', company: '', city: '', recruitType: '', sinceDays: '' } };
const $ = (id) => document.getElementById(id);

function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid == null || kid === false) continue;
    el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

// replaceChildren 会把 null 显示成文字，条件内容先滤掉
function show(view, ...kids) {
  view.replaceChildren(...kids.flat(Infinity).filter(Boolean));
}

function token() {
  try { return localStorage.getItem('radar-token') || ''; } catch { return ''; }
}

async function api(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}) };
  const t = token();
  if (t) headers.Authorization = 'Bearer ' + t;
  const resp = await fetch(path, { ...options, headers });
  if (resp.status === 401) { showTokenGate(); throw new Error('需要访问令牌'); }
  if (resp.status === 429) throw new Error('请求太频繁，请一分钟后再试');
  if (!resp.ok) {
    let msg = '请求失败（' + resp.status + '）';
    try { const b = await resp.json(); msg = b.error || b.message || msg; } catch { /* 响应不是 JSON */ }
    throw new Error(msg);
  }
  return resp.status === 204 ? null : resp.json();
}

let toastTimer;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2600);
}

async function copy(text) {
  try { await navigator.clipboard.writeText(text); toast('已复制'); } catch { toast('复制失败，请手动选择文本'); }
}

function safeUrl(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.href : null;
  } catch { return null; }
}

function daysAgo(iso) {
  return iso ? Math.floor((Date.now() - new Date(iso).getTime()) / 86400000) : null;
}

function dateText(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}

function splitList(text) {
  return text.split(/[,，、;；\n]+/).map((s) => s.trim()).filter(Boolean);
}

function showTokenGate() {
  const view = $('view');
  view.replaceChildren(h('div', { class: 'card' },
    h('h1', null, '需要访问令牌'),
    h('p', { class: 'sub' }, '这个实例设置了 RADAR_MCP_TOKEN。令牌只保存在当前浏览器。'),
    h('form', { class: 'row', onsubmit: (e) => {
      e.preventDefault();
      try { localStorage.setItem('radar-token', e.target.elements.t.value.trim()); } catch { /* 隐私模式下存不了 */ }
      location.reload();
    } }, h('input', { class: 'grow', name: 't', type: 'password', 'aria-label': '访问令牌' }), h('button', { class: 'primary' }, '继续'))));
}

/* ---------- 岗位 ---------- */

async function loadTracked() {
  if (state.status?.hosted) return;
  const apps = await api('/api/applications');
  state.tracked = new Map(apps.filter((a) => a.jobId).map((a) => [a.jobId, a]));
}

async function track(job, status) {
  const saved = await api('/api/applications', {
    method: 'POST',
    body: JSON.stringify({ company: job.company, title: job.title, jobId: job.id, applyUrl: job.applyUrl, status, source: 'MANUAL' }),
  });
  state.tracked.set(job.id, saved);
  toast(status === '想投' ? '已加入想投' : '已记录投递');
  return saved;
}

function jobMeta(j) {
  const age = daysAgo(j.postedAt);
  return h('div', { class: 'm' },
    [j.company, j.cities?.slice(0, 3).join(' / ') || '城市未注明', j.recruitType, j.gradYear ? j.gradYear + ' 届' : null, j.postedAt ? dateText(j.postedAt) : null].filter(Boolean).join(' · '),
    ' ',
    age != null && age <= 3 ? h('span', { class: 'tag new' }, '新') : null);
}

function jobActions(j) {
  const box = h('div', { class: 'acts' });
  const render = () => {
    const tracked = state.tracked.get(j.id);
    box.replaceChildren();
    if (tracked) {
      box.append(h('span', { class: 'tag done' }, tracked.status));
    } else if (!state.status?.hosted) {
      box.append(h('button', { onclick: () => track(j, '想投').then(render).catch((e) => toast(e.message)) }, '想投'));
    }
    const url = safeUrl(j.applyUrl);
    if (url) box.append(h('a', { class: 'btn', href: url, target: '_blank', rel: 'noopener noreferrer' }, '官网'));
  };
  render();
  return box;
}

function jobItem(j, extra) {
  return h('div', { class: 'item' },
    h('div', null,
      h('button', { class: 't', onclick: () => openJob(j.id) }, j.title),
      jobMeta(j),
      j.snippet ? h('div', { class: 'r' }, j.snippet) : null,
      extra),
    jobActions(j));
}

async function openJob(id) {
  const d = $('drawer');
  d.hidden = false;
  d.replaceChildren(h('p', { class: 'sub' }, '加载中…'));
  try {
    const j = await api('/api/jobs/' + id);
    const url = safeUrl(j.applyUrl);
    const tracked = state.tracked.get(j.id);
    const actions = h('div', { class: 'row' },
      url ? h('a', { class: 'btn primary', href: url, target: '_blank', rel: 'noopener noreferrer' }, '去官网投递') : null,
      !state.status?.hosted && !tracked ? h('button', { onclick: (e) => track(j, '已投递').then(() => { e.target.disabled = true; e.target.textContent = '已记录'; }).catch((x) => toast(x.message)) }, '我投了这个') : null,
      tracked ? h('span', { class: 'tag done' }, tracked.status) : null,
      h('button', { class: 'link', onclick: closeJob }, '关闭'));
    show(d,
      h('h1', null, j.title),
      jobMeta({ ...j, postedAt: j.publishedAt || j.firstSeenAt }),
      j.category ? h('div', { class: 'sub' }, j.category) : null,
      actions,
      j.description ? h('h2', null, '岗位职责') : null,
      j.description ? h('div', { class: 'jd' }, j.description) : null,
      j.requirement ? h('h2', null, '任职要求') : null,
      j.requirement ? h('div', { class: 'jd' }, j.requirement) : null,
      !j.description && !j.requirement ? h('p', { class: 'sub' }, '这个来源的列表不带 JD，请到官网查看。') : null,
      h('p', { class: 'sub' }, '信息以官网为准。最后确认在招：' + dateText(j.lastSeenAt)));
    d.scrollTop = 0;
  } catch (e) {
    d.replaceChildren(h('p', null, e.message), h('button', { class: 'link', onclick: closeJob }, '关闭'));
  }
}

function closeJob() { $('drawer').hidden = true; }

function collectingBanner() {
  const s = state.status;
  if (!s?.collecting) return null;
  const now = s.collectingSources?.length ? '，正在采集 ' + s.collectingSources.join('、') : '';
  return h('div', { class: 'banner' }, h('span', { class: 'pulse' }), '岗位库更新中' + now + '。已收录 ' + s.openJobs.toLocaleString() + ' 个在招岗位' + (s.snapshot ? '。' : '，首次采集约需十几分钟，可以先用起来。'));
}

async function viewJobs(view) {
  const f = state.jobs;
  const list = h('div', { class: 'list' });
  const info = h('span', { class: 'sub' });
  const run = async () => {
    list.replaceChildren(h('p', { class: 'empty' }, '搜索中…'));
    const q = new URLSearchParams({ limit: '50' });
    for (const k of ['keyword', 'company', 'city', 'recruitType', 'sinceDays']) if (f[k]) q.set(k, f[k]);
    try {
      const jobs = await api('/api/jobs?' + q);
      info.textContent = jobs.length === 50 ? '显示前 50 个，可以加条件缩小范围' : '共 ' + jobs.length + ' 个';
      list.replaceChildren(...(jobs.length ? jobs.map((j) => jobItem(j)) : [h('p', { class: 'empty' }, state.status?.openJobs ? '没有符合条件的岗位，换个关键词试试' : '岗位库还是空的，首次采集完成后这里就有数据了')]));
    } catch (e) {
      info.textContent = '';
      list.replaceChildren(h('p', { class: 'empty' }, e.message));
    }
  };
  const chipGroup = (key, options) => h('div', { class: 'chips' }, options.map(([label, value]) => {
    const b = h('button', { class: 'chip', type: 'button', 'aria-pressed': String(f[key] === value), onclick: () => {
      f[key] = f[key] === value ? '' : value;
      [...b.parentNode.children].forEach((c, i) => c.setAttribute('aria-pressed', String(f[key] === options[i][1])));
      run();
    } }, label);
    return b;
  }));
  const sources = await api('/api/sources').catch(() => []);
  const company = h('select', { 'aria-label': '公司', onchange: (e) => { f.company = e.target.value; run(); } },
    h('option', { value: '' }, '全部公司'),
    sources.filter((s) => s.openJobs > 0).sort((a, b) => b.openJobs - a.openJobs).map((s) => {
      const o = h('option', { value: s.company }, s.company + '（' + s.openJobs + '）');
      o.selected = s.company === f.company;
      return o;
    }));
  const keyword = h('input', { class: 'grow', type: 'search', value: f.keyword, placeholder: '关键词，如 后端 Java、大模型 算法', 'aria-label': '关键词' });
  show(view,
    h('div', { class: 'head' }, h('h1', null, '全部岗位'), info),
    collectingBanner(),
    h('form', { class: 'card', onsubmit: (e) => { e.preventDefault(); f.keyword = keyword.value.trim(); run(); } },
      h('div', { class: 'row' }, keyword, h('div', { style: 'flex:0 1 200px;min-width:0' }, company), h('button', { class: 'primary' }, '搜索')),
      chipGroup('city', CITIES.map((c) => [c, c])),
      h('div', { class: 'row' }, chipGroup('recruitType', [['校招', '校招'], ['实习', '实习']]), chipGroup('sinceDays', [['3 天内', '3'], ['7 天内', '7'], ['30 天内', '30']]))),
    h('div', { class: 'card' }, list));
  await run();
}

/* ---------- 今日推荐 ---------- */

function profileQuickForm(onSaved) {
  const roles = h('input', { placeholder: '如 后端开发、大模型算法', 'aria-label': '目标方向' });
  const skills = h('input', { placeholder: '如 Java、Spring、Redis', 'aria-label': '技能' });
  const cities = h('input', { placeholder: '如 上海、杭州', 'aria-label': '意向城市' });
  return h('form', { class: 'card', onsubmit: async (e) => {
    e.preventDefault();
    if (!roles.value.trim() && !skills.value.trim()) { toast('方向和技能至少填一项'); return; }
    try {
      await api('/api/profile', { method: 'PUT', body: JSON.stringify({ targetRoles: splitList(roles.value), skills: splitList(skills.value), cities: splitList(cities.value) }) });
      onSaved();
    } catch (x) { toast(x.message); }
  } },
  h('h2', null, '先告诉雷达你想找什么'),
  h('p', { class: 'sub' }, '填完就能看到按你的情况打分的岗位。数据只存在你自己的电脑上。'),
  h('div', { class: 'grid2' },
    h('label', { class: 'f' }, '目标方向', roles),
    h('label', { class: 'f' }, '技能', skills),
    h('label', { class: 'f span2' }, '意向城市（选填）', cities)),
  h('div', null, h('button', { class: 'primary' }, '看推荐')));
}

async function viewToday(view) {
  const [events, profile] = await Promise.all([api('/api/events?days=7'), api('/api/profile')]);
  const parts = [h('div', { class: 'head' }, h('h1', null, '今日推荐'), h('span', { class: 'sub' }, '规则打分，每一分都写明原因')), collectingBanner()];
  if (events.length) {
    parts.push(h('div', { class: 'card' }, h('h2', null, '接下来 7 天'), h('div', { class: 'list' }, events.slice(0, 5).map((e) => h('div', { class: 'item' },
      h('div', null, h('div', { class: 't' }, e.title), h('div', { class: 'm' }, e.localTime)),
      safeUrl(e.link) ? h('a', { class: 'btn', href: safeUrl(e.link), target: '_blank', rel: 'noopener noreferrer' }, '入口') : null)))));
  }
  if (!profile.targetRoles?.length && !profile.skills?.length) {
    parts.push(profileQuickForm(() => route()));
    show(view, parts);
    return;
  }
  const box = h('div', { class: 'card' }, h('p', { class: 'empty' }, '正在匹配…'));
  parts.push(box);
  show(view, parts);
  try {
    const matches = await api('/api/match?limit=30&maxPerCompany=4');
    box.replaceChildren(...(matches.length
      ? [h('div', { class: 'list' }, matches.map((m) => jobItem(m.job, h('div', { class: 'r' }, h('span', { class: 'score' }, m.score + ' 分'), '  ' + m.reasons.join('；')))))]
      : [h('p', { class: 'empty' }, state.status?.collecting ? '岗位还在采集，稍后再来看' : '没有符合档案的新岗位。可以放宽城市或方向')]));
  } catch (e) {
    box.replaceChildren(h('p', { class: 'empty' }, e.message));
  }
}

/* ---------- 投递进度 ---------- */

function reached(app, stage) {
  return app.status === stage || app.timeline?.some((t) => t.status === stage);
}

function funnelCounts(apps) {
  const applied = apps.filter((a) => !['想投'].includes(a.status) || reached(a, '已投递'));
  return {
    applied: applied.length,
    tests: applied.filter((a) => reached(a, '笔试/测评')).length,
    interviews: applied.filter((a) => reached(a, '面试中')).length,
    offers: applied.filter((a) => reached(a, 'Offer')).length,
  };
}

function reportText(c) {
  return '我的秋招战报：投递 ' + c.applied + ' 家，笔试 ' + c.tests + ' 场，面试 ' + c.interviews + ' 场，Offer ' + c.offers + ' 个。用校招雷达管的，岗位直接来自公司官网：' + REPO;
}

function applicationItem(a, refresh) {
  const select = h('select', { 'aria-label': '投递状态', onchange: async (e) => {
    try { await api('/api/applications/' + a.id, { method: 'PUT', body: JSON.stringify({ status: e.target.value }) }); toast('已更新'); refresh(); } catch (x) { toast(x.message); }
  } }, STAGES.map((s) => { const o = h('option', { value: s }, s); o.selected = s === a.status; return o; }));
  const url = safeUrl(a.applyUrl);
  return h('div', { class: 'item' },
    h('div', null,
      a.jobId ? h('button', { class: 't', onclick: () => openJob(a.jobId) }, a.title || a.company) : h('div', { class: 't' }, a.title || a.company),
      h('div', { class: 'm' }, a.company + ' · 更新于 ' + dateText(a.updatedAt)),
      a.note ? h('div', { class: 'r' }, a.note) : null,
      a.timeline?.length > 1 ? h('details', null, h('summary', { class: 'sub' }, '进度记录 ' + a.timeline.length + ' 条'),
        h('ul', { class: 'timeline' }, a.timeline.map((t) => h('li', null, dateText(t.at) + ' ' + [t.status, t.note].filter(Boolean).join('：'))))) : null),
    h('div', { class: 'acts' }, select, url ? h('a', { class: 'btn', href: url, target: '_blank', rel: 'noopener noreferrer' }, '官网') : null));
}

async function viewApplications(view) {
  const apps = await api('/api/applications');
  const c = funnelCounts(apps);
  const refresh = () => viewApplications(view);
  const company = h('input', { placeholder: '公司', 'aria-label': '公司' });
  const title = h('input', { placeholder: '岗位', 'aria-label': '岗位' });
  const groups = STAGES.map((s) => [s, apps.filter((a) => a.status === s)]).filter(([, v]) => v.length);
  show(view,
    h('div', { class: 'head' }, h('h1', null, '投递进度'), h('span', { class: 'sub' }, '接上邮箱后，笔试和面试邀请会自动更新进度')),
    h('div', { class: 'card' },
      h('div', { class: 'funnel' },
        h('div', { class: 'stage' }, h('b', null, c.applied), h('span', null, '已投递')),
        h('div', { class: 'stage' }, h('b', null, c.tests), h('span', null, '笔试/测评')),
        h('div', { class: 'stage' }, h('b', null, c.interviews), h('span', null, '面试')),
        h('div', { class: 'stage' }, h('b', null, c.offers), h('span', null, 'Offer'))),
      h('div', null, h('button', { onclick: () => copy(reportText(c)), disabled: c.applied === 0 }, '复制我的秋招战报'))),
    h('form', { class: 'row', onsubmit: async (e) => {
      e.preventDefault();
      if (!company.value.trim()) { toast('至少填公司'); return; }
      try { await api('/api/applications', { method: 'POST', body: JSON.stringify({ company: company.value.trim(), title: title.value.trim(), status: '已投递', source: 'MANUAL' }) }); refresh(); } catch (x) { toast(x.message); }
    } }, h('div', { class: 'grow' }, company), h('div', { class: 'grow' }, title), h('button', null, '手动添加')),
    groups.length
      ? h('div', { class: 'card' }, groups.map(([s, v]) => [h('div', { class: 'group-title' }, s + ' ' + v.length), h('div', { class: 'list' }, v.map((a) => applicationItem(a, refresh)))]))
      : h('div', { class: 'card' }, h('p', { class: 'empty' }, '还没有投递记录。在岗位详情里点「我投了这个」，或用浏览器插件一键记录。')));
}

/* ---------- 日程 ---------- */

async function viewSchedule(view) {
  const events = await api('/api/events?days=60');
  const refresh = () => viewSchedule(view);
  const company = h('input', { 'aria-label': '公司' });
  const kind = h('select', { 'aria-label': '类型' }, ['面试', '笔试', '测评', '日程'].map((k) => h('option', { value: k }, k)));
  const start = h('input', { type: 'datetime-local', 'aria-label': '开始时间' });
  const link = h('input', { placeholder: '会议或考试链接（选填）', 'aria-label': '链接' });
  const ics = location.origin + '/api/calendar.ics' + (token() ? '?token=' + encodeURIComponent(token()) : '');
  show(view,
    h('div', { class: 'head' }, h('h1', null, '日程'), h('span', { class: 'sub' }, '时间按北京时间录入')),
    h('div', { class: 'card' }, events.length
      ? h('div', { class: 'list' }, events.map((e) => h('div', { class: 'item' },
        h('div', null, h('div', { class: 't' }, e.title), h('div', { class: 'm' }, e.localTime), e.location ? h('div', { class: 'r' }, e.location) : null),
        h('div', { class: 'acts' },
          safeUrl(e.link) ? h('a', { class: 'btn', href: safeUrl(e.link), target: '_blank', rel: 'noopener noreferrer' }, '入口') : null,
          h('button', { onclick: async () => { try { await api('/api/events/' + e.id + '/cancel', { method: 'POST' }); refresh(); } catch (x) { toast(x.message); } } }, '取消')))))
      : h('p', { class: 'empty' }, '接下来 60 天没有笔试或面试。接上邮箱后邀请会自动进来。')),
    h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault();
      if (!company.value.trim() || !start.value) { toast('请填公司和开始时间'); return; }
      try { await api('/api/events', { method: 'POST', body: JSON.stringify({ company: company.value.trim(), kind: kind.value, start: start.value.replace('T', ' '), link: link.value.trim() || null }) }); refresh(); } catch (x) { toast(x.message); }
    } },
    h('h2', null, '手动添加'),
    h('div', { class: 'grid4' }, h('label', { class: 'f' }, '公司', company), h('label', { class: 'f' }, '类型', kind), h('label', { class: 'f' }, '开始时间', start), h('label', { class: 'f' }, '链接', link)),
    h('div', null, h('button', null, '添加'))),
    h('div', { class: 'card' }, h('h2', null, '订阅到手机或电脑日历'),
      h('p', { class: 'sub' }, '在苹果、Google 或 Outlook 日历里添加订阅，每个日程带提前 1 天和 1 小时的提醒。手机要能访问这个地址才行。'),
      h('div', { class: 'copy-box' }, h('pre', null, ics), h('button', { onclick: () => copy(ics) }, '复制'))));
}

/* ---------- 档案 ---------- */

async function viewProfile(view) {
  const p = await api('/api/profile');
  const text = (key, label, hint, wide) => h('label', { class: 'f' + (wide ? ' span2' : '') }, label, h('input', { name: key, value: (p[key] || []).join('、'), placeholder: hint }));
  const types = ['校招', '实习'].map((t) => {
    const c = h('input', { type: 'checkbox', name: 'recruitTypes', value: t, style: 'width:auto' });
    c.checked = (p.recruitTypes || []).includes(t);
    return h('label', { class: 'row', style: 'gap:6px' }, c, t);
  });
  show(view,
    h('div', { class: 'head' }, h('h1', null, '我的档案'), h('span', { class: 'sub' }, '只存在你自己的电脑上，用于匹配打分')),
    h('form', { class: 'card', onsubmit: async (e) => {
      e.preventDefault();
      const f = e.target.elements;
      const body = {
        gradYear: f.gradYear.value ? Number(f.gradYear.value) : null,
        degree: f.degree.value.trim(), major: f.major.value.trim(), summary: f.summary.value.trim(),
        recruitTypes: [...e.target.querySelectorAll('[name=recruitTypes]:checked')].map((c) => c.value),
      };
      for (const k of ['targetRoles', 'skills', 'cities', 'preferredCompanies', 'excludedCompanies', 'excludeKeywords']) body[k] = splitList(f[k].value);
      try { await api('/api/profile', { method: 'PUT', body: JSON.stringify(body) }); toast('已保存'); } catch (x) { toast(x.message); }
    } },
    h('div', { class: 'grid2' },
      h('label', { class: 'f' }, '毕业届别', h('input', { name: 'gradYear', type: 'number', min: 2024, max: 2035, value: p.gradYear ?? '', placeholder: '2027' })),
      h('label', { class: 'f' }, '学历', h('input', { name: 'degree', value: p.degree ?? '', placeholder: '本科 / 硕士 / 博士' })),
      h('label', { class: 'f span2' }, '专业', h('input', { name: 'major', value: p.major ?? '' })),
      text('targetRoles', '目标方向', '后端开发、大模型算法', true),
      text('skills', '技能', 'Java、Spring、Redis', true),
      text('cities', '意向城市（第一个优先）', '上海、杭州'),
      h('div', { class: 'f' }, '招聘类型', h('div', { class: 'row' }, types)),
      text('preferredCompanies', '偏好公司', '字节、腾讯'),
      text('excludedCompanies', '排除公司', ''),
      text('excludeKeywords', '标题排除词', '销售、外包', true),
      h('label', { class: 'f span2' }, '一句话简介', h('textarea', { name: 'summary' }, p.summary ?? ''))),
    h('div', null, h('button', { class: 'primary' }, '保存'))));
}

/* ---------- 接入与数据源 ---------- */

function snippet(title, code) {
  return h('div', { style: 'display:grid;gap:4px;min-width:0' }, h('div', { class: 'sub' }, title),
    h('div', { class: 'copy-box' }, h('pre', null, code), h('button', { type: 'button', onclick: () => copy(code) }, '复制')));
}

function statusTag(s) {
  const map = { SUCCESS: ['正常', ''], WARN: ['需关注', 'warn'], FAILED: ['失败', 'bad'] };
  const [label, cls] = map[s.lastRunStatus] || ['未采集', ''];
  return h('span', { class: 'tag ' + cls, title: s.lastMessage || '' }, label);
}

async function viewConnect(view) {
  const sources = await api('/api/sources');
  const s = state.status;
  const mcp = location.origin + '/mcp';
  show(view,
    h('div', { class: 'head' }, h('h1', null, '接入 Agent'), h('span', { class: 'sub' }, '让你的 Agent 直接查岗位和管投递')),
    h('div', { class: 'card' },
      snippet('Claude Code', 'claude mcp add --transport http campus-radar ' + mcp),
      snippet('Codex', 'codex mcp add campus-radar --url ' + mcp),
      snippet('其他支持 MCP 的客户端（Cursor、OpenClaw、DeepSeek Harness 等）', JSON.stringify({ mcpServers: { 'campus-radar': { type: 'http', url: mcp } } }, null, 2)),
      s.hosted ? h('p', { class: 'sub' }, '这是公开只读实例，只能查岗位。匹配、投递和提醒需要在自己电脑上运行。') : null),
    !s.hosted ? h('div', { class: 'card' }, h('h2', null, '提醒与邮箱'),
      h('p', null, '每日推送：', h('span', { class: 'tag ' + (s.pushEnabled ? 'done' : '') }, s.pushEnabled ? '已开启' : '未配置')),
      h('p', null, '邮箱识别：', h('span', { class: 'tag ' + (s.mailEnabled ? 'done' : '') }, s.mailEnabled ? '已开启' : '未配置')),
      !s.pushEnabled || !s.mailEnabled ? h('p', { class: 'sub' }, '通过环境变量配置，步骤见项目 README。') : null) : null,
    h('div', { class: 'card' }, h('div', { class: 'head' }, h('h2', null, '数据源'), h('span', { class: 'sub' }, s.openJobs.toLocaleString() + ' 个在招岗位')),
      h('div', { class: 'table-wrap' }, h('table', null,
        h('thead', null, h('tr', null, h('th', null, '公司'), h('th', null, '在招'), h('th', null, '状态'), h('th', null, '最近采集'))),
        h('tbody', null, sources.slice().sort((a, b) => b.openJobs - a.openJobs).map((x) => h('tr', null,
          h('td', null, x.company), h('td', { class: 'n' }, x.openJobs), h('td', null, statusTag(x)), h('td', null, dateText(x.lastRunAt) || '—'))))))));
}

/* ---------- 路由 ---------- */

const ROUTES = [
  { path: 'today', label: '今日推荐', view: viewToday, personal: true },
  { path: 'jobs', label: '全部岗位', view: viewJobs },
  { path: 'applications', label: '投递进度', view: viewApplications, personal: true },
  { path: 'schedule', label: '日程', view: viewSchedule, personal: true },
  { path: 'profile', label: '我的档案', view: viewProfile, personal: true },
  { path: 'connect', label: '接入 Agent', view: viewConnect },
];

function available() {
  return ROUTES.filter((r) => !r.personal || !state.status?.hosted);
}

async function route() {
  closeJob();
  const name = location.hash.replace(/^#\/?/, '');
  const routes = available();
  const r = routes.find((x) => x.path === name) || routes[0];
  document.querySelectorAll('#nav a').forEach((a) => (a.getAttribute('href') === '#/' + r.path ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current')));
  const view = $('view');
  try {
    await loadTracked();
    await r.view(view);
  } catch (e) {
    if (e.message !== '需要访问令牌') view.replaceChildren(h('div', { class: 'card' }, h('p', null, e.message)));
  }
}

async function refreshStatus() {
  const before = state.status;
  state.status = await api('/api/status');
  const s = state.status;
  $('side-foot').textContent = s.openJobs.toLocaleString() + ' 个在招岗位' + (s.lastCollectedAt ? '，' + dateText(s.lastCollectedAt) + '更新' : '') + ' · v' + s.version;
  return before;
}

async function boot() {
  try {
    await refreshStatus();
  } catch (e) {
    if (e.message !== '需要访问令牌') $('view').replaceChildren(h('div', { class: 'card' }, h('p', null, e.message)));
    return;
  }
  $('nav').replaceChildren(...available().map((r) => h('a', { href: '#/' + r.path }, r.label)));
  window.addEventListener('hashchange', route);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeJob(); });
  await route();
  // 采集进行中时刷新进度，采集结束后重绘一次，让新岗位显示出来
  setInterval(async () => {
    if (!state.status.collecting) return;
    await refreshStatus().catch(() => {});
    if (!state.status.collecting) route();
  }, 5000);
}

boot();
