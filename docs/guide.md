# 使用指南

以 jar 方式运行时，配置通过环境变量传入；Docker 写在 `docker-compose.yml` 的 `environment` 里；用安装脚本的写在 `~/.campus-radar/env` 里。

## 匹配与每日推送

`match_jobs` 是规则式打分，不调用大模型，每一分都能说清来由：

| 维度 | 分值 | 规则 |
| --- | --- | --- |
| 岗位方向 | 40 | 目标方向命中标题 30，每多命中一个 +5；只命中职能类别 20；只在 JD 里 10 |
| 技能 | 30 | 档案技能在标题和 JD 里出现的比例 × 30；英文按词匹配（Java 不会命中 JavaScript，Go 不会命中 Google），Go/Golang、K8s/Kubernetes 等同义写法互认 |
| 城市 | 10 | 第一意向城市 10，其他意向城市 7，岗位没写城市 5 |
| 公司 | 10 | 偏好公司 |
| 新鲜度 | 10 | 3 天内 10，7 天内 6，14 天内 3 |

硬过滤：招聘类型、届别（没标届别的保留）、排除公司、标题排除词、意向城市（没写城市的保留）、已在投递台账里的岗位。打分覆盖全部在招岗位（分页扫描，没有候选数上限）。

**每日推送**：配哪个就推到哪个，可以同时配多个。

| 渠道 | 环境变量 | 获取方式 |
| --- | --- | --- |
| 个人微信（Server酱） | `RADAR_SERVERCHAN_KEY` | 微信扫码登录 [sct.ftqq.com](https://sct.ftqq.com)，复制 SendKey |
| 企业微信群 | `RADAR_WECOM_WEBHOOK` | 群设置 → 群机器人 → 添加，复制 webhook 地址 |
| 飞书群 | `RADAR_FEISHU_WEBHOOK` | 群设置 → 群机器人 → 添加自定义机器人，复制 webhook 地址 |

每天北京时间 7:30 推送匹配度最高的 5 个岗位，同一家公司最多 2 个，推过的岗位不会再推。电脑在那个时间没开机也没关系，开机后当天会补推。这些渠道都是单向的，「选中岗位」请在网页或 Agent 里完成。

手动操作（只允许本机直连）：

```bash
curl -X POST http://127.0.0.1:8090/api/admin/push/preview   # 预览，不发送
curl -X POST http://127.0.0.1:8090/api/admin/push           # 立即推送一次
```

## 投递闭环：邮箱 → 台账 → 日历 → 提醒

投递之后的事交给它：收到笔试、面试、测评邀请时自动更新投递状态、加进日历、开始前提醒；收到拒信或 Offer 也会更新状态。

```
官网投递（插件一键记录 / 让 Agent 记录）
   → 邮箱收到邀请（每 15 分钟同步，只读）
   → 识别公司、类型、时间、会议链接：台账「已投递 → 面试中」，日历新增日程
   → 企业微信推送汇总；开始前 1 天、1 小时再提醒；日历 App 也会提醒
```

**1. 接邮箱**（IMAP 只读：不标记已读、不移动、不删除；只识别求职相关邮件）

| 邮箱 | RADAR_MAIL_HOST | 密码 |
| --- | --- | --- |
| QQ 邮箱 | imap.qq.com | 设置 → 账户 → 开启 IMAP 后生成的「授权码」 |
| 163 邮箱 | imap.163.com | 设置 → POP3/SMTP/IMAP → 「授权码」 |
| Gmail | imap.gmail.com | Google 账号 → 安全 → 两步验证 → 「应用专用密码」 |
| 学校 / Outlook | 见邮箱服务的 IMAP 设置 | 多数需要应用专用密码 |

```bash
RADAR_MAIL_HOST=imap.qq.com RADAR_MAIL_USERNAME=你的邮箱 RADAR_MAIL_PASSWORD=授权码 \
RADAR_DISPLAY_ZONE=America/New_York \
java -jar target/campus-radar.jar
```

`RADAR_DISPLAY_ZONE` 是给人在海外的：设置后所有日程同时显示北京时间和当地时间，如「10月28日 周三 19:00（当地 07:00）」。邮件里没写时区的按北京时间理解，写了 ET / PT / 北京时间的按写明的。

能识别的写法：「2026年10月28日（周三）19:00-20:00」「10月30日 下午3点 - 5点」「请于11月2日 23:59前完成」「10月31日前完成测评」「October 29, 2026 at 2:00 PM ET」、全角数字、多个候选时间；识别不出公司的邀请也会以「未知公司」进日历，宁可标签不准也不漏面试。营销邮件（面试技巧、真题、直播课）会被忽略。识别规则见 `InviteParser`，误判时在 `InviteParserTest` 加一个用例再改规则。

**2. 订阅日历**：在苹果日历 / Google 日历 / Outlook 里「添加订阅日历」，地址填

```
http://127.0.0.1:8090/api/calendar.ics?token=<RADAR_MCP_TOKEN>
```

手机日历要订阅的话，服务需要能从外网访问（同上面的隧道）。每个日程带提前 1 天和 1 小时两个提醒。

**3. 浏览器插件**（`extension/`）：Chrome 打开 `chrome://extensions` → 打开「开发者模式」→「加载已解压的扩展程序」→ 选 `extension` 目录。

- 「我的信息」里填一次姓名、手机、学校、专业、毕业时间等，**只存在本机浏览器**，不上传服务器。
- 在网申表单页点右下角「校招雷达：一键填写」：只填空着的字段，组件库下拉框会提示你手动选，**从不替你提交**。
- 提交后点「记录已投递」，这次投递就进了台账，之后的邮件会接着更新它。
- 内置支持飞书招聘、字节、Moka、北森、腾讯、阿里、美团、百度、京东、快手、拼多多的页面；其他网站在插件弹窗里点「填写当前页面」。
- 填表逻辑的测试：`cd extension-tests && npm install && npm test`。

手动操作（只允许本机直连）：

```bash
curl -X POST http://127.0.0.1:8090/api/admin/mail/sync   # 立即同步邮箱
curl -X POST http://127.0.0.1:8090/api/admin/remind      # 立即检查提醒
```

## REST 接口

- `GET /api/jobs?keyword=&company=&city=&recruitType=校招&gradYear=&sinceDays=&limit=20`
- `GET /api/jobs/{id}`
- `GET /api/sources`
- `GET /api/profile`，`PUT /api/profile`（部分更新）
- `GET /api/match?limit=10&maxPerCompany=3`
- `GET /api/applications`，`POST /api/applications`，`PUT /api/applications/{id}`
- `GET /api/events?days=14`，`POST /api/events`，`POST /api/events/{id}/cancel`
- `GET /api/calendar.ics`（日历订阅，令牌可以放在 `?token=`）

## 设计要点

- **增量采集**：岗位按 `(source, externalId)` 唯一；内容摘要没变只刷新 `lastSeenAt`；本轮没再出现的岗位标记为 `CLOSED`，不删除。
- **健康检查**：本轮条数低于上次成功采集的一半时，标记 `WARN` 并**跳过下线**，避免接口改版或风控导致整批岗位被误下线。
- **只对新岗位拉详情**：腾讯这类列表不带 JD 的来源，已有 JD 的岗位沿用库里内容，每天只多请求新增的那部分。
- **解析与请求分离**：每个采集器的解析方法用录制的真实响应测试（`src/test/resources/fixtures`），接口改版时先更新 fixture 再看哪条断言失败。

## 采集边界

- 只采公司校招官网公开的岗位，低频请求（同一来源请求间隔 ≥ 400ms，每天一轮），保留原文链接，引导用户回官网投递。
- 不破解加密接口，不绕过验证码和风控。
- 不搬运牛客等聚合平台整理的内容；它们只作为「哪家开招了」的线索来源。

## 自己部署公开实例

想要带 JD 全文的公开实例，可以自己部署 `hosted` 模式，注意这会公开 JD 全文。它把服务变成多人共用的只读岗位库：MCP 只保留 `search_jobs`、`get_job`、`list_sources`，REST 只保留岗位接口，其余一律 404，并按来源 IP 限流。

```bash
SPRING_PROFILES_ACTIVE=hosted RADAR_BIND_ADDRESS=0.0.0.0 java -jar campus-radar.jar
```

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `RADAR_RATE_PER_MINUTE` | 60 | 单个来源 IP 每分钟的请求上限 |
| `RADAR_TOTAL_RATE_PER_MINUTE` | 3000 | 全局每分钟的请求上限 |

放在 Cloudflare 或反向代理后面时，限流按 `CF-Connecting-IP` 或 `X-Forwarded-For` 识别来源，这两个请求头要由代理设置，不要让服务直接暴露在公网。

## 岗位快照

默认每个实例自己请求官网。用户多了以后，这样既慢又会给对方服务器添负担，所以支持一处采集、所有实例共用：

```bash
# 采集方：采集一轮，写出快照后退出
java -jar campus-radar.jar --server.port=0 --radar.snapshot.export-path=out/jobs.json.gz

# 使用方：不再请求官网，启动几秒就有数据
RADAR_SNAPSHOT_URL=https://example.com/jobs.json.gz java -jar campus-radar.jar
```

快照只有公开的岗位信息，不含档案、台账、日程和邮件，但包含 JD 全文，发布前请确认合规风险。

## 网页版和远程 MCP

不用安装的入口，都由 `Snapshot` 工作流生成：

- **网页版**（`web/`）：静态页面，部署在 GitHub Pages。岗位索引下载到浏览器，搜索和打分都在本地完成。档案和投递记录存在 `localStorage`，可以导出和导入
- **远程 MCP**（`worker/`）：Cloudflare Worker，只有 `search_jobs`、`get_job`、`list_sources` 三个工具，无状态，不含任何个人数据

两者共用 `web/search.js`，数据来自 `scripts/build-index.mjs`。它把全量快照转成公开索引，只保留标题、公司、城市、届别、官网链接和从 JD 提取的技能标签，**不含 JD 原文**。全量快照只留在工作流的运行环境里。

```bash
# 本地试跑：先导出快照，再生成网页和 Worker 用的索引
java -jar target/campus-radar.jar --server.port=0 --radar.snapshot.export-path=out/jobs.json.gz
node scripts/build-index.mjs out/jobs.json.gz
python3 -m http.server -d dist/site   # 网页版
cd worker && npm install && npm run dev   # 远程 MCP，地址 http://127.0.0.1:8787/mcp
```

部署需要在仓库里配置：

| 配置 | 类型 | 说明 |
| --- | --- | --- |
| Settings → Pages → Source | 选 GitHub Actions | 开启网页版 |
| `PUBLIC_MCP_URL` | Variable | Worker 的 `/mcp` 地址。没设置时跳过 Worker，网页上也不显示 MCP 命令 |
| `CLOUDFLARE_ACCOUNT_ID` | Variable | Cloudflare 账号 ID |
| `CLOUDFLARE_API_TOKEN` | Secret | 需要 Workers Scripts 的编辑权限 |

手动运行工作流时默认只试跑，勾选 publish 才发布。构建时岗位少于 1000 个会失败，避免用残缺数据覆盖线上。

## 配置一览

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `RADAR_PORT` | 8090 | 监听端口 |
| `RADAR_BIND_ADDRESS` | 127.0.0.1 | 监听地址。改成其他地址时必须同时设置令牌 |
| `RADAR_MCP_TOKEN` | 空 | 访问令牌。没设置时只响应本机地址的请求 |
| `RADAR_DISPLAY_ZONE` | 空 | 人在海外时设置，日程同时显示当地时间 |
| `RADAR_COLLECT_WHEN_EMPTY` | true | 岗位库为空时启动即采集 |
| `RADAR_COLLECT_MAX_AGE_HOURS` | 26 | 数据超过这么久没更新就补采，0 表示关闭 |
