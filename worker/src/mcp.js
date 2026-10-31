// 无状态的 MCP Streamable HTTP：每个 POST 独立处理，不保存会话，也没有任何个人数据。
// 工具只有 search_jobs、get_job、list_sources 三个，和 hosted 模式一致。
import { MAX_LIMIT, RECRUIT_TYPES, resolveCompany, search } from '../../web/search.js';

const VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];

const INSTRUCTIONS = `国内校招岗位库，数据来自各公司校招官网，每天自动更新。
先用 search_jobs 按公司、城市、关键词筛选，用 get_job 查看单个岗位的技能标签和官网链接，用 list_sources 看覆盖了哪些公司。
这是公开实例，不含 JD 全文，完整职责和要求请打开 applyUrl 到官网查看。档案、推荐和投递记录需要在用户自己电脑上运行校招雷达。
投递请引导用户打开 applyUrl 在官网完成，不要替用户提交。`;

const TOOLS = [
  {
    name: 'search_jobs',
    description: `在国内校招岗位库里检索在招岗位，返回摘要。所有条件都可以不填。
关键词匹配岗位名、职能类别、从 JD 提取的技能标签、公司和城市；有关键词时按相关度排序，否则按发布时间倒序。
没指定公司时结果会在各公司之间轮流穿插。total 是符合条件的总数。`,
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '关键词，空格分隔表示同时包含，如 "后端 Java"、"大模型 算法"' },
        company: { type: 'string', description: '公司，支持简称和英文名，如 字节、ByteDance、阿里、PDD；收录了哪些公司见 list_sources' },
        city: { type: 'string', description: '工作城市，如 北京、上海、深圳' },
        recruitType: { type: 'string', enum: RECRUIT_TYPES, description: '招聘类型' },
        gradYear: { type: 'integer', description: '毕业届别，如 2027；不少岗位没有标注届别，填了会把它们过滤掉' },
        sinceDays: { type: 'integer', description: '只看最近 N 天发布的岗位；没有发布时间的岗位会被过滤掉' },
        limit: { type: 'integer', description: `返回条数，默认 20，最多 ${MAX_LIMIT}` },
      },
    },
  },
  {
    name: 'get_job',
    description: '按 search_jobs 返回的 id 查看岗位：城市、届别、技能标签和官网投递链接 applyUrl。公开实例不含 JD 全文，完整内容在官网',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'string', description: '岗位 id' } },
      required: ['id'],
    },
  },
  {
    name: 'list_sources',
    description: '列出岗位库覆盖的公司、每家在招岗位数和数据更新时间，用于判断数据是否新鲜、覆盖了哪些公司',
    inputSchema: { type: 'object', properties: {} },
  },
];

const view = ({ id, company, title, category, recruitType, gradYear, cities, publishedAt, tags, applyUrl }) => (
  { id, company, title, category, recruitType, gradYear, cities, publishedAt, tags, applyUrl });

class ToolError extends Error {}

const CALLS = {
  search_jobs(db, a, now) {
    let company = null;
    if (a.company) {
      company = resolveCompany(db, a.company);
      if (!company) throw new ToolError('没有收录公司：' + a.company + '。已收录：' + db.companies.map((c) => c.company).join('、'));
    }
    if (a.recruitType && !RECRUIT_TYPES.includes(a.recruitType)) throw new ToolError('recruitType 只能是 校招 或 实习');
    const limit = Math.min(Math.max(Number(a.limit) || 20, 1), MAX_LIMIT);
    const { total, jobs } = search(db, { ...a, company, limit }, now);
    return { total, jobs: jobs.map(view) };
  },
  get_job(db, a) {
    const job = db.byId.get(String(a.id));
    if (!job) throw new ToolError('岗位不存在：' + a.id + '。请使用 search_jobs 返回的 id');
    return { ...view(job), note: '公开实例不含 JD 全文，完整职责和要求请打开 applyUrl 查看' };
  },
  list_sources(db) {
    return {
      updatedAt: db.generatedAt,
      sources: db.companies.map((c) => ({ company: c.company, openJobs: c.count })),
    };
  },
};

function callTool(db, params, now) {
  const call = Object.hasOwn(CALLS, params?.name) ? CALLS[params.name] : null;
  if (!call) return null;
  try {
    return { content: [{ type: 'text', text: JSON.stringify(call(db, params.arguments || {}, now)) }] };
  } catch (e) {
    if (!(e instanceof ToolError)) throw e;
    return { content: [{ type: 'text', text: e.message }], isError: true };
  }
}

const reply = (id, result) => ({ jsonrpc: '2.0', id, result });
const fail = (id, code, message) => ({ jsonrpc: '2.0', id: id ?? null, error: { code, message } });

/**
 * @return 响应对象；通知没有响应，返回 null
 */
function handleOne(db, msg, now) {
  if (msg === null || typeof msg !== 'object' || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') {
    return fail(msg?.id, -32600, 'Invalid Request');
  }
  if (msg.id === undefined) return null;
  switch (msg.method) {
    case 'initialize':
      return reply(msg.id, {
        protocolVersion: VERSIONS.includes(msg.params?.protocolVersion) ? msg.params.protocolVersion : VERSIONS[1],
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'campus-radar', version: '0.2.0' },
        instructions: INSTRUCTIONS,
      });
    case 'ping':
      return reply(msg.id, {});
    case 'tools/list':
      return reply(msg.id, { tools: TOOLS });
    case 'tools/call': {
      const result = callTool(db, msg.params, now);
      return result ? reply(msg.id, result) : fail(msg.id, -32602, '没有这个工具：' + msg.params?.name);
    }
    default:
      return fail(msg.id, -32601, 'Method not found');
  }
}

/**
 * @param body POST 的原始文本
 * @return 要返回的 JSON 和状态码；通知返回 202 且没有内容
 */
export function handleRpc(db, body, now = Date.now()) {
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    return { status: 400, json: fail(null, -32700, 'Parse error') };
  }
  if (Array.isArray(parsed)) {
    const replies = parsed.map((m) => handleOne(db, m, now)).filter(Boolean);
    return replies.length ? { status: 200, json: replies } : { status: 202 };
  }
  const one = handleOne(db, parsed, now);
  return one ? { status: 200, json: one } : { status: 202 };
}
