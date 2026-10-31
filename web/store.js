// 档案和投递记录只存在这个浏览器里，不会发给任何服务器。
// 清除浏览器数据就没了，所以提供导出和导入。

const PROFILE = 'radar-profile';
const APPS = 'radar-applications';

export const STAGES = ['想投', '已投递', '笔试/测评', '面试中', 'Offer', '未通过', '已放弃'];

// 隐私模式下 localStorage 可能不可用，退回内存，页面照常工作，只是刷新后不保留
const memory = new Map();

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return memory.has(key) ? memory.get(key) : fallback;
  }
}

function write(key, value) {
  memory.set(key, value);
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function persistent() {
  try {
    localStorage.setItem('radar-probe', '1');
    localStorage.removeItem('radar-probe');
    return true;
  } catch {
    return false;
  }
}

export const getProfile = () => read(PROFILE, {});
export const saveProfile = (p) => write(PROFILE, p);
export const listApps = () => read(APPS, []);

export function trackedJobs() {
  return new Map(listApps().filter((a) => a.jobId).map((a) => [a.jobId, a]));
}

export function addApp({ jobId = null, company, title, applyUrl = null, status }) {
  const apps = listApps();
  const existing = jobId && apps.find((a) => a.jobId === jobId);
  if (existing) {
    existing.status = status;
    existing.updatedAt = Date.now();
  } else {
    const now = Date.now();
    apps.unshift({ id: now + '-' + apps.length, jobId, company, title, applyUrl, status, createdAt: now, updatedAt: now });
  }
  write(APPS, apps);
}

export function setStatus(id, status) {
  write(APPS, listApps().map((a) => (a.id === id ? { ...a, status, updatedAt: Date.now() } : a)));
}

export function removeApp(id) {
  write(APPS, listApps().filter((a) => a.id !== id));
}

export const exportAll = () => ({ app: 'campus-radar', version: 1, exportedAt: new Date().toISOString(), profile: getProfile(), applications: listApps() });

export function importAll(data) {
  if (data?.app !== 'campus-radar' || !Array.isArray(data.applications)) throw new Error('这不是校招雷达导出的文件');
  const text = (v) => (typeof v === 'string' ? v : null);
  const apps = data.applications
    .filter((a) => text(a?.id) && text(a.company) && text(a.title) && STAGES.includes(a.status))
    .map((a) => ({
      id: a.id,
      jobId: text(a.jobId),
      company: a.company,
      title: a.title,
      applyUrl: text(a.applyUrl),
      status: a.status,
      createdAt: Number(a.createdAt) || Date.now(),
      updatedAt: Number(a.updatedAt) || Date.now(),
    }));
  write(APPS, apps);
  write(PROFILE, data.profile && typeof data.profile === 'object' ? data.profile : {});
  return apps.length;
}
