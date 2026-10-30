# 贡献指南

## 加一家公司

**公司用飞书招聘**（校招官网地址形如 `xxx.jobs.feishu.cn/campus`）：在 `src/main/resources/application.yml` 的 `radar.feishu-sites` 里加一项。

```yaml
    - source: momenta          # 小写英文编码，全局唯一
      company: Momenta         # 展示用的公司名
      base-url: https://momenta.jobs.feishu.cn
      aliases: [魔门塔]         # 用户可能用的其他叫法
```

启动后验证：

```bash
./mvnw -DskipTests package && java -jar target/campus-radar.jar
curl -X POST http://127.0.0.1:8090/api/admin/collect/momenta
```

返回的 `status` 是 `SUCCESS`、`fetched` 大于 0 就可以提 PR 了。

**其他招聘系统**：实现 `JobCollector` 并标注 `@Component`，放在 `collect/source/` 下。解析和请求要分开，用录制的真实响应测试解析（放在 `src/test/resources/fixtures/`），可以参考 `MeituanCollector`。

## 采集边界

提交采集器前请确认：

- 数据来自公司官网公开页面，不需要登录
- 没有破解加密接口，没有绕过验证码、签名或风控
- 请求频率遵守 `HttpFetcher` 的间隔，每天一轮
- 不采集牛客等聚合平台整理的内容

接口加密或有风控的站点请不要接入，开 issue 说明情况即可。

## 开发

```bash
./mvnw test                                  # 服务端测试
cd extension-tests && npm install && npm test # 插件测试
```

需要 Java 21+。提交前请确保测试通过，`git commit` 信息说明这次改动的原因。

## 安全问题

发现安全问题请不要公开提 issue，通过 GitHub 的 [Report a vulnerability](https://github.com/Ajax-1/campus-radar/security/advisories/new) 私下报告。
