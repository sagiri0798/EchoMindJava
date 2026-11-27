# RAG 缓存 / 召回延迟 A/B 报告

- 日期：2026-11-28
- 测试方：b6
- worktree：`EchoMindJava/.claude/worktrees/feature-latency-ab`（基于 `a1a6e96`，未 commit / 未 push）
- 测试入口：`/chat`（主指标），辅助 `/search` 与 `/metrics`
- 约束：不动 8090；不改 18 项 dataset / EvaluationTest / README；不改简历

## 1. 测试环境与配置

| 组 | 端口 | `ECHOMIND_KNOWLEDGE_CACHE_ENABLED` | `ECHOMIND_KNOWLEDGE_RECALL_MODE` | TTL | 说明 |
| --- | ---: | --- | --- | ---: | --- |
| A | 8095 | false | parallel | — | 冷/无缓存基线 |
| B | 8096 | true | parallel | 600s | 先冷启动，再 warmup 后测热缓存 |
| C | 8097 | false | serial | — | 串行召回对照 |

共同环境：

- fake 模型：`docs/evidence/latency-ab/fake-anthropic-ab.mjs`，`FAKE_DELAY_MS=200`，rewrite 返回 3 个子查询，所有主 Agent 请求触发 `search_knowledge_base`
- `SEMANTIC_STORAGE_MODE=disabled`，`KNOWLEDGE_SEED_DEMO=false`，本地 Redis 6379，系统 Maven `D:/workcode/apache-maven-3.9.16/bin/mvn`
- 语料：`rag-eval/dataset-v1.json` 10 篇文档，经 harness `SEED=1` 写入 `/knowledge/add`；三组同一 revision
- 查询集：`docs/evidence/latency-ab/queries.json`，8 条可回答问题：
  `refund-arrival-time`、`invoice-after-payment`、`payment-failed-page`、`subscription-cancel-renewal`、`account-email-change`、`security-strange-login`、`technical-http-401`、`order-no-tracking-seven-days`
- 负载：N=60/组/轮 × 3 轮，CONCURRENCY=5，WARMUP=16（8 query × 2），每次请求唯一 `client_request_id`（duplicates=0）
- B 额外：冷缓存先用 8 条 query 各跑 1 次（8 样本），再 warmup 16、热缓存 3×60

## 2. 端到端结果（客户端 p50/p95/p99，ms）

| 组 | 请求 | p50 | p95 | p99 | max | HTTP/status | 失败/超时 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| A cache off + parallel | 180 | **1068** | **1285** | **1447** | 1463 | 180×200 / success | 0 |
| B cache on + parallel | 180 | **640** | **867** | **1108** | 1114 | 180×200 / success | 0 |
| C cache off + serial | 180 | **1068** | **1284** | **1489** | 1490 | 180×200 / success | 0 |
| B 冷缓存（补充，8 样本） | 8 | 1782 | 1962 | 1962 | 1962 | 8×200 / success | 0 |

### 3 轮稳定性

**B vs A（cache on vs off，parallel）**

| 轮次 | A p50 | B p50 | Δp50 | A p95 | B p95 | Δp95 | A p99 | B p99 | Δp99 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1070 | 648 | −39.44% | 1288 | 858 | −33.39% | 1291 | 869 | −32.69% |
| 2 | 1046 | 636 | −39.20% | 1263 | 855 | −32.30% | 1463 | 868 | −40.67% |
| 3 | 1069 | 639 | −40.22% | 1281 | 895 | −30.13% | 1289 | 1114 | −13.58% |

结论：**3 轮 p50/p95/p99 全部同向下降**；第 3 轮 p99 降幅较小（−13.58%），但仍低于 A。

**C vs A（serial vs parallel，cache off）**

| 轮次 | A p50 | C p50 | Δp50 | A p95 | C p95 | Δp95 | A p99 | C p99 | Δp99 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1070 | 1064 | −0.56% | 1288 | 1284 | −0.31% | 1291 | 1304 | +1.01% |
| 2 | 1046 | 1056 | +0.96% | 1263 | 1284 | +1.66% | 1463 | 1490 | +1.85% |
| 3 | 1069 | 1081 | +1.12% | 1281 | 1284 | +0.23% | 1289 | 1292 | +0.23% |

结论：**serial vs parallel 无可测差异**，方向不一致（−0.56% ~ +1.85%），本 setup 下不能归因；不建议写简历数字。

## 3. 缓存命中与工具耗时

### 3.1 `/search` 直接探针

| 组 | 第一次 | 第二次 |
| --- | --- | --- |
| A cache off | `cached=false, latency_ms=429` | `cached=false, latency_ms=430` |
| B cache on | `cached=false, latency_ms=410` | **`cached=true, latency_ms=0`** |
| C cache off | `cached=false, latency_ms=426` | `cached=false, latency_ms=432` |

### 3.2 `/metrics` 缓存计数

| 组/阶段 | full_chain hits | full_chain misses | sub_query misses |
| --- | ---: | ---: | ---: |
| A cache off | 无该计数器 | 无 | 无 |
| B 冷缓存后 | 3 | 8 | 32 |
| B 热缓存后 | 277 | 8 | 32 |
| C cache off | 无该计数器 | 无 | 无 |

- B 热阶段 full_chain 命中率：`277 / (277 + 8) = 97.2%`（按 full_chain lookup 计，不是按 HTTP 请求计；3 条 query 走 technical+billing，双 Agent 会各查一次）。
- 8 次 miss 对应冷启动第一次查询；warmup/热阶段无新增 miss。
- A/C cache off 不产生 hit/miss 计数器，符合开发说明。

### 3.3 `knowledge_search` 工具耗时（`/monitor`）

| 组 | total | success_rate | avg_latency_ms |
| --- | ---: | ---: | ---: |
| A cache off | 274 | 1.0 | **417.39** |
| B cache on | 285 | 1.0 | **12.69** |
| C cache off serial | 274 | 1.0 | **417.58** |

### 3.4 服务端聊天耗时（`/metrics`）

| 组 | `echomind_chat_latency_seconds_count` | sum (s) | 平均 (ms) |
| --- | ---: | ---: | ---: |
| A | 196 | 227.713 | 1161.8 |
| B | 204 | 153.893 | 754.4 |
| C | 196 | 227.767 | 1162.1 |

> A/C 包含 warmup 16；B 包含 cold 8 + warmup 16 + hot 180，因此该平均值只作服务端一致性参考，主指标仍用客户端分位数。

## 4. 答案 / 路由 / 工具一致性

- A/B/C 每轮 status 均为 `success`，HTTP 全部 200；`duplicate_request_ids=0`；无 `client_error` / 超时。
- 8 条 query 的 `answerHashes`、`tools_used`、主 Agent 在 A/B/C 三组逐条一致；脚本比较 `mismatches=[]`。
- 回答哈希只有 2 个，对应两条确定性分支：
  - 3 条 query 走 technical+billing → composer 文本（15 字）
  - 5 条 query 走单 technical → 单 Agent 文本（19 字）
- 结论：缓存命中不改变答案、工具或主 Agent；serial/parallel 也不改变结果。

## 5. 结论与建议口径

### 可以报告的

- 同一版本、8 条固定知识问答、N=60/组×3 轮、并发 5、fake 模型 200ms 的受控压测：
  - cache-on 相对 cache-off：**p50 1068→640ms（−40.1%）、p95 1285→867ms（−32.5%）、p99 1447→1108ms（−23.4%）**
  - `knowledge_search` 工具平均耗时 **417.4ms→12.7ms**
  - full_chain 缓存命中率 **97.2%**（按 full_chain lookup）
  - 3 轮方向一致，答案/工具/主 Agent 完全一致，0 错误/超时
- 简历可选表述（必须带限定）：
  > 同一版本、8 条固定知识问答、N=60/组×3 轮、并发 5 的受控压测：RAG 全链路缓存命中场景下 p95 从 1285ms 降到 867ms（fake 模型 200ms，非生产 SLA，不代表线上收益）。
- 更保守的面试口径：
  > 在受控 fake 模型与固定语料下测过 cache on/off 对照，p95 约降 32%；这是缓存命中场景的机制验证，不是线上 SLA，也没有外推到真实模型/真实语料。

### 不能报告的

- **serial vs parallel**：本 setup 无可测差异（−0.56% ~ +1.85%，方向不一致），不写“并行比串行快 X%”。
- 不能把 8 条固定 query、fake 200ms、`SEMANTIC_STORAGE_MODE=disabled` 的结果写成“生产延迟”或“线上优化”。
- 冷缓存 8 样本只能作为补充（冷 1782ms → 热 640ms），不能当 N≥60 的统计结论。

## 6. 限制

- fake 模型固定 200ms 延迟；`SEMANTIC_STORAGE_MODE=disabled`，检索为本地 hash/BM25；未使用真实 DeepSeek、Chroma 或真实企业语料。
- 单机、单 Redis、本地进程内缓存；未做多实例共享缓存、长稳压测、GC/CPU 干扰分析。
- B 冷缓存仅 8 样本；A/B/C 各 3 轮、每轮 60；未做更多轮次或更高并发。
- 未使用 `/eval/run` 做质量 parity；答案/工具/主 Agent 一致性由 `/chat` 哈希与响应字段验证。
- worktree 未 commit / 未 push；结果只存 `docs/evidence/latency-ab/`。

## 7. 证据索引

| 文件 | 内容 |
| --- | --- |
| `A-cache-off-parallel.json` | A 组 3×60 原始结果 |
| `B-cache-on-cold.json` | B 冷缓存 8 条 |
| `B-cache-on-parallel.json` | B 热缓存 3×60 |
| `C-cache-off-serial.json` | C 组 3×60 |
| `summary.json` / `summary.txt` | 汇总、对比、一致性、cache 指标 |
| `A|B|C-run.log` / `B-cold-run.log` / `B-hot-run.log` | harness 逐轮输出 |
| `A|B|C-metrics-*.txt` | `/metrics` 原文 |
| `A|B|C-monitor-after.json` | 工具耗时/监控 |
| `A|B|C-search-*.json` / `B-search-probe-*.json` / `C-search-probe-*.json` | `/search` 缓存探针 |
| `fake-stats-A|B|C.json` | 假模型调用计数 |
| `A|B|C-app.log` | 启动与运行日志 |
| `queries.json` | 固定 8 条查询 |
| `latency-ab.mjs` | 负载 harness |
| `fake-anthropic-ab.mjs` | test-only 假模型（3 子查询） |
