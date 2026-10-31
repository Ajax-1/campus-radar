// 把全量快照（含 JD 全文）转成公开索引，生成网页和 Worker 要发布的文件。
// 公开索引只有标题、公司、城市、届别、官网链接和从 JD 提取的技能标签，不含 JD 原文。
//
//   node scripts/build-index.mjs out/jobs.json.gz
//   PUBLIC_MCP_URL=https://example.com/mcp node scripts/build-index.mjs out/jobs.json.gz
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

// 新增公司时这里也要加一行，漏了构建会失败。全称和别名与各 Collector、application.yml 一致
export const COMPANIES = {
  bytedance: ['字节跳动', '字节', 'ByteDance', '抖音', 'TikTok', '今日头条'],
  alibaba: ['阿里巴巴', '阿里', 'Alibaba', '淘天', '淘宝', '天猫', '阿里云', '通义', '千问'],
  tencent: ['腾讯', 'Tencent', '鹅厂', '微信', 'WXG', 'IEG', 'PCG', 'CSIG', 'TEG', 'CDG'],
  meituan: ['美团', 'Meituan'],
  kuaishou: ['快手', 'Kuaishou'],
  baidu: ['百度', 'Baidu'],
  jd: ['京东', 'JD', 'Jingdong'],
  pdd: ['拼多多', 'PDD', 'Pinduoduo', 'Temu'],
  xiaopeng: ['小鹏汽车', '小鹏', 'XPeng', '小鹏集团'],
  nio: ['蔚来', 'NIO', '蔚来汽车'],
  momenta: ['Momenta', '魔门塔'],
  thundersoft: ['中科创达', 'ThunderSoft'],
  insta360: ['影石Insta360', '影石', 'Insta360', '影石创新'],
  modelbest: ['面壁智能', '面壁', 'ModelBest'],
  desaysv: ['德赛西威', 'Desay SV', '德赛'],
  bambulab: ['拓竹科技', '拓竹', 'Bambu Lab', 'BambuLab'],
  papergames: ['叠纸游戏', '叠纸', 'Paper Games', 'PaperGames'],
};

// 技能词表：标签名 -> 在 JD 里的写法。只收技术词，「产品」「设计」这类词在 JD 里太常见，靠标题和职能类别匹配
const SKILLS = {
  Java: ['java'], Python: ['python'], Go: ['golang', 'go语言', 'go'], 'C++': ['c++', 'cpp'], C: ['c语言'], Rust: ['rust'],
  JavaScript: ['javascript', 'js'], TypeScript: ['typescript'], Kotlin: ['kotlin'], Swift: ['swift'], Scala: ['scala'],
  PHP: ['php'], Lua: ['lua'], SQL: ['sql'], Shell: ['shell'],
  React: ['react'], Vue: ['vue'], 'Node.js': ['node.js', 'nodejs'], Flutter: ['flutter'], Android: ['android', '安卓'], iOS: ['ios'],
  Spring: ['spring'], MySQL: ['mysql'], PostgreSQL: ['postgresql', 'postgres'], Redis: ['redis'], MongoDB: ['mongodb'],
  Elasticsearch: ['elasticsearch'], Kafka: ['kafka'], Docker: ['docker'], Kubernetes: ['kubernetes', 'k8s'], Linux: ['linux'],
  Spark: ['spark'], Hadoop: ['hadoop'], Flink: ['flink'], Hive: ['hive'],
  PyTorch: ['pytorch'], TensorFlow: ['tensorflow'], Transformer: ['transformer'], RAG: ['rag'], Agent: ['agent'],
  大模型: ['大模型', 'llm'], 机器学习: ['机器学习', 'machine learning'], 深度学习: ['深度学习', 'deep learning'],
  强化学习: ['强化学习'], 计算机视觉: ['计算机视觉', 'cv'], NLP: ['nlp', '自然语言处理'], 推荐系统: ['推荐系统', '推荐算法'],
  多模态: ['多模态'], AIGC: ['aigc'], 搜索: ['搜索算法', '搜索引擎'], 分布式: ['分布式'], 微服务: ['微服务'], 高并发: ['高并发'],
  数据库: ['数据库'], 操作系统: ['操作系统'], 编译器: ['编译器'], 嵌入式: ['嵌入式'], 自动驾驶: ['自动驾驶'], 机器人: ['机器人'],
  SLAM: ['slam'], ROS: ['ros'], FPGA: ['fpga'], Verilog: ['verilog'], 芯片: ['芯片'], 单片机: ['单片机'], 'Unity': ['unity'], 'Unreal': ['unreal', 'ue4', 'ue5'],
  数据分析: ['数据分析'], 数据挖掘: ['数据挖掘'], 自动化测试: ['自动化测试'],
};

const pattern = (term) => {
  const esc = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const left = /^[a-z0-9]/.test(term) ? '(?<![a-z0-9])' : '';
  // go-to-market 不算 Go
  const right = term === 'go' ? '(?![a-z0-9-])' : /[a-z0-9]$/.test(term) ? '(?![a-z0-9])' : '';
  return left + esc + right;
};
const MATCHERS = Object.entries(SKILLS).map(([tag, terms]) => [tag, new RegExp(terms.map(pattern).join('|'))]);

export const skillTags = (text) => MATCHERS.filter(([, re]) => re.test(text)).map(([tag]) => tag);

const MIN_JOBS = Number(process.env.MIN_JOBS || 1000);

/**
 * @param snapshot SnapshotFormat 写出的结构
 * @return 公开索引。岗位按数组存，比对象少一半体积
 */
export function buildIndex(snapshot, mcpUrl = null) {
  const sources = Object.keys(COMPANIES);
  const unknown = [...new Set(snapshot.jobs.map((j) => j.source))].filter((s) => !COMPANIES[s]);
  if (unknown.length) throw new Error('scripts/build-index.mjs 的 COMPANIES 里缺少：' + unknown.join(', '));

  const counts = new Map();
  for (const j of snapshot.jobs) counts.set(j.source, (counts.get(j.source) || 0) + 1);
  const companies = sources
    .filter((s) => counts.has(s))
    .sort((a, b) => counts.get(b) - counts.get(a))
    .map((source) => {
      const [company, ...aliases] = COMPANIES[source];
      return { source, company, aliases, count: counts.get(source) };
    });
  const companyIndex = new Map(companies.map((c, i) => [c.source, i]));

  const tags = Object.keys(SKILLS);
  const tagIndex = new Map(tags.map((t, i) => [t, i]));
  const jobs = snapshot.jobs.map((j) => {
    const text = [j.title, j.category, j.description, j.requirement].filter(Boolean).join('\n').toLowerCase();
    return [
      j.source + '-' + j.externalId,
      companyIndex.get(j.source),
      j.title,
      j.category || null,
      j.recruitType === 'INTERN' ? 1 : 0,
      j.gradYear ?? null,
      j.cities || [],
      j.applyUrl,
      j.publishedAt ? Math.floor(Date.parse(j.publishedAt) / 86400000) : null,
      skillTags(text).map((t) => tagIndex.get(t)),
    ];
  });
  return { version: 1, generatedAt: snapshot.generatedAt, mcpUrl, companies, tags, jobs };
}

function main([input]) {
  if (!input) throw new Error('用法：node scripts/build-index.mjs <jobs.json.gz>');
  const snapshot = JSON.parse(gunzipSync(readFileSync(input)).toString('utf8'));
  if (snapshot.jobs.length < MIN_JOBS) {
    throw new Error('只有 ' + snapshot.jobs.length + ' 个岗位，低于 ' + MIN_JOBS + '，不发布，避免用残缺数据覆盖线上');
  }
  const json = JSON.stringify(buildIndex(snapshot, process.env.PUBLIC_MCP_URL || null));

  const site = join(ROOT, 'dist/site');
  rmSync(site, { recursive: true, force: true });
  mkdirSync(join(site, 'data'), { recursive: true });
  for (const f of ['index.html', 'app.css', 'app.js', 'search.js', 'store.js']) cpSync(join(ROOT, 'web', f), join(site, f));
  const skill = readFileSync(join(ROOT, 'skills/campus-radar/SKILL.md'), 'utf8');
  writeFileSync(join(site, 'SKILL.md'), skill.replaceAll('{{PUBLIC_MCP_URL}}', process.env.PUBLIC_MCP_URL || '{{PUBLIC_MCP_URL}}'));
  writeFileSync(join(site, 'data/jobs.json'), json);

  mkdirSync(join(ROOT, 'worker/generated'), { recursive: true });
  writeFileSync(join(ROOT, 'worker/generated/index.txt'), json);

  console.log('岗位 ' + snapshot.jobs.length + ' 个，索引 ' + (json.length / 1e6).toFixed(1) + ' MB');
  const counts = new Map();
  for (const j of snapshot.jobs) counts.set(j.source, (counts.get(j.source) || 0) + 1);
  for (const s of Object.keys(COMPANIES)) console.log('  ' + s + ': ' + (counts.get(s) || '缺失'));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
