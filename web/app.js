import { hydrate, match, search } from './search.js';
import { STAGES, addApp, exportAll, getProfile, importAll, listApps, persistent, removeApp, saveProfile, setStatus, trackedJobs } from './store.js';

const REPO = 'https://github.com/Ajax-1/campus-radar';
const PAGE = 20;
const SUGGESTIONS = ['后端 Java', '大模型 算法', '前端 React', '产品经理', '测试开发', '北京 实习'];

const $ = (id) => document.getElementById(id);
const state = { db: null, filters: {}, shown: PAGE };

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k in el && k !== 'list') el[k] = v;
    else el.setAttribute(k, v);
  }
  el.append(...kids.flat(Infinity).filter((c) => c != null && c !== false));
  return el;
}

function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#i-' + name);
  svg.append(use);
  return svg;
}

// 岗位链接来自外部网站，导入的备份文件也可能被改过，只放行 http(s)
function safeUrl(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : null;
  } catch {
    return null;
  }
}

let toastTimer;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2400);
}

async function copy(text, btn) {
  try {
    await navigator.clipboard.writeText(text);
    if (btn) {
      btn.replaceChildren(icon('check'));
      setTimeout(() => btn.replaceChildren(icon('copy')), 1400);
    }
    toast('已复制');
  } catch {
    toast('复制失败，请手动选中');
  }
}

const splitList = (s) => s.split(/[,，、;；\s]+/).map((x) => x.trim()).filter(Boolean);

function daysAgo(day) {
  return day == null ? null : Math.floor(Date.now() / 86400000) - day;
}

function ageText(j) {
  const d = daysAgo(j.publishedDay);
  if (d == null) return '';
  if (d <= 0) return '今天';
  if (d <= 30) return d + ' 天前';
  const [, month, day] = j.publishedAt.split('-').map(Number);
  return month + ' 月 ' + day + ' 日';
}

function updatedText(iso) {
  const days = Math.floor((Date.now() - Date.parse(iso)) / 86400000);
  return days <= 0 ? '今日更新' : days + ' 天前更新';
}

function metaOf(j) {
  return [
    h('b', null, j.company),
    h('span', null, j.cities.slice(0, 3).join(' ') + (j.cities.length > 3 ? ' 等' : '') || '城市未注明'),
    h('span', null, j.recruitType),
    j.gradYear ? h('span', null, j.gradYear + ' 届') : null,
  ];
}

/* 岗位详情 */

function closeSheet() {
  const root = $('overlay');
  if (root.hidden) return;
  root.classList.remove('open');
  const back = root.opener;
  setTimeout(() => { root.hidden = true; root.replaceChildren(); back?.focus?.(); }, 220);
}

function openJob(j, opener) {
  const root = $('overlay');
  root.opener = opener;
  const url = safeUrl(j.applyUrl);
  const acts = h('div', { class: 'sheet-acts' });
  const renderActs = () => {
    const tracked = trackedJobs().get(j.id);
    acts.replaceChildren(
      url ? h('a', { class: 'btn primary', href: url, target: '_blank', rel: 'noopener noreferrer' }, '去官网查看和投递', icon('arrow-up-right')) : null,
      ...['想投', '已投递'].map((status) => h('button', {
        class: 'btn' + (tracked?.status === status ? ' on' : ''),
        onclick: () => { addApp({ jobId: j.id, company: j.company, title: j.title, applyUrl: j.applyUrl, status }); toast('已记到投递进度'); renderActs(); refresh(); },
      }, status === '已投递' ? '我投了' : '想投')),
    );
  };
  renderActs();
  const sheet = h('aside', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': j.title },
    h('button', { class: 'close', 'aria-label': '关闭', onclick: closeSheet }, icon('x')),
    h('p', { class: 'co' }, j.company),
    h('h2', null, j.title),
    h('dl', null,
      h('dt', null, '城市'), h('dd', null, j.cities.join('、') || '未注明'),
      h('dt', null, '类型'), h('dd', null, j.recruitType + (j.category ? '，' + j.category : '')),
      h('dt', null, '届别'), h('dd', null, j.gradYear ? j.gradYear + ' 届' : '未注明'),
      j.publishedAt ? [h('dt', null, '发布'), h('dd', null, j.publishedAt)] : null,
      j.tags.length ? [h('dt', null, '技能'), h('dd', null, h('div', { class: 'tags' }, j.tags.map((t) => h('span', null, t))))] : null),
    h('p', { class: 'note' }, '技能标签从岗位描述中提取。完整的职责和要求以官网为准，截止时间请到官网确认。'),
    acts);
  root.replaceChildren(h('div', { class: 'scrim', onclick: closeSheet }), sheet);
  root.hidden = false;
  requestAnimationFrame(() => requestAnimationFrame(() => root.classList.add('open')));
  sheet.querySelector('.close').focus();
}

function jobRow(j, tracked) {
  return h('li', null, h('button', { class: 'job', onclick: (e) => openJob(j, e.currentTarget) },
    h('h3', null, j.title),
    h('div', { class: 'meta' }, metaOf(j)),
    j.tags.length ? h('div', { class: 'tags' }, j.tags.slice(0, 5).map((t) => h('span', null, t))) : null,
    h('div', { class: 'side' }, tracked.has(j.id) ? h('span', { class: 'state' }, tracked.get(j.id).status) : ageText(j))));
}

/* 岗位搜索 */

function syncUrl() {
  const p = new URLSearchParams(Object.entries(state.filters).filter(([, v]) => v));
  const query = p.toString();
  history.replaceState(null, '', '#/' + (query ? '?' + query : ''));
}

function readUrl() {
  const q = location.hash.split('?')[1] || '';
  return Object.fromEntries(new URLSearchParams(q));
}

function filterSelect(key, label, options) {
  return h('select', {
    'aria-label': label,
    class: state.filters[key] ? 'active' : '',
    onchange: (e) => { state.filters[key] = e.target.value; state.shown = PAGE; syncUrl(); renderResults(); },
  }, h('option', { value: '' }, label), options.map(([value, text]) => h('option', { value, selected: String(state.filters[key] || '') === String(value) }, text)));
}

function filterOptions() {
  const cities = new Map();
  const years = new Set();
  for (const j of state.db.jobs) {
    j.cities.forEach((c) => cities.set(c, (cities.get(c) || 0) + 1));
    if (j.gradYear) years.add(j.gradYear);
  }
  return {
    cities: [...cities].sort((a, b) => b[1] - a[1]).slice(0, 24).map(([c]) => [c, c]),
    years: [...years].sort().map((y) => [y, y + ' 届']),
  };
}

function renderResults() {
  const root = $('results');
  if (!root) return;
  const { keyword, company, city, type, year, since } = state.filters;
  const searching = Object.values(state.filters).some(Boolean);
  $('home').classList.toggle('searching', searching);
  if (!searching || !state.db) { root.replaceChildren(); return; }

  const { cities, years } = filterOptions();
  const { total, jobs } = search(state.db, {
    keyword, company, city, recruitType: type, gradYear: Number(year) || null, sinceDays: Number(since) || null, limit: state.shown,
  });
  const tracked = trackedJobs();
  const focused = document.activeElement?.tagName === 'SELECT' ? document.activeElement.getAttribute('aria-label') : null;
  const clear = () => { state.filters = {}; state.shown = PAGE; $('q').value = ''; syncUrl(); renderResults(); };
  root.replaceChildren(...[
    h('div', { class: 'filters' },
      filterSelect('company', '公司', state.db.companies.map((c) => [c.company, c.company])),
      filterSelect('city', '城市', cities),
      filterSelect('type', '类型', [['校招', '校招'], ['实习', '实习']]),
      filterSelect('year', '届别', years),
      filterSelect('since', '发布时间', [['3', '3 天内'], ['7', '7 天内'], ['30', '30 天内']])),
    total ? [
      h('p', { class: 'count', role: 'status' }, total.toLocaleString() + ' 个岗位'),
      h('ul', { class: 'results' }, jobs.map((j) => jobRow(j, tracked))),
      total > jobs.length ? h('div', { class: 'more' }, h('button', { class: 'btn', onclick: () => { state.shown += PAGE; renderResults(); } }, '加载更多')) : h('div', { class: 'more' }),
    ] : h('div', { class: 'empty' },
      h('h2', null, '没有找到符合条件的岗位'),
      h('p', null, '试试少一个关键词，或者换一个城市。'),
      h('button', { class: 'btn', onclick: clear }, '清除筛选')),
  ].flat());
  // 列表是整块重绘的，键盘用户改完筛选后焦点要留在原地
  if (focused) root.querySelector('select[aria-label="' + focused + '"]')?.focus();
}

function companyBoard() {
  const go = (company) => { state.filters = { company }; state.shown = PAGE; syncUrl(); renderResults(); scrollTo(0, 0); };
  const [lead, second, third, ...rest] = state.db.companies;
  const tile = (c, cls = '') => h('button', { class: 'tile ' + cls, onclick: () => go(c.company) }, h('b', null, c.company), h('span', null, c.count.toLocaleString() + (cls === 'lead' ? '' : ' 个')));
  // 最后一行不满四个时拉宽，不留空格
  const tail = rest.length % 4;
  const cls = (i) => (tail && i >= rest.length - tail ? 'fill' + tail : '');
  return h('div', { class: 'board' }, tile(lead, 'lead'), tile(second, 'wide'), tile(third, 'wide'), rest.map((c, i) => tile(c, cls(i))));
}

function agentPanel() {
  const site = location.origin + location.pathname.replace(/index\.html$/, '');
  const mcp = state.db?.mcpUrl;
  const options = [
    ['一句话接入', '读取 ' + site + 'SKILL.md，按里面的说明接入校招雷达', '把这句话发给你的 Agent，它会自己完成接入。'],
    mcp && ['Claude Code', 'claude mcp add --transport http campus-radar ' + mcp, '在终端运行，然后重启会话。'],
    mcp && ['Codex', 'codex mcp add campus-radar --url ' + mcp, '在终端运行，然后重启会话。'],
    mcp && ['其他客户端', mcp, '在 MCP 配置里填这个地址，传输方式选 Streamable HTTP。'],
  ].filter(Boolean);
  const body = h('div', null);
  const tabs = h('div', { class: 'tabs', role: 'tablist' });
  const pick = (i) => {
    [...tabs.children].forEach((b, n) => b.setAttribute('aria-selected', String(n === i)));
    const [, text, hint] = options[i];
    const btn = h('button', { 'aria-label': '复制', onclick: (e) => copy(text, e.currentTarget) }, icon('copy'));
    body.replaceChildren(h('div', { class: 'cmd' }, h('code', null, text), btn), h('p', { class: 'hint' }, hint));
  };
  options.forEach(([name], i) => tabs.append(h('button', { role: 'tab', onclick: () => pick(i) }, name)));
  pick(0);
  return h('div', null, tabs, body);
}

function viewHome(view) {
  const count = state.db ? state.db.jobs.length.toLocaleString() + ' 个在招岗位，' + updatedText(state.db.generatedAt) : '正在加载岗位';
  const input = h('input', {
    id: 'q', type: 'search', placeholder: '试试：字节 后端 Java', autocomplete: 'off', enterKeyHint: 'search', 'aria-label': '搜索岗位',
    value: state.filters.keyword || '',
    oninput: (e) => { state.filters.keyword = e.target.value.trim(); state.shown = PAGE; syncUrl(); renderResults(); },
  });
  const suggest = (s) => () => { input.value = s; state.filters.keyword = s; state.shown = PAGE; syncUrl(); renderResults(); input.focus(); };
  view.replaceChildren(h('div', { id: 'home', class: 'wrap' },
    h('section', { class: 'hero' + (state.entered ? '' : ' enter') },
      h('p', { class: 'pulse' }, state.db ? h('i') : null, count),
      h('h1', null, '17 家公司的校招岗位，', h('br'), h('em', null, '一次搜完。')),
      h('p', { class: 'lede' }, '岗位直接来自公司官网，每天更新。不用注册，也不用安装。'),
      h('form', { class: 'searchbox', role: 'search', onsubmit: (e) => { e.preventDefault(); input.blur(); } },
        icon('magnifying-glass'), input),
      h('div', { class: 'suggest' }, SUGGESTIONS.map((s) => h('button', { type: 'button', onclick: suggest(s) }, s)))),
    h('div', { id: 'results', 'aria-live': 'polite' }),
    state.db ? h('div', { class: 'intro' },
      h('section', null,
        h('h2', { class: 'section-title' }, '按公司看'),
        companyBoard()),
      state.db.mcpUrl ? h('section', { class: 'agent' },
        h('div', null,
          h('h2', { class: 'section-title' }, '让 Agent 帮你找'),
          h('p', { class: 'section-lede' }, '接入后可以直接问：这周字节有什么新的后端岗位？')),
        agentPanel()) : null,
      h('section', { class: 'local' },
        h('div', null,
          h('h2', { class: 'section-title' }, '档案和投递记录，只留在你的浏览器'),
          h('p', { class: 'section-lede' }, '不用账号，也不会上传。推荐按规则打分，每一分都写明理由。'),
          h('div', { class: 'actions', style: 'margin-top:22px' }, h('a', { class: 'btn primary', href: '#/foryou' }, '填方向，看推荐'))),
        h('ul', { class: 'facts' },
          h('li', null, h('b', null, '只帮你找和记'), h('span', null, '投递永远由你在官网完成，不碰你的账号。')),
          h('li', null, h('b', null, '换设备不丢'), h('span', null, '投递记录可以导出成文件，到新设备再导入。')),
          h('li', null, h('b', null, '想要邮箱识别和微信提醒'), h('span', null, h('a', { href: '#/agent', style: 'color:var(--accent)' }, '在自己电脑上运行完整版'), '，笔试面试会自动进日历。'))))) : null));
  state.entered = true;
  if (state.db) renderResults();
  else if (Object.values(state.filters).some(Boolean)) {
    $('home').classList.add('searching');
    $('results').append(h('div', { class: 'skeleton', 'aria-label': '加载中' }, h('i'), h('i'), h('i')));
  }
}

/* 推荐 */

const PROFILE_FIELDS = [
  ['targetRoles', '目标方向', '写具体的岗位名，如 后端开发、大模型算法'],
  ['skills', '技能', '写简历里真实出现的技术'],
  ['cities', '意向城市', '第一个是首选，如 北京 上海'],
];

function viewForYou(view) {
  const p = getProfile();
  const list = h('div', null);
  const field = (key, label, help, extra) => h('div', { class: 'field' },
    h('label', { for: 'f-' + key }, label),
    h('input', { type: 'text', id: 'f-' + key, value: (p[key] || []).join('、') }),
    extra,
    help ? h('small', null, help) : null);
  const skillPick = h('div', { class: 'pick' }, ['Java', 'Go', 'Python', 'C++', 'React', 'PyTorch', '大模型', 'Redis'].map((t) => h('button', {
    type: 'button',
    onclick: () => {
      const input = $('f-skills');
      const items = splitList(input.value);
      if (!items.includes(t)) input.value = [...items, t].join('、');
    },
  }, t)));
  const year = new Date().getFullYear();
  const yearSelect = h('select', { id: 'f-gradYear' }, h('option', { value: '' }, '不限'),
    [year, year + 1, year + 2].map((y) => h('option', { value: y, selected: p.gradYear === y }, y + ' 届')));
  const typeBox = (t) => h('label', null, h('input', { type: 'checkbox', name: 'type', value: t, checked: (p.recruitTypes || []).includes(t) }), t);

  const render = () => {
    const profile = getProfile();
    const empty = !(profile.targetRoles?.length || profile.skills?.length);
    if (empty) {
      list.replaceChildren(h('div', { class: 'empty' }, h('h2', null, '先告诉我你想找什么'), h('p', null, '填上方向或技能就能看到推荐。')));
      return;
    }
    const tracked = trackedJobs();
    const results = match(state.db, profile, { skip: new Set(tracked.keys()) });
    if (!results.length) {
      list.replaceChildren(h('div', { class: 'empty' }, h('h2', null, '没有匹配的岗位'), h('p', null, '放宽城市或届别，或者换一种写法，如「后端」而不是「后端开发工程师」。')));
      return;
    }
    list.replaceChildren(h('p', { class: 'count' }, '匹配度最高的 ' + results.length + ' 个岗位，分数不代表通过概率'),
      h('div', null, results.map(({ job, score, reasons }) => h('button', { class: 'rec', onclick: (e) => openJob(job, e.currentTarget) },
        h('span', { class: 'score' }, score),
        h('h3', null, job.title),
        h('div', { class: 'meta' }, metaOf(job)),
        h('p', { class: 'why' }, reasons.join('；'))))));
  };

  const form = h('form', {
    class: 'panel form',
    onsubmit: (e) => {
      e.preventDefault();
      const value = (id) => splitList($(id).value);
      saveProfile({
        targetRoles: value('f-targetRoles'),
        skills: value('f-skills'),
        cities: value('f-cities'),
        gradYear: Number(yearSelect.value) || null,
        recruitTypes: [...form.querySelectorAll('[name=type]:checked')].map((c) => c.value),
        preferredCompanies: value('f-preferredCompanies'),
        excludedCompanies: value('f-excludedCompanies'),
        excludeKeywords: value('f-excludeKeywords'),
      });
      toast('已保存到这个浏览器');
      render();
    },
  },
  PROFILE_FIELDS.map(([k, l, help]) => field(k, l, help, k === 'skills' ? skillPick : null)),
  h('div', { class: 'field' }, h('label', { for: 'f-gradYear' }, '毕业届别'), yearSelect),
  h('fieldset', { class: 'field' }, h('legend', null, '招聘类型'), h('div', { class: 'checks' }, typeBox('校招'), typeBox('实习'))),
  h('details', null, h('summary', null, '更多条件'), h('div', null,
    field('preferredCompanies', '偏好公司'),
    field('excludedCompanies', '排除公司'),
    field('excludeKeywords', '标题排除词', '如 销售 外包'))),
  h('div', { class: 'form-end' }, h('button', { class: 'btn primary' }, '保存并推荐')));

  view.replaceChildren(h('div', { class: 'wrap page' },
    h('div', { class: 'page-head' }, h('div', null, h('h1', null, '为你推荐'), h('p', null, '按方向、技能、城市和新鲜度打分。档案只存在这个浏览器。'))),
    h('div', { class: 'split' }, form, list)));
  render();
}

/* 投递进度 */

function download(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  h('a', { href: url, download: name }).click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const reached = (a, stage) => {
  const order = STAGES.indexOf(a.status);
  return order >= STAGES.indexOf(stage) && order <= STAGES.indexOf('Offer');
};

function viewTracker(view) {
  const apps = listApps();
  const stored = persistent();
  const company = h('input', { type: 'text', id: 't-company', required: true });
  const title = h('input', { type: 'text', id: 't-title', required: true });
  const picker = h('input', {
    type: 'file', accept: 'application/json,.json', hidden: true,
    onchange: async (e) => {
      try {
        const n = importAll(JSON.parse(await e.target.files[0].text()));
        toast('已导入 ' + n + ' 条投递记录');
        viewTracker(view);
      } catch (x) {
        toast(x instanceof SyntaxError ? '文件格式不对' : x.message);
      }
    },
  });
  const row = (a) => {
    const url = safeUrl(a.applyUrl);
    const text = h('div', null, h('b', null, a.title), h('span', null, a.company));
    return h('div', { class: 'track' },
      url ? h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, text) : text,
      h('select', {
        'aria-label': '进度',
        onchange: (e) => { setStatus(a.id, e.target.value); viewTracker(view); },
      }, STAGES.map((s) => h('option', { value: s, selected: s === a.status }, s))),
      h('button', { class: 'btn quiet small', style: 'padding:0;width:34px', 'aria-label': '删除', onclick: () => { removeApp(a.id); viewTracker(view); } }, icon('x')));
  };
  view.replaceChildren(h('div', { class: 'wrap page' },
    h('div', { class: 'page-head' },
      h('div', null, h('h1', null, '投递进度'), h('p', null, '记录只存在这个浏览器。')),
      h('div', { class: 'actions' },
        h('button', { class: 'btn small', onclick: () => download('campus-radar-backup.json', JSON.stringify(exportAll(), null, 2)) }, icon('download-simple'), '导出'),
        h('button', { class: 'btn small', onclick: () => picker.click() }, icon('upload-simple'), '导入'), picker)),
    h('div', { class: 'funnel' },
      [['已投递', '已投递'], ['笔试/测评', '笔试测评'], ['面试中', '面试'], ['Offer', 'Offer']].map(([stage, label]) => h('div', null,
        h('b', null, apps.filter((a) => reached(a, stage)).length), h('span', null, label)))),
    apps.length ? h('div', null, apps.map(row)) : h('div', { class: 'empty' },
      h('h2', null, '还没有记录'),
      h('p', null, '在岗位详情里点「想投」或「我投了」，就会出现在这里。'),
      h('a', { class: 'btn', href: '#/' }, '去找岗位')),
    h('form', {
      class: 'panel form', style: 'margin-top:26px',
      onsubmit: (e) => {
        e.preventDefault();
        addApp({ company: company.value.trim(), title: title.value.trim(), status: '已投递' });
        viewTracker(view);
      },
    },
    h('div', { class: 'field' }, h('label', { for: 't-company' }, '公司'), company),
    h('div', { class: 'field' }, h('label', { for: 't-title' }, '岗位'), title),
    h('div', { class: 'form-end' }, h('button', { class: 'btn' }, '添加投递'))),
    h('p', { class: 'notice' }, icon('lock-key'), h('span', null,
      stored ? '清除浏览器数据时记录会一起删除，建议定期导出。' : '这个浏览器不允许保存数据，刷新后记录会消失，请及时导出。',
      '邮箱自动识别和提醒需要', h('a', { href: '#/agent' }, '运行完整版'), '。'))));
}

/* 接入 Agent */

function viewAgent(view) {
  const mcp = state.db?.mcpUrl;
  view.replaceChildren(h('div', { class: 'wrap page' },
    h('div', { class: 'page-head' }, h('div', null, h('h1', null, '接入 Agent'), h('p', null, mcp ? '接入公开实例不用安装任何东西，只读，不收集个人信息。' : '公开实例即将开放。现在可以在自己电脑上运行完整版，再接入 Agent。'))),
    h('div', { class: 'stack' },
      mcp ? [
        h('section', { class: 'agent' }, agentPanel()),
        h('section', null,
          h('h2', null, '公开实例提供的工具'),
          h('div', { class: 'tools' },
            [['search_jobs', '按公司、城市、方向和技能检索岗位'], ['get_job', '查看岗位标签和官网链接'], ['list_sources', '查看覆盖的公司和更新时间']].map(([name, text]) => h('div', null, h('code', null, name), h('p', null, text))))),
      ] : null,
      h('section', null,
        h('h2', null, '想要完整功能，在自己电脑上运行'),
        h('p', { class: 'prose' }, '完整版可以读 JD 全文、按 JD 改简历、识别邮件里的笔试面试邀请、进日历并推送到微信。所有数据都在你自己的电脑上。安装方法见',
          h('a', { href: REPO + '#在自己电脑上运行', target: '_blank', rel: 'noopener noreferrer' }, ' 项目主页'), '。')),
      h('section', null,
        h('h2', null, '数据从哪里来'),
        h('p', { class: 'prose' }, '只采公司官网公开的校招和实习岗位，每天一轮，低频请求。公开实例不展示 JD 原文，请回官网查看和投递。如果你代表某家公司，希望不被收录，请',
          h('a', { href: REPO + '/issues', target: '_blank', rel: 'noopener noreferrer' }, '提 issue'), '，我们会尽快处理。')))));
}

/* 路由 */

const ROUTES = [
  { path: '', label: '岗位', view: viewHome },
  { path: 'foryou', label: '推荐', view: viewForYou, needsData: true },
  { path: 'tracker', label: '投递', view: viewTracker },
  { path: 'agent', label: '接入 Agent', view: viewAgent, needsData: true },
];

function route() {
  closeSheet();
  const name = location.hash.replace(/^#\/?/, '').split('?')[0];
  const r = ROUTES.find((x) => x.path === name) || ROUTES[0];
  document.querySelectorAll('#nav a').forEach((a) => (a.getAttribute('href') === '#/' + r.path ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current')));
  if (r.path === '') state.filters = readUrl();
  const view = $('view');
  if (r.needsData && !state.db) {
    view.replaceChildren(h('div', { class: 'wrap page' }, h('div', { class: 'skeleton' }, h('i'), h('i'), h('i'))));
    return;
  }
  const paint = () => { r.view(view); view.focus({ preventScroll: true }); };
  paint();
  scrollTo(0, 0);
}

// 岗位详情里改了投递状态后，刷新列表上的标记
function refresh() {
  const name = location.hash.replace(/^#\/?/, '').split('?')[0];
  if (name === '') renderResults();
  if (name === 'foryou') viewForYou($('view'));
  if (name === 'tracker') viewTracker($('view'));
}

async function load() {
  try {
    const resp = await fetch('data/jobs.json');
    if (!resp.ok) throw new Error(String(resp.status));
    state.db = hydrate(await resp.json());
    // 数据到达时用户可能已经在搜索框里打字，重绘后把焦点和光标放回去
    const typing = document.activeElement?.id === 'q' ? document.activeElement.selectionStart : null;
    route();
    if (typing != null) $('q').setSelectionRange(typing, typing, $('q').focus());
  } catch {
    $('view').replaceChildren(h('div', { class: 'wrap empty' },
      h('h2', null, '岗位数据没有加载出来'),
      h('p', null, '可能是网络不稳定，等一下再试。'),
      h('button', { class: 'btn', onclick: () => { $('view').replaceChildren(); load(); } }, '重试')));
  }
}

function boot() {
  $('nav').replaceChildren(...ROUTES.map((r) => h('a', { href: '#/' + r.path }, r.label)));
  window.addEventListener('hashchange', route);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSheet(); });
  new IntersectionObserver(([e]) => $('top').classList.toggle('stuck', !e.isIntersecting)).observe($('sentinel'));
  route();
  load();
}

boot();
