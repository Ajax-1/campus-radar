# campus-radar 校招雷达

国内校招岗位的数据层：每天从各公司**校招官网**采集岗位，统一成一个岗位库，再通过 **MCP** 开放给任意 Agent（Claude、Grok Bot、Muse 等）使用。

为什么做成「被 Agent 调用的一层」而不是又一个 Agent：通用 Agent 每次现上网搜，校招信息会漏、会重、会过期；它们缺的是一份准确、持续更新的国内校招数据。这个项目只做这一件事，并把它做准。

## 当前覆盖

14 个数据源，约 1.3 万个在招岗位（2026-10-25）。

**飞书招聘通用适配器**（`FeishuAtsCollector`，在 `application.yml` 的 `radar.feishu-sites` 里加一行即可接入新公司）：

| 公司 | 编码 | 站点 |
| --- | --- | --- |
| 字节跳动 | `bytedance` | jobs.bytedance.com（同一套系统，约 7000 个岗位） |
| 小鹏汽车 | `xiaopeng` | xiaopeng.jobs.feishu.cn |
| 蔚来 | `nio` | nio.jobs.feishu.cn |
| 中科创达 | `thundersoft` | thundersoft.jobs.feishu.cn |
| 影石Insta360 | `insta360` | arashivision.jobs.feishu.cn |
| 面壁智能 | `modelbest` | modelbest.jobs.feishu.cn |
| 德赛西威 | `desaysv` | yesv-desaysv.jobs.feishu.cn（不返回招聘类型，按标题判断实习） |

**官网专用采集器**：

| 数据源 | 编码 | 采集方式 | 说明 |
| --- | --- | --- | --- |
| 阿里巴巴 | `alibaba` | XSRF cookie + `listBatch` 动态发现当届批次 | 应届生 + 实习批次 |
| 腾讯 | `tencent` | 列表 + 逐个拉详情（只拉新岗位） | 列表接口不带 JD |
| 美团 | `meituan` | `jobType` 1 校招 / 2 实习 | |
| 快手 | `kuaishou` | `recruitSubProjectCodes` 传空数组返回全部项目 | |
| 百度 | `baidu` | 表单 POST，每页最多 20 | 岗位名去掉城市前缀和编号 |
| 京东 | `jd` | `pageIndex` 从 0 开始 | 城市在 `requirementVoList` 里 |
| 拼多多 | `pdd` | 服务端固定每页 10 条 | 只有应届生页，实习页待接 |

待接入：网易（校招站需要先选项目）、华为（WAF 拦截裸请求，需要浏览器）、Moka（接口响应加密，只读渲染后的页面）、拼多多和京东的实习页。

## 快速开始

需要 Java 21+ 和 Maven。

```bash
mvn -DskipTests package
java -jar target/campus-radar-0.1.0-SNAPSHOT.jar
```

服务默认监听 `8090`，数据存在 `./data/`（H2 文件库）。每天北京时间 7 点自动采集；也可以手动触发（只允许本机调用）：

```bash
# 采集单个数据源（同步返回本次结果）
curl -X POST http://127.0.0.1:8090/api/admin/collect/bytedance
# 后台采集全部数据源
curl -X POST http://127.0.0.1:8090/api/admin/collect
```

## 接入 Agent

MCP 端点：`http://<host>:8090/mcp`，传输方式 Streamable HTTP。

| 工具 | 作用 |
| --- | --- |
| `search_jobs` | 按关键词、公司、城市、校招/实习、届别、最近 N 天检索在招岗位；有关键词按相关度排序，不限公司时各公司穿插，返回 JD 命中片段 |
| `get_job` | 查看单个岗位的完整 JD 和官网投递链接 |
| `list_sources` | 每家公司在招岗位数和最近一次采集时间 |
| `get_profile` / `update_profile` | 读写求职档案（届别、目标方向、技能、意向城市、招聘类型、偏好/排除公司、排除关键词）；部分更新，`[]` 清空 |
| `match_jobs` | 按档案给岗位打 0–100 分并给出每一分的理由；已在投递台账里的岗位不再推荐 |
| `record_application` / `update_application` / `list_applications` | 投递台账：记录投递、改状态、看每条的时间线 |
| `list_events` / `add_event` | 笔试、面试、测评日程（含测评截止时间） |
| `sync_mail` | 立即同步一次邮箱（平时每 15 分钟自动同步） |

公司名支持简称和英文名（字节、ByteDance、阿里、PDD 等）；没收录的公司会返回 `isError` 和已收录公司列表。

**Claude Code**

```bash
claude mcp add --transport http campus-radar http://localhost:8090/mcp
```

**云端 Agent（Grok Bot、Muse 等）**：它们跑在自己的云端机器上，访问不到你电脑的 `localhost`。需要先开启令牌，再用隧道暴露一个公网地址：

```bash
RADAR_MCP_TOKEN=换成一串足够长的随机字符 java -jar target/campus-radar-0.1.0-SNAPSHOT.jar
cloudflared tunnel --url http://localhost:8090
```

然后在 Agent 的 connector / MCP 设置里填 `https://<隧道地址>/mcp`，请求头 `Authorization: Bearer <令牌>`。

- 配置令牌后，`/mcp` 和 `/api/**` 都需要带令牌。
- `/api/admin/**`（手动触发采集）只接受直连本机的请求，经隧道或反向代理转发进来的请求一律 403，带令牌也不行。
- Homebrew 装不上 cloudflared 时，可以直接从 [cloudflared Releases](https://github.com/cloudflare/cloudflared/releases) 下载对应系统的二进制。

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

**企业微信每日推送**：群设置 → 群机器人 → 添加，复制 webhook 地址后启动：

```bash
RADAR_WECOM_WEBHOOK='https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...' java -jar target/campus-radar-0.1.0-SNAPSHOT.jar
```

每天北京时间 7:30（7 点采集之后）推送匹配度最高的 5 个岗位，同一家公司最多 2 个，推过的岗位不会再推。webhook 是单向的，在群里回复不会被收到，「选中岗位」请通过 Agent 或 REST 完成。手动操作（只允许本机直连）：

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
java -jar target/campus-radar-0.1.0-SNAPSHOT.jar
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

## 路线图

- **P0（当前）**：官网采集、统一岗位库、只读 MCP
- **P1**：飞书招聘通用适配器 ✅；个人档案与 `match_jobs` 打分 ✅；企业微信每日推送 ✅；华为 / Moka 浏览器采集（待做）
- **P2**：投递台账 ✅；邮件识别笔试/面试/Offer/拒信并自动更新进度 ✅；日历订阅 + 提醒 ✅；网申插件（填表 + 记录投递）✅；按 JD 定制简历（起草 + 事实校验，待做）
- **P3**：浏览器插件填网申表、海外数据源
