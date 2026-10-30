// 在网申页面右下角放两个按钮：「一键填写」和「记录已投递」。只在页面上有足够多的输入框时出现，避免干扰浏览岗位。
(function () {
  'use strict';
  if (window.__campusRadarInjected) return;
  window.__campusRadarInjected = true;

  const MIN_INPUTS = 3;

  function toast(text, ok) {
    const t = document.createElement('div');
    t.textContent = text;
    t.style.cssText = 'position:fixed;right:20px;bottom:120px;z-index:2147483647;max-width:360px;padding:10px 14px;'
      + 'border-radius:8px;font:13px/1.5 -apple-system,system-ui,sans-serif;color:#fff;white-space:pre-line;'
      + 'box-shadow:0 4px 16px rgba(0,0,0,.2);background:' + (ok ? '#1f7a3a' : '#a3361e');
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 6000);
  }

  function button(text, bottom, onClick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = text;
    b.style.cssText = 'position:fixed;right:20px;z-index:2147483646;padding:8px 14px;border:0;border-radius:18px;'
      + 'font:13px -apple-system,system-ui,sans-serif;cursor:pointer;color:#fff;background:#2563eb;'
      + 'box-shadow:0 2px 8px rgba(0,0,0,.2);bottom:' + bottom + 'px';
    b.addEventListener('click', onClick);
    return b;
  }

  async function fill() {
    const { profile } = await chrome.storage.local.get('profile');
    if (!profile || Object.keys(profile).length === 0) {
      toast('还没有填写个人信息：点浏览器右上角的校招雷达图标先填一次', false);
      return;
    }
    const r = window.CampusRadarFill.fillForm(document, profile);
    const lines = ['已填写 ' + r.filled.length + ' 项：' + r.filled.map(f => f.label).join('、')];
    if (r.keptExisting.length) lines.push('保留了你已填的 ' + r.keptExisting.length + ' 项');
    if (r.needsManual.length) lines.push('需要手动选择：' + r.needsManual.join('、'));
    lines.push('请核对后自己点提交');
    toast(lines.join('\n'), r.filled.length > 0);
  }

  async function record() {
    const job = window.CampusRadarFill.guessJob(document, location.hostname);
    const company = prompt('公司', job.company || '');
    if (!company) return;
    const title = prompt('岗位', job.title || '');
    const resp = await chrome.runtime.sendMessage({ type: 'record', company, title, url: location.href });
    toast(resp && resp.ok ? '已记入投递台账：' + company + (title ? ' · ' + title : '')
      : '记录失败：' + (resp && resp.error || '未知错误'), resp && resp.ok);
  }

  function mount() {
    if (document.getElementById('campus-radar-buttons')) return;
    const inputs = document.querySelectorAll('input:not([type=hidden]), textarea, select');
    if (inputs.length < MIN_INPUTS) return;
    const box = document.createElement('div');
    box.id = 'campus-radar-buttons';
    box.appendChild(button('校招雷达：一键填写', 64, fill));
    box.appendChild(button('记录已投递', 20, record));
    document.body.appendChild(box);
  }

  // 招聘站多是单页应用，表单晚加载；观察 DOM 变化，出现表单后再挂按钮
  mount();
  const observer = new MutationObserver(() => mount());
  observer.observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(() => observer.disconnect(), 120000);

  // 弹窗里点「填写当前页面」时走这里
  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg && msg.type === 'fill-now') {
      fill().then(() => sendResponse({ ok: true }));
      return true;
    }
    return false;
  });
})();
