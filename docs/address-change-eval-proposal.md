# 地址变更评测提案（独立 suite，不修改原 18 项）

## 决策（2026-11-28）

冻结旧基线，新建独立 suite：原 `customer-service-v1.json`、`EvaluationTest` 计数和 README 评测数量保持不动；`src/main/resources/evaluation/address-change-cases.json` 保留为地址变更专项回归；不修改原 suite；未 commit / 未 push。

## 背景

原固定回归集 `src/main/resources/evaluation/customer-service-v1.json` 保持不动：11 条意图用例 + 4 条单轮对话 + 1 组 3 轮对话，共 18 项。本提案不新增 `intentCases` / `dialogCases` 到原文件，避免改变原 suite_id 和历史基线。

独立 payload：`src/main/resources/evaluation/address-change-cases.json`，运行方式与 `agent-parity-cases.json` 相同：

```bash
curl -X POST http://localhost:8095/eval/run \
  -H 'Content-Type: application/json' \
  --data-binary @src/main/resources/evaluation/address-change-cases.json
```

## 覆盖范围

- 意图：收货地址变更（含“换地址”“地址变更”）、邮箱地址变更负例。
- 对话：
  1. 只给“朝阳区” → 追问订单号、收件人、电话、街道门牌。
  2. 有订单号但缺门牌 → 先 `lookup_order` 核验，再追问门牌。
  3. 有完整地址但缺订单号 → 追问订单号。
  4. 信息完整 → `lookup_order` 核验后复述并请用户确认，不直接写。
  5. 未知订单 `#999999` → `lookup_order` 返回 not found，拒绝并请用户核对。
  6. 已发货订单 `#A1002` → 拒绝修改，建议联系承运商或正式客服渠道。

## 已知边界

- 项目安全策略规定 `/eval/run` 不执行写工具（`ToolRegistry.fork()` 只复制只读工具，评测请求也使用只读 `ToolExecutionGuard`）。因此“成功修改”不在本 suite 中断言，由 `AddressToolsTest.updateSucceedsInSandboxAndLookupReflectsChange` 和真实 `/chat` 的两轮确认场景覆盖。
- 新代码会让原 suite 的 `dialog_14`（“帮我把收货地址改成北京市朝阳区”）从原来的非账单路由变为 `billing`（用例里的 `expectedAgent` 仍是 `general`，前置运行里该用例实际常落到 `escalation`），所以原 18 项中该项会变为失败。这是预期的行为变化，不是质量回归；后续需要在“更新原 suite”与“冻结旧基线、另建 suite”之间做决定。
- 地址工具是进程内沙箱：`sandbox=true`、`real_order_modified=false`，不连接真实订单系统。

## 实测结果（2026-11-27，8095，deepseek-flash，最终代码）

### 原 18 项（未改 dataset）

- 最终运行 16/18；失败项：
  - `dialog_14`：确定性断言 `checks.agent=false`（用例期望 `general`，新代码实际 `billing`，质量分 0.85）。这是地址路由的预期行为变化。
  - `intent_4`（“为什么扣了两次款？”）：LLM 采样波动导致本次未命中 `payment_issue`；该消息不含地址，与本次地址改动无关。同 suite 前置基线 `8c3f2e…` 为 17/18（失败项同为 `dialog_14`，当时实际 `escalation`）；另一次运行也为 17/18。按“18 项不低于 16/18”的口径，本次达标。
- 本次指标：`dialog_overall` 0.9214，`accuracy` 0.9714，`completeness` 0.8143，`relevance` 1.0，`helpfulness` 0.9，`macro_f1` 0.8333，`intent_accuracy` 0.9091。`regressions` 里的 `macro_f1`/`intent_accuracy` 来自上述单条意图采样波动，不是地址路由回归。
- 与同 suite 前置 `8c3f2e` 对比：`dialog_overall` 0.9179→0.9214，`accuracy` 0.9857→0.9714，`completeness` 0.7857→0.8143，`relevance` 1.0→1.0，`helpfulness` 0.9→0.9。通过数受 `intent_4` 单次波动影响，需在更新原 suite 后多跑几次确认。

### 独立 address-change suite

- 10/10 通过；intent 4/4，dialog 6/6；`dialog_overall` 0.9333，`accuracy` 0.9667，`completeness` 0.85，`relevance` 1.0，`helpfulness` 0.9167。
- 覆盖：不完整追问、缺门牌追问、缺订单号追问、完整信息 lookup 后确认、未知订单拒绝、已发货拒绝并建议承运商/正式渠道。

### 真实 /chat（8095，最终代码）

- 不完整：“帮我把收货地址改成北京市朝阳区” → `intent=account`、`agent=billing`、`escalated=false`，追问订单号/收件人/电话/门牌。
- 缺门牌：订单 `B2001` + 朝阳区 → `lookup_order` 命中，脱敏手机号，追问街道门牌。
- 完整两轮：第一轮 `lookup_order` 后复述脱敏地址并请确认；第二轮“确认修改” → `update_shipping_address` 成功，`sandbox_updated`、`real_order_modified=false`、`tools_used=["update_shipping_address"]`、`escalated=false`。前一次同场景运行 `escalated=true`（回答校验器波动），地址链路本身未触发关键词升级。
- 未知订单 `999999` → `lookup_order` 返回 `found=false`，拒绝并请核对订单号，`escalated=false`。
- 已发货 `B2002` → 拒绝直接修改，建议联系承运商或正式客服渠道，明确不能代为提交拦截申请，`escalated=false`。
