# RAG 延迟 A/B 运行手册（待开发 ready）

- 日期：2026-11-27
- 角色：b6（测试方）
- 目标 worktree：`EchoMindJava/.claude/worktrees/feature-latency-ab`
- 要求：不改项目代码、不 commit/push、不动 8090、不改 18 项 dataset/EvaluationTest/README

## 0. 当前状态

- 已准备：固定查询集、A/B harness、test-only 假模型（3 子查询）。
- 开发 worktree 目前仍是干净基线；`KnowledgeCacheTest.java` 已出现，但源码/配置尚未落地。
- 从测试预期推断的配置项（等源码 ready 后以实际为准）：
  - `echomind.knowledge.cache-enabled`（环境变量 `ECHOMIND_KNOWLEDGE_CACHE_ENABLED`）
  - `echomind.knowledge.cache-ttl-seconds`（`ECHOMIND_KNOWLEDGE_CACHE_TTL_SECONDS`）
  - `echomind.knowledge.recall-mode`（`ECHOMIND_KNOWLEDGE_RECALL_MODE`，`parallel` / `serial`）
  - 指标：`echomind_knowledge_cache_hits_total{layer="full_chain"}`、`echomind_knowledge_cache_misses_total{layer="full_chain"}`；子查询层 `layer="sub_query"`。

## 1. 固定查询集

`docs/evidence/latency-ab/queries.json`，8 条可回答问题，跨品类：

| # | case id | category |
| ---: | --- | --- |
| 1 | refund-arrival-time | refund |
| 2 | invoice-after-payment | invoice |
| 3 | payment-failed-page | payment |
| 4 | subscription-cancel-renewal | subscription |
| 5 | account-email-change | account |
| 6 | security-strange-login | security |
| 7 | technical-http-401 | technical |
| 8 | order-no-tracking-seven-days | order |

来源：`rag-eval/dataset-v1.json`；两组完全一致；每次请求唯一 `client_request_id`，不触发幂等 replay。

## 2. Harness 与假模型

- 负载 harness：`docs/evidence/latency-ab/latency-ab.mjs`
  - 固定 query 文件、warmup、轮次、label、每轮 60 请求、并发 5
  - 记录 `answerHash`、`agentType`、`toolsUsed`、`status`、p50/p95/p99、perQuery
  - `SEED=1` 时先向 `/knowledge/add` 写入 10 篇 RAG 语料
- 假模型：`docs/evidence/latency-ab/fake-anthropic-ab.mjs`
  - `FAKE_DELAY_MS=200`、`REWRITES=3`
  - 所有主 Agent 请求都触发 `search_knowledge_base`
  - rewrite 返回 3 个基于原 query 的子查询；不改生产 `loadtests/fake-anthropic.mjs`
  - 已 smoke test：rewrite 返回 3 条；主 Agent 返回 `tool_use`

## 3. 三组配置（端口错开，不动 8090）

| 组 | 端口 | cache | recall | 启动关键环境变量 |
| --- | ---: | --- | --- | --- |
| A | 8095 | off | parallel | `ECHOMIND_KNOWLEDGE_CACHE_ENABLED=false`、`ECHOMIND_KNOWLEDGE_RECALL_MODE=parallel` |
| B | 8096 | on | parallel | `ECHOMIND_KNOWLEDGE_CACHE_ENABLED=true`、`ECHOMIND_KNOWLEDGE_CACHE_TTL_SECONDS=600`、`ECHOMIND_KNOWLEDGE_RECALL_MODE=parallel` |
| C | 8097 | off | serial | `ECHOMIND_KNOWLEDGE_CACHE_ENABLED=false`、`ECHOMIND_KNOWLEDGE_RECALL_MODE=serial` |

每组额外设置：

```text
SERVER_PORT=<8095|8096|8097>
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=test
ANTHROPIC_BASE_URL=http://127.0.0.1:8099
ANTHROPIC_MODEL=claude-sonnet-4-6
SEMANTIC_STORAGE_MODE=disabled
ECHOMIND_ENV=development
KNOWLEDGE_SEED_DEMO=false
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
ECHOMIND_DATA_DIR=docs/evidence/latency-ab/data-<group>
```

- 三组使用同一 fake 模型、同一 Redis、同一机器；语料由 harness `SEED=1` 写入同一 10 篇文档。
- 若缓存实现是进程内，三实例天然隔离；若改为 Redis 共享缓存，需按组加前缀或清空缓存 key，避免 A/C 读到 B 的缓存。
- 先启动 fake（8099），再依次启动 A/B/C；每组启动后先 `SEED=1` 写入语料。

## 4. 测试流程

### A/B1（主）：cache off vs on（parallel）

```bash
# A: cache off + parallel
TARGET=http://127.0.0.1:8095 LABEL=cache-off-parallel SEED=1 \
  QUERIES=docs/evidence/latency-ab/queries.json \
  RESULT_FILE=docs/evidence/latency-ab/cache-off-parallel.json \
  ROUNDS=3 REQUESTS=60 CONCURRENCY=5 WARMUP=16 \
  node docs/evidence/latency-ab/latency-ab.mjs

# B: cache on + parallel（先 warmup 16 次，即 8 query × 2）
TARGET=http://127.0.0.1:8096 LABEL=cache-on-parallel SEED=1 \
  QUERIES=docs/evidence/latency-ab/queries.json \
  RESULT_FILE=docs/evidence/latency-ab/cache-on-parallel.json \
  ROUNDS=3 REQUESTS=60 CONCURRENCY=5 WARMUP=16 \
  node docs/evidence/latency-ab/latency-ab.mjs
```

### A/B2（次）：serial vs parallel（cache off）

```bash
# C: cache off + serial
TARGET=http://127.0.0.1:8097 LABEL=cache-off-serial SEED=1 \
  QUERIES=docs/evidence/latency-ab/queries.json \
  RESULT_FILE=docs/evidence/latency-ab/cache-off-serial.json \
  ROUNDS=3 REQUESTS=60 CONCURRENCY=5 WARMUP=16 \
  node docs/evidence/latency-ab/latency-ab.mjs
```

### 指标抓取

每组测试前后抓取 `/metrics` 和 `/monitor`：

```bash
curl -s http://127.0.0.1:<port>/metrics > docs/evidence/latency-ab/<group>-metrics-before.txt
curl -s http://127.0.0.1:<port>/metrics > docs/evidence/latency-ab/<group>-metrics-after.txt
curl -s http://127.0.0.1:<port>/monitor > docs/evidence/latency-ab/<group>-monitor-after.json
curl -s http://127.0.0.1:8099/stats > docs/evidence/latency-ab/fake-stats-<group>.json
```

关注：
- 客户端 p50/p95/p99（harness JSON）
- `echomind_chat_latency_seconds` 直方图
- `echomind_knowledge_cache_hits_total{layer="full_chain"}` / misses；子查询层
- `knowledge_search` 工具耗时（`/monitor` 的 `tool_stats` 或 trace）
- HTTP/status/错误率、`distinct_request_ids`

## 5. 验收标准

1. 3 轮方向一致：cache on 相对 off 的 p50/p95/p99 在 3 轮同向；serial 相对 parallel 同向。
2. 无错误：全部 HTTP 200、`status=success`，无 `client_error`。
3. 结果一致：三组 `answer_hashes`、`agent_types`、`tools_used` 完全一致；缓存不能改变答案。
4. 缓存确实命中：B 的 `full_chain` hits > 0 且 misses 合理；A/C 不应出现 cache hits 计数器。
5. 每轮 N≥60，唯一 `client_request_id` 无重复；样本量写入报告。
6. 同一 fake 模型、同一语料、同一 Redis、同一机器；记录配置和命令。

## 6. 报告口径

- 稳定时才建议写简历，例如：
  > 同一版本、8 条固定知识问答、N=60/组×3 轮、受控压测，cache-on 相对 cache-off 的 p95 从 X 降到 Y（缓存命中场景，非生产 SLA）。
- 不稳定则只报告机制与观测值，不写“从 X 降到 Y”，不改简历。
- 报告位置：`docs/evidence/latency-ab/report.md` + 原始 JSON。
- 不改原 18 项 dataset/EvaluationTest/README 的 18 项计数；不 commit/push。

## 7. 待开发 ready 后确认

- [ ] `EchoMindProperties.Knowledge` 的实际字段名与默认值
- [ ] `application.yml` 是否已暴露上述属性
- [ ] 缓存是进程内还是 Redis；若共享，确认 key 前缀/清理方式
- [ ] `echomind_knowledge_cache_hits_total` / misses 的实际标签名（测试用 `layer`）
- [ ] serial/parallel 开关是否只影响召回并发，不改变结果顺序
- [ ] 启动脚本与健康检查通过
