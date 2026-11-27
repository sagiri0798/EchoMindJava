# Address-change evidence (8095)

> PII 说明：本目录证据已按统一 `PiiRedactor` 规则脱敏整理——完整手机号掩码为
> `138****0000` 形式；地址保留省/市/区，街道与门牌为 `****`；收件人保留首字
> （如 `张**`）。原 18 项 `customer-service-v1.json` 未修改。

All runs used the worktree `feature+billing-address-change` with:

```bash
SERVER_PORT=8095 MCP_WRITE_ENABLED=true REDIS_PASSWORD= \
SPRING_PROFILES_ACTIVE=deepseek DEEPSEEK_MODEL=deepseek-flash \
/d/workcode/apache-maven-3.9.16/bin/mvn -q spring-boot:run
```

The original `customer-service-v1.json` and its 18-case counts were not modified.

## Files

| File | Scenario | Result |
|---|---|---|
| `chat-1-incomplete.json` | “帮我把收货地址改成北京市朝阳区” | `intent=account`, `agent=billing`, `escalated=false`; asks order/recipient/phone/street |
| `chat-2-missing-door.json` | order `B2001` + 朝阳区 | `lookup_order` used; masked phone; asks street/door |
| `chat-3e-complete-turn1.json` | complete info, turn 1 | `lookup_order` used; restates masked address; asks confirmation |
| `chat-4e-complete-turn2.json` | “确认修改” | `update_shipping_address` used; sandbox success; `real_order_modified=false`; `escalated=false` (one earlier run had `escalated=true` from the answer verifier) |
| `chat-5-unknown.json` | order `999999` | `lookup_order` found=false; refuses; asks to verify |
| `chat-6-shipped.json` | shipped order `B2002` | refuses direct change; suggests carrier/正式渠道; no interception claim |
| `original-18-final.json` | default `/eval/run` | 16/18; `dialog_14` fails the `expectedAgent=general` check (actual `billing`); one intent case flaked on LLM sampling in this run (17/18 in other runs; same-suite baseline 17/18, original `baseline.json` 16/18) |
| `address-suite-final.json` | separate address suite | 10/10; `dialog_overall=0.9333` |

## Notes

- `/eval/run` deliberately uses a read-only tool fork and a read-only guard, so `update_shipping_address` is not executed during evaluation. The real write path is covered by `AddressToolsTest` and the two-turn `/chat` evidence.
- Sandbox ownership uses `AgentRequest.userId()`. In development without `ECHOMIND_API_TOKENS`, `/chat` accepts a client-supplied `user_id`; production auth should be enabled before treating the sandbox ownership check as an authorization boundary.
- `confirmed=true` is a model-asserted gate for this sandbox. A real order system must verify user confirmation server-side and must not copy this pattern as authorization.
- `./mvnw` in this checkout has a broken wrapper jar (`no main manifest attribute`); system Maven 3.9.16 was used.

## Tests

- Plain `mvn test` (no test Redis): 164 tests — 146 passed, 18 skipped, 0 failures. The skips are 17 `ApiIntegrationTest` cases (`test.redis.port` not set) plus 1 `SemanticStorageTest` Chroma case (`test.chroma.url` not set).
- `mvn test -Dtest.redis.port=16379` with an isolated `redis-server --port 16379 --save "" --appendonly no`: 164 tests — 163 passed, 0 failures, 0 errors, 1 skipped. `ApiIntegrationTest` 17/17 passed; only the Chroma test remains skipped because no Chroma environment is available.
- The earlier “163 tests, 0 skipped” summary was wrong; the suite has since grown to 164 and the Chroma test is always reported as skipped without `test.chroma.url`.

## 2026-11-28 PII 回归（端口 8098）

使用主工作区新代码起 `SERVER_PORT=8098`（`MCP_WRITE_ENABLED=true`、Redis `16379`、
DeepSeek `deepseek-flash`）复测：

- `/eval/run --data-binary @src/main/resources/evaluation/address-change-cases.json`：
  **10/10，pass_rate=1.0**（新证据 `address-suite-regression-20261128.json`）。
- 真实 `/chat` 两轮（订单 B2001，合成号码 13700000000）：
  - Turn 1：`lookup_order`；回复显示 `137****0000`、`北京市朝阳区****`；`escalated=false`
    （`chat-7-regression-turn1.json`）。
  - Turn 2（“确认修改”）：`update_shipping_address` 沙箱预演成功，回复明确“真实订单未修改”；
    `escalated=false`；无完整手机号/街道（`chat-8-regression-turn2.json`）。
- `/trace/tool/{id}` 与 `/trace/bus/{id}`：两轮均扫描无完整手机号/街道（`****` 掩码保留）。
- 可读性修正：普通词“电话”不再被收件人规则误掩；`北京市 北京市 朝阳区` 去重为
  `北京市朝阳区****`。
- PII 专项 17/17 通过；全量 `mvn test`：172 tests、0 failures、18 skipped
  （17 个需 Redis 的集成用例 + 1 个 Chroma 用例；本目录 8098 证据使用独立 Redis 16379）。

## Reproduction

```bash
curl -s -X POST http://localhost:8095/chat -H 'Content-Type: application/json' \
  --data-binary @chat-request.json

curl -s -X POST http://localhost:8095/eval/run -H 'Content-Type: application/json' -d '{}'
curl -s -X POST http://localhost:8095/eval/run -H 'Content-Type: application/json' \
  --data-binary @src/main/resources/evaluation/address-change-cases.json
```

Poll `/eval/jobs/{job_id}` for the result.
