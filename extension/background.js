// 和校招雷达服务端通信放在后台：内容脚本的跨域请求受页面 CORS 限制，后台带 host_permissions 不受影响。
'use strict';

const DEFAULT_SERVER = 'http://127.0.0.1:8090';

async function settings() {
  const { server, token } = await chrome.storage.local.get(['server', 'token']);
  return { server: (server || DEFAULT_SERVER).replace(/\/+$/, ''), token: token || '' };
}

async function api(path, options) {
  const { server, token } = await settings();
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  const resp = await fetch(server + path, { ...options, headers });
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(body.error || body.message || ('HTTP ' + resp.status));
  return body;
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg) return false;
  if (msg.type === 'record') {
    api('/api/applications', {
      method: 'POST',
      body: JSON.stringify({ company: msg.company, title: msg.title, applyUrl: msg.url, source: 'EXTENSION' })
    }).then(app => sendResponse({ ok: true, app }), e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
  if (msg.type === 'ping') {
    api('/api/sources', { method: 'GET' })
      .then(rows => sendResponse({ ok: true, sources: rows.length }), e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
  if (msg.type === 'applications') {
    api('/api/applications', { method: 'GET' })
      .then(rows => sendResponse({ ok: true, rows }), e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
  return false;
});
