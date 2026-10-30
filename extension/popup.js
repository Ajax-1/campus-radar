'use strict';

const $ = (sel) => document.querySelector(sel);
const DEFAULT_SERVER = 'http://127.0.0.1:8090';

// 标签页切换
document.querySelectorAll('header button').forEach(b => b.addEventListener('click', () => {
  document.querySelectorAll('header button, section').forEach(x => x.classList.remove('on'));
  b.classList.add('on');
  $('#' + b.dataset.tab).classList.add('on');
  if (b.dataset.tab === 'apps') loadApps();
}));

// 我的信息：只存 chrome.storage.local
async function loadProfile() {
  const { profile } = await chrome.storage.local.get('profile');
  document.querySelectorAll('#profile [name]').forEach(el => { el.value = (profile && profile[el.name]) || ''; });
}

$('#save-profile').addEventListener('click', async () => {
  const profile = {};
  document.querySelectorAll('#profile [name]').forEach(el => { if (el.value.trim()) profile[el.name] = el.value.trim(); });
  for (const k of ['eduStart', 'eduEnd']) {
    if (profile[k] && !/^\d{4}-\d{2}$/.test(profile[k])) {
      $('#profile-status').textContent = '入学和毕业时间请写成 2023-09 这样的格式';
      return;
    }
  }
  await chrome.storage.local.set({ profile });
  $('#profile-status').textContent = '已保存 ' + Object.keys(profile).length + ' 项';
});

// 填写当前页面：内置站点已经有内容脚本；其他站点按需注入（activeTab 权限，只在用户点击时生效）
async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

async function ensureInjected(tab) {
  try {
    await chrome.tabs.sendMessage(tab.id, { type: 'noop' });
  } catch (e) {
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['fill.js', 'content.js'] });
  }
}

$('#fill-now').addEventListener('click', async () => {
  const tab = await activeTab();
  try {
    await ensureInjected(tab);
    await chrome.tabs.sendMessage(tab.id, { type: 'fill-now' });
    $('#fill-status').textContent = '已在页面上填写，结果见页面右下角提示';
  } catch (e) {
    $('#fill-status').textContent = '这个页面不能填写：' + e.message;
  }
});

$('#record-now').addEventListener('click', async () => {
  const tab = await activeTab();
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    files: ['fill.js']
  }).then(() => chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: () => window.CampusRadarFill.guessJob(document, location.hostname)
  }));
  const company = prompt('公司', result.company || '');
  if (!company) return;
  const title = prompt('岗位', result.title || '');
  const resp = await chrome.runtime.sendMessage({ type: 'record', company, title, url: tab.url });
  $('#fill-status').textContent = resp.ok ? '已记入投递台账' : '记录失败：' + resp.error;
});

// 投递列表
async function loadApps() {
  const resp = await chrome.runtime.sendMessage({ type: 'applications' });
  const box = $('#apps-list');
  if (!resp.ok) {
    box.textContent = '读取失败：' + resp.error + '（检查设置里的服务地址和令牌）';
    return;
  }
  if (resp.rows.length === 0) {
    box.textContent = '还没有投递记录';
    return;
  }
  box.textContent = '';
  for (const a of resp.rows.slice(0, 30)) {
    const div = document.createElement('div');
    div.className = 'app';
    const b = document.createElement('b');
    b.textContent = a.company + (a.title ? ' · ' + a.title : '');
    const s = document.createElement('span');
    s.textContent = '  ' + a.status;
    div.append(b, s);
    box.appendChild(div);
  }
}

// 设置：服务地址不是本机时需要用户授权访问该域名
async function loadSettings() {
  const { server, token } = await chrome.storage.local.get(['server', 'token']);
  $('#server').value = server || DEFAULT_SERVER;
  $('#token').value = token || '';
}

$('#save-settings').addEventListener('click', async () => {
  const server = ($('#server').value.trim() || DEFAULT_SERVER).replace(/\/+$/, '');
  let origin;
  try {
    origin = new URL(server).origin;
  } catch (e) {
    $('#settings-status').textContent = '服务地址格式不对';
    return;
  }
  if (!/^http:\/\/(127\.0\.0\.1|localhost):8090$/.test(origin)) {
    const granted = await chrome.permissions.request({ origins: [origin + '/*'] });
    if (!granted) {
      $('#settings-status').textContent = '没有获得访问 ' + origin + ' 的权限';
      return;
    }
  }
  await chrome.storage.local.set({ server, token: $('#token').value.trim() });
  $('#settings-status').textContent = '已保存';
});

$('#ping').addEventListener('click', async () => {
  const resp = await chrome.runtime.sendMessage({ type: 'ping' });
  $('#settings-status').textContent = resp.ok ? '连接正常，收录 ' + resp.sources + ' 个数据源' : '连接失败：' + resp.error;
});

loadProfile();
loadSettings();
