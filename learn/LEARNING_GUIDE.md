# EchoMind Java 深度学习路线图

> 面向想系统学习「多 Agent 智能客服」工程的开发者。本指南基于本仓库的实际代码组织，每一阶段都给出：核心文件（可直接点开）、要回答的问题、动手实验。
>
> 技术栈：Java 21 · Spring Boot 3.5 · LangChain4j 1.12.2 · Redis · ChromaDB · 官方 MCP Java SDK · Micrometer/Prometheus

---

## 配套分节课程（learn/，共 10 课时）

按下面阶段顺序跟课，每课有可直接粘贴执行的实验（PowerShell 5.1 实测）：

| 课程 | 对应阶段 | 内容 | 状态 |
| --- | --- | --- | --- |
| [lesson-01-first-run.md](learn/lesson-01-first-run.md) | 阶段 0 | 跑起来 + 五个实验，看懂系统暴露的所有信号 | ✅ |
| [lesson-02-request-lifecycle.md](learn/lesson-02-request-lifecycle.md) | 阶段 1 | 九环节深读 + 六个实验 | ✅ |
| [lesson-03-intent-fusion.md](learn/lesson-03-intent-fusion.md) | 阶段 2 | 意图识别——三路融合的取舍（融合公式手算实测） | ✅ |
| [lesson-04-agent-framework.md](learn/lesson-04-agent-framework.md) | 阶段 3 | Agent 框架层——LangChain4j 的正确打开方式（实战①：加 LogisticsAgent） | ✅ |
| lesson-05 | 阶段 4 | AgentBus——本项目的灵魂 | 计划 |
| lesson-06 | 阶段 5 + 10 | 工具系统 + MCP——安全边界与标准协议 | 计划 |
| lesson-07 | 阶段 6 | RAG 知识库（实战②：加知识库工具） | 计划 |
| lesson-08 | 阶段 7 | 分层记忆——多轮对话的工程化 | 计划 |
| lesson-09 | 阶段 8 | 可靠性工程——从 Demo 到生产的距离（实战③：Java 21 重构） | 计划 |
| lesson-10 | 阶段 9 + 11 + 实战 | 评测监控 + 测试习惯 + 实战验收 + 深挖问题清单面试串讲 | 计划 |

---

## 先建立心智地图

在深入任何一行代码之前，先理解这个项目的「一句话架构」：

```
一次客服请求 = 意图识别（路由） + 一个或多个 Agent（生成） + 工具（知识库/MCP） + 校验（把关） + 可靠提交（存储）
```

它不是一个"调一次 LLM 就返回"的 Demo，而是一套完整的 **LLM 应用工程**：路由、协作、工具安全、记忆、评测、监控、可靠性治理，每一层都有生产级考虑。学习这个项目的最大价值不是"会用 LangChain4j"，而是学会**如何把一个大模型调用包装成可靠的服务**。

### 全景架构图（简版）

```
HTTP /chat
  → AccessFilter（可选鉴权）
  → ChatService（会话锁 + 幂等回执）
      → MemoryManager（工作记忆/摘要/长期记忆/画像）
      → IntentRecognizer（LLM + Embedding + Pattern 三路融合）
      → AgentOrchestrator（主辅路由 / 并行协作 / AgentBus 协商）
          → BaseAgent → AgentAiService（LangChain4j AiServices 工具循环）
              → 本地知识库工具：查询改写 → BM25 + 向量并行召回 → LLM 重排
              → 外部 MCP 工具：白名单 → Schema → 超时 → 缓存 → 熔断
          → AnswerVerifier（校验不通过 → 转人工 / 可重试失败）
      → ConversationStore（Lua 原子提交消息 + 状态 + 回执）
      → MemoryMaintenance（后台队列压缩记忆、更新画像）
```

### 十一个子系统的位置索引

| 子系统 | 包路径 | 一句话职责 |
| --- | --- | --- |
| 入口与 API | `com.echomind.api` | REST 接口、鉴权、错误规范 |
| 核心编排 | `com.echomind.chat` | `ChatService`：会话锁、幂等、主流程编排 |
| 意图识别 | `com.echomind.intent` | 三路意图融合、紧急度、在线 Embedding |
| Agent 层 | `com.echomind.agent` | 路由、Agent 基类、AiServices 装配、校验、汇总 |
| Agent 通信协议 | `com.echomind.agent.communication` | AgentBus：ASK/DELEGATE/CRITIQUE 等消息与收敛控制 |
| 工具系统 | `com.echomind.tool` | 注册表、JSON Schema 校验、熔断、写保护 |
| 知识库 | `com.echomind.knowledge` | 切片、BM25、语义索引、融合召回 |
| 记忆 | `com.echomind.memory` | 分层记忆、后台维护、原子提交 |
| LLM 网关 | `com.echomind.llm` | 模型适配、输入预算、截断拒绝、指标 |
| 评测 | `com.echomind.evaluation` | LLM Judge 四维评分、Few-shot 校准、回归基线 |
| 监控/轨迹/存储 | `monitor` / `trace` / `storage` / `security` / `skill` / `integration` | 可观测性、MCP、Skills、语义存储 |

---

## 阶段 0：把项目跑起来，建立体感（0.5 天）

不要跳过这一步。能跑、能玩、能看轨迹，后面读代码才有抓手。

**准备**：JDK 21+、Redis（本项目 Compose 中 Redis 映射宿主机 6380 端口）。

```bash
docker compose up -d redis
export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=你的Key
export REDIS_PORT=6380
export REDIS_PASSWORD
./mvnw spring-boot:run
```

**必做实验**：

1. 发一条普通消息，看响应里的所有字段（`agent_types`、`tools_used`、`entities`、`intent_embedding_source` 等）——这些字段就是系统的"内部状态暴露面"。
2. 发一条跨域问题，触发 AgentBus 协商模式：
   > "我的应用一直报 500，会不会重复扣了我的订阅费"
   
   然后访问 `GET /trace/bus/{request_id}`，看 Technical↔Billing 之间的 ASK 通信图。**这张图是理解 AgentBus 的关键**。
3. 往知识库塞一篇文档，再问一个需要检索的问题，观察 `tools_used` 里出现知识检索工具。
4. 提交一次评测：`curl -X POST http://localhost:8080/eval/run -d '{}'`，轮询 job 看四维分数。
5. 打开 Swagger（`http://localhost:8080/docs`）通读一遍所有接口——接口清单就是这个系统的功能清单。

**此时你应该能回答**：一个 `/chat` 请求会依次经过哪些组件？

---

## 阶段 1：跟踪一次请求的全生命周期（核心！2 天）

这是整个学习路线里**最重要**的一步。多 Agent 系统的一切复杂度都体现在一次请求的处理链上。

### 阅读路径（按调用顺序）

1. `api/EchoMindController.java` → `chat/ChatService.java`
   看：鉴权后如何进入聊天流程；Redis 会话锁怎么加、怎么续租；请求回执（幂等键）怎么查。
2. `memory/MemoryManager.java` → `memory/MemoryContext.java`
   看：工作记忆（原生消息历史）、摘要、长期记忆（语义检索）、用户画像、业务字段这五类"背景"如何组装。
3. `intent/IntentRecognizer.java`
   看：LLM 分类、Embedding 相似度、Pattern 规则三路结果如何融合成一个 `IntentResult`（置信度、紧急度、实体）。
4. `agent/AgentOrchestrator.java`
   看：主辅路由规则；复合问题如何并行；`AgentStats.routingScore()` 如何参与选人；失败如何降级到 General。
5. `agent/BaseAgent.java` + `agent/AgentAiService.java` + `agent/AgentAssistant.java`
   看：角色提示如何注入；每次调用为何创建独立 `MessageWindowChatMemory`；LangChain4j `AiServices` 如何自动执行工具循环（模型选择工具 → 执行 → 结果回填 → 再生成）。
6. `agent/AnswerVerifier.java`
   看：回答如何校验（`pass` + `grounded` 两个维度）；三种出口：交付 / 转人工 / 可重试失败。
7. `memory/ConversationStore.java` + `util/AtomicJson.java`
   看：为什么用 **Lua 脚本**原子提交消息 + 业务状态 + 回执；快照比较如何防止后台压缩覆盖前台写入。
8. `memory/MemoryMaintenance.java`
   看：有界后台队列如何异步压缩摘要、更新画像。

### 动手实验：给请求加"探针"

在 `ChatService` 的主流程每个关键节点加一行日志（或直接打断点单步跟踪），然后重发阶段 0 的消息，对照日志走一遍。这是理解复杂编排最有效的办法。

### 此时你应该能回答

- 为什么"会话锁"和"幂等回执"是两件事？各自防什么？
- 工具循环最多 4 轮、每轮最多 8 个调用——这个上限防的是什么问题？
- 为什么回答校验失败时，未核验的答案**不写入**会话历史？
- 50 秒总预算、20 秒通信预算、8 秒 ASK 超时——这些数字之间是什么关系？

---

## 阶段 2：意图识别——三路融合的取舍（1 天）

**核心文件**：`intent/IntentRecognizer.java`、`intent/IntentEmbedding.java`、`intent/IntentResult.java`、`intent/IntentCategory.java`、`intent/UrgencyLevel.java`

**测试**：`IntentEmbeddingTest.java`、`UrgencyTest.java`

**要回答的问题**：

1. 为什么需要三路融合？只用 LLM 分类有什么问题（成本、延迟、不确定输出）？只用规则有什么问题（覆盖不全）？
2. 在线 Embedding（硅基流动 bge-m3）失败时如何回退到本地向量空间？为什么"整次匹配"失败才回退，而不是逐条回退？
3. `IntentResult` 里除了意图类别还携带了什么（置信度、紧急度、已提取实体）？这些信息下游谁在用？
4. 实体继承：多轮对话中上一轮的实体如何传给本轮？切换订单/业务领域时如何清理过期字段？（见 `memory/ConversationState.java`）

**动手实验**：在 `src/main/resources` 里找到意图的 Few-shot 模板（评测资源 `evaluation/customer-service-v1.json` 里有 11 条细粒度意图用例），自己构造一条"模棱两可"的消息，观察三路结果分别给出什么、融合后是什么。

---

## 阶段 3：Agent 框架层——LangChain4j 的正确打开方式（2 天）

这是项目里最"技术性"的一层，也是面试深挖的重点。

**核心文件**：`agent/AgentAssistant.java`（接口声明）、`agent/AgentAiService.java`（装配）、`agent/BaseAgent.java`、`agent/AgentProfile.java` + `agent/AgentProfiles.java`（读取 `src/main/resources/agents/profiles.json`）、`llm/ManagedChatModel.java`、`llm/CheckedChatModel.java`

**要回答的问题**：

1. `AgentAssistant` 只是声明一个接口，方法上的注解如何描述工具？`AiServices.builder` 做了什么魔法？——对照 [LangChain4j AI Services 官方教程](https://docs.langchain4j.dev/tutorials/ai-services/) 读。
2. 为什么每次调用都新建 `MessageWindowChatMemory`，而不是全局共享？"历史只接受 user/assistant 角色，摘要和业务状态作为独立背景传入"——这样设计比"把摘要塞进历史"好在哪？
3. `BaseAgent` 承担了什么职责？为什么业务路由（`AgentOrchestrator`）和工具消息循环（`AiServices`）都不归它管？
4. `ManagedChatModel` 的输入预算检查：`PromptBudget` 如何按 UTF-8 字节保守估算 token？预算不足时为什么**删除完整旧轮次**而不是截断当前问题？
5. `CheckedChatModel` 拒绝"截断、内容过滤及其他非完整响应"——为什么模型返回 `stop_reason=content_filtered` 时必须拒绝而不是当作正常答案？
6. 每个角色的专属工具（`inspect_request_context`、`lookup_error_code`、`compare_amounts` 等）为什么从服务端传入的 `AgentRequest` 读实体，而不是让模型在参数里自由发挥？

**动手实验**：打开 `src/main/resources/agents/profiles.json`，改 General 的 temperature 或某个角色的工具范围，重启后观察行为差异。你会直观理解"提示词工程 + 参数配置"如何决定 Agent 行为。

---

## 阶段 4：AgentBus——本项目的灵魂（2 天）

这是本项目区别于"模板路由多 Agent"的最大亮点：**Agent 在生成过程中可以主动与其他 Agent 对话**，而不是被动等待编排器分配。

**核心文件**（都在 `agent/communication/` 下）：
`BusMessage.java`（消息模型）、`MessageType.java`（六种消息）、`CommunicationTools.java`（ask_agent / delegate_agent 工具）、`ConvergenceController.java`（收敛控制）、`CommunicationContext.java`（每请求通信图）、`NegotiationTrigger.java`（协商模式触发条件）

**先建立概念模型**：

| 消息类型 | 语义 | 阻塞性 | 类比 |
| --- | --- | --- | --- |
| ASK | 子问题求助 | 阻塞（8 秒超时） | 同事发消息问一句 |
| DELEGATE | 整个请求转交 | 终止性 | 把工单转给别的部门 |
| PROPOSE | 并行模式辅助结论 | 非阻塞 | 拉会时别人附议 |
| CRITIQUE | 校验器负评 | 注入系统提示 | Code Review 意见 |
| ACCEPT | composer 采纳 | 记录 | 评审通过 |
| ESCALATE | 升级交接 | 终止性 | 提交上级 |

**要回答的问题**：

1. 为什么 ASK 超时/目标熔断/循环时，发起方要"降级继续作答而不是失败"？这体现了什么设计哲学？
2. 收敛控制器（`ConvergenceController`）的四道闸：20 秒预算、消息数 ≤6、树深度 ≤2、访问栈循环检测（A→B→A 立即终止并盖 `cycle_detected`）。为什么"循环检测"在 Agent 互相调用时如此重要？
3. 双模路由：什么条件进入协商模式（跨域意图、多实体、General 主路由），什么情况保持快路径（单 Agent / 并行+composer）？为什么快路径要"零回归"？
4. 批判-修订循环：`AnswerVerifier` 的负评变成 `CRITIQUE` 消息注入原 Agent，修订**封顶 1 轮**后复判。为什么封顶 1 轮？不封顶会怎样？
5. ASK 的目标选择复用 `AgentStats.routingScore()`——熔断冷却中的 Agent 不会被问。这和其他模块（监控惩罚、熔断器）如何联动？

**动手实验**：

1. 用阶段 0 的跨域问题触发协商，把 `/trace/bus/{request_id}` 返回的 JSON 画成一张图（节点=Agent，边=消息类型）。**画图是理解 AgentBus 最好的方式**。
2. 阅读 `communication/CommunicationTools.java`，看 `ask_agent` 和 `delegate_agent` 两个工具的参数 Schema 和实现——它们本质上是"把 Agent 当作工具调用"。
3. 把 `ECHOMIND_BUS_MAX_MESSAGES` 调成 2，重发跨域问题，观察收敛控制器如何提前截断。

**对应测试**：`AskFlowTest`、`DelegateFlowTest`、`CritiqueReviseFlowTest`、`ParallelGraphTest`、`ConvergenceControllerTest`、`NegotiationTriggerTest`——这些测试就是协议行为的"活文档"，比注释更准确。

---

## 阶段 5：工具系统——LLM 世界的安全边界（2 天）

模型决定"用什么工具"，但**系统决定"什么工具能被用"**。这一层的每一行代码都在回答"如何信任模型输出"。

**核心文件**：`tool/ToolRegistry.java`、`tool/RegistryToolProvider.java`、`tool/ToolDefinition.java`（JSON Schema 校验）、`tool/CircuitBreaker.java` + `tool/CircuitState.java`、`tool/ToolExecutionGuard.java`（写工具保护）、`tool/RoleToolRegistration.java`

**要回答的问题**：

1. 工具注册时的 JSON Schema 校验为什么重要？参数模型不支持根级 `anyOf`/`allOf` 时为什么**明确拒绝注册**而不是静默丢弃约束？（`ToolSpecifications.java`）
2. 只读模式的三重过滤：服务配置白名单 + 角色范围 + `readOnlyHint=true` 且 `destructiveHint!=true`。缺一声明的外部工具为什么直接过滤？
3. 写工具要同时满足三个条件才可用：`MCP_WRITE_ENABLED=true`、服务端 `read_only=false`、工具名及角色白名单。**并且**：执行前把 `tool_outcome_unknown`、`retryable=false` 的回执持久化到 Redis，保存失败就不执行。这个"操作前回执"设计防的是什么灾难场景？（提示：进程在工具执行后、回复落盘前崩溃，重试会发生什么？）
4. 同一请求禁止重复执行相同工具和参数——为什么？（提示：非幂等的写操作）
5. `CircuitBreaker` 的"带代次的熔断"：冷却结束后只放行一个探测请求。为什么不是冷却结束后直接全量恢复？
6. TTL 缓存：工具结果的缓存怎么保证"重排标记同时保留在缓存结果和轨迹中"？

**动手实验**：往 `config/mcp_servers.json` 里配一个自己的 MCP 服务器（哪怕是个 echo 工具），体验 stdio/Streamable HTTP 两种传输、分页发现、列表变化刷新。然后故意让服务器超时，观察熔断器的状态变化。

---

## 阶段 6：RAG 知识库——细节决定召回质量（2 天）

**核心文件**：`knowledge/TextChunker.java`（切片）、`knowledge/KnowledgeBaseService.java`（BM25 + 导入管理）、`knowledge/SemanticKnowledgeIndex.java`（向量索引）、`storage/SemanticStorage.java` + `storage/PersistentEmbeddingStore.java`

**要回答的问题**：

1. 为什么切片不直接用 LangChain4j 的切片器，而是自己写 `TextChunker`？看它的三个细节：按 **Unicode 码点**计数（不是 char，Java 的 char 是 UTF-16）、在窗口后半段优先找句末/换行边界、超长无标点文本也保证前进。这三个细节各解决什么真实 bug？
2. 混合召回：BM25 和向量召回如何融合？为什么两者互补（关键词匹配 vs 语义匹配）？LLM 重排在融合结果上做什么？
3. 查询改写：检索前对查询做了什么处理？为什么要改写？
4. 向量集合前缀包含模型版本（`echomind_java_minilm_v1`）——为什么换 Embedding 模型必须换集合？混用会怎样？
5. 导入的原子性：为什么"原子文件替换完成后才发布新索引"？重导入如何清理多余旧片段？
6. 生产要求 `chroma` 模式，连接失败**明确报错**而不隐式退回本地库——为什么隐式降级在生产是危险的？

**动手实验**：

1. 读 `TextChunkerTest`（在 `knowledge/KnowledgeTest.java` 或专门测试中），自己构造一段超长无标点文本，观察切片边界。
2. 导入同一篇文档两次（改一个字），观察 `/knowledge/stats` 里片段数量的变化——验证"同标题覆盖、旧片段清理"。
3. 用 `/search` 对比"关键词匹配"和"语义匹配"两类查询的召回差异（`top_k` 调大看完整排序）。

---

## 阶段 7：分层记忆——多轮对话的工程化（1.5 天）

**核心文件**：`memory/MemoryManager.java`、`memory/MemoryContext.java`、`memory/ConversationState.java`、`memory/MemoryMaintenance.java`、`memory/SemanticMemoryStore.java`

**要回答的问题**：

1. 记忆分几层？各层的生命周期、存储介质、用途是什么？

   | 层 | 存储 | 生命周期 | 用途 |
   | --- | --- | --- | --- |
   | 工作记忆 | Redis（原生消息） | 24h TTL | 本轮请求直接入上下文 |
   | 摘要 | Redis | 随压缩更新 | 超出窗口的历史压缩 |
   | 长期记忆（情景） | 向量库 | 持久 | 相关历史片段语义召回 |
   | 画像 | Redis | 持久 | 用户偏好/事实 |
   | 业务字段 | Redis | 按会话 | 订单号等实体状态 |

2. 为什么长期记忆"先返回当前会话的摘要，不足五条再补充同用户其他会话，去重且不跨用户检索"？
3. 压缩的并发安全：Lua 同时比较消息快照和旧摘要——为什么"快照比较"能防止后台维护覆盖前台新写入？如果只比较摘要版本会出什么 bug？
4. 摘要生成失败时不裁剪历史、画像提取失败时保留旧值——这些"失败保守策略"为什么重要？
5. 画像更新用"用户级 Redis 租约 + 先写新版本再清理旧版本"——顺序反了会怎样？

**动手实验**：同一用户连续聊 20 轮以上，然后观察：① 工作记忆里还剩几轮；② 摘要是否被多次重写；③ 长期记忆里出现了哪些片段。再用 `redis-cli`（端口 6380）直接看键结构。

---

## 阶段 8：可靠性工程——从 Demo 到生产的距离（2 天）

**这是本项目最值得学的部分**：把 LLM 的不确定性（超时、截断、幻觉、格式错误）变成可处理的系统状态。

**核心文件**：`llm/PromptBudget.java`、`llm/CheckedChatModel.java`、`memory/ConversationStore.java`、`util/AtomicJson.java`（Lua）、`tool/ToolExecutionGuard.java`、`tool/CircuitBreaker.java`、`monitor/PerformanceMonitor.java`

**要回答的问题**：

1. 幂等：同一 `client_request_id` 相同消息返回已完成回执、不同消息返回 409。为什么"防重"要按请求 ID 而不是按会话？
2. 回执边界：进程在模型响应后、回执落盘前崩溃——此时模型调用会重做。这个边界为什么无法避免？哪些措施把它限制在可接受范围？
3. token 预算：为什么用 UTF-8 字节数保守估算而不是调分词器？预算不足时删除"完整旧轮次"而不是截断当前问题——为什么？
4. 超长工具结果转换为含 `truncated=true` 的**合法 JSON**——为什么必须是合法 JSON？（提示：模型对非法 JSON 的反应不可控）
5. 校验失败三态：`pass=false`（转人工，只写转人工回复）、`verification_unavailable`（HTTP 503，可重试，不写会话）、`pass=true`（交付）。为什么"校验系统本身不可用"要单独成态？
6. `PerformanceMonitor`：Z-score 监控、滑动窗口（300 秒/50 次）、惩罚达到阈值进入冷却、冷却后只放行一个探测请求。这套"监控 → 惩罚 → 冷却 → 探测恢复"的闭环和路由层怎么联动？
7. 失败回复不加入正常对话历史——为什么？（提示：模型会从历史里学到"失败也算一次回答"吗？不——是为了下次重试时上下文干净）

**动手实验**：

1. 把 `LLM_TIMEOUT_SECONDS` 调成 2 秒，发一条需要工具循环的消息，观察 503、`retryable=true` 和轨迹。
2. 故意给一个不存在的 `conv_id` 制造边界情况，观察错误码规范（`ApiErrors.java`）。
3. 读 `Hashing.java`（身份哈希脱敏）——为什么轨迹里不存原始 user_id？

---

## 阶段 9：评测与监控——用 LLM 评 LLM（2 天）

**核心文件**：`evaluation/LLMJudge.java`（四维评分）、`evaluation/EndToEndEvaluator.java`（固定回归）、`evaluation/ConversationEvaluator.java`（真实会话）、`evaluation/EvaluationJobs.java`（异步任务）、`evaluation/QualityScores.java`；资源：`src/main/resources/evaluation/customer-service-v1.json`（5 组 Few-shot 校准案例）；`monitor/PerformanceMonitor.java`；`llm/LlmMetrics.java`

**要回答的问题**：

1. LLM-as-Judge 的四维：相关性、准确性、完整性、有用性。为什么需要 Few-shot 校准案例（问题+背景+回复+分数+理由）？不给示例直接让模型打分有什么问题？
2. 回归基线：为什么基线按 suite 分开保存？退化超过 5% 为什么保留旧基线不让退化结果覆盖？（提示：防止"评测系统自己变差了"污染基线）
3. 真实会话评测为什么不计算意图准确率、不更新固定回归基线？（提示：没有人工标注真值）
4. 评测隔离：独立 Agent 统计、意图缓存、工具缓存与熔断，但真实知识库和外部连接共享——为什么这样切分？
5. 评测快照：原始问答 + 工具证据与会话状态**在同一 Lua 中原子提交**——为什么评测要存"当时的工具证据"而不是重新调用工具？
6. 监控指标设计：为什么标签里只放"固定 stage、配置模型、结果、有限错误类别"，不把用户内容或请求 ID 放进去？（提示：指标基数爆炸 + 隐私）

**动手实验**：

1. 跑一次固定回归（18 项），对照 `EvaluationFixtures.java` 和 `customer-service-v1.json` 逐条看输入与分数。
2. 改 `customer-service-v1.json` 里某条案例的评分规则（记得更新版本号），重跑观察回归基线如何因 suite 标识变化而重新建立。
3. 打开 `/actuator/prometheus` 看 `echomind_llm_*` 指标，对照 `LlmMetrics.java` 理解每个指标的标签维度。

---

## 阶段 10：MCP 与生态——标准协议的价值（1 天）

**核心文件**：`integration/StandardMcpClient.java`（官方 Java SDK 客户端）、`tool/RegistryToolProvider.java`（MCP 工具接入 AiServices）、`config/McpServerConfig.java`；配置 `config/mcp_servers.json`；本地服务端在 `api` 相关代码（`/mcp` 端点）

**要回答的问题**：

1. MCP 的三种能力：Tools / Resources / Prompts。本项目只用 Tools——为什么？
2. 为什么外部 MCP 工具默认不可用，必须显式配 `allowed_tools` 和 `agents`？
3. "仅工具参数被发送给外部 MCP，不自动发送完整会话和画像"——这是隐私和安全的什么原则？（最小暴露）
4. 标准 MCP 客户端可以连 EchoMind 自己的 `/mcp` 端点——这意味着什么？（EchoMind 既是 MCP 消费者也是提供者，可以被任何 MCP 客户端/Agent 调用）
5. 分页发现、列表变化刷新、故障重连——这些客户端细节为什么重要？

**动手实验**：用任意一个 MCP 客户端（如 Claude Desktop 或一个简单脚本）连接 `http://localhost:8080/mcp`，调用 `search_local_knowledge`。你会亲身体会"客服系统也能当别人的工具"。

---

## 阶段 11：测试与工程习惯（持续）

**测试清单**（39 个测试文件，是比注释更可靠的文档）：

- 通信协议：`agent/communication/*Test.java`（7 个）
- 工具与安全：`tool/ToolTest.java`、`RoleToolsTest.java`、`WriteToolGuardTest.java`
- 记忆：`memory/MemoryManagerTest.java`、`MemoryMaintenanceTest.java`、`ConversationStateTest.java`
- LLM：`llm/PromptBudgetTest.java`、`LlmMetricsTest.java`、`LangChain4jGatewayTest.java`
- 端到端：`integration/ApiIntegrationTest.java`、`McpReadOnlyTest.java`
- 评测：`evaluation/EvaluationTest.java`、`ConversationEvaluatorTest.java`

**要回答的问题**：

1. 为什么测试模型响应用本地桩（Mockito）而不是真实模型 Key？——确定性、速度、成本。这也是"可测试性设计"的体现：模型调用都收敛在 `LlmGateway` / `ChatModel` 接口后面。
2. 集成测试为什么要求专用 Redis（`-Dtest.redis.port=16379`）？——测试污染。
3. 挑一个你最喜欢的模块，读它的测试：**测试往往比实现代码更清楚地表达了"这个模块的契约是什么"**。

**动手实验**：为 `AnswerVerifier` 或 `ResponseComposer` 补一个测试用例（故意构造边界输入），跑通。写测试是检验你"真懂了"的最快方式。

---

## 三个综合实战项目（学完必做）

1. **加一个 LogisticsAgent（物流查询）**
   - 在 `AgentType` 加枚举；在 `agent/` 下建类；在 `agents/profiles.json` 加角色契约；在 `AgentOrchestrator` 加路由规则；在 `AgentConfig` 加模型/温度配置；加一个 `track_package` 角色工具。
   - 这是理解"如何扩展一个多 Agent 系统"的完整闭环。

2. **加一个知识库工具**
   - 参考 `KnowledgeToolManager` / `RoleToolRegistration` 加一个"按标题精确查找文档"的工具，写清楚 JSON Schema、只读声明、Agent 范围。观察它出现在 `/skills` 和 AiServices 的工具选择里。

3. **用 Java 21 特性重构一个小模块**
   - 比如把某处可以模式匹配的代码改成 `switch` 模式匹配，或把 `Tasks.java` 的并发封装改得更符合结构化并发。目的不是炫技，而是体会"21 的现代 Java"与 Spring Boot 3.5 的配合。

---

## 推荐阅读顺序（如果时间有限）

| 优先级 | 内容 | 理由 |
| --- | --- | --- |
| ⭐⭐⭐ | `ChatService` + `AgentOrchestrator` + `BaseAgent` | 主链路，一切围绕它 |
| ⭐⭐⭐ | `agent/communication/*`（AgentBus 全部） | 本项目最独特的部分 |
| ⭐⭐⭐ | `ConversationStore` + `AtomicJson` | 生产级可靠性的精髓 |
| ⭐⭐ | `TextChunker` + `KnowledgeBaseService` | RAG 细节 |
| ⭐⭐ | `ToolExecutionGuard` + `CircuitBreaker` | 工具安全 |
| ⭐⭐ | `PromptBudget` + `CheckedChatModel` | LLM 工程边界 |
| ⭐ | `LLMJudge` + `EndToEndEvaluator` | 评测 |
| ⭐ | `PerformanceMonitor` | 监控 |

---

## 外部学习资源

- [LangChain4j 官方文档](https://docs.langchain4j.dev/)——AI Services、Tools、Chat Memory 三个教程是阶段 3 的必读
- [Anthropic 平台文档](https://platform.claude.com/docs)——本项目两个 profile 都走 Anthropic Messages 协议
- [MCP 规范](https://modelcontextprotocol.io/)——阶段 10
- [DeepSeek Anthropic 兼容接口文档](https://api-docs.deepseek.com/guides/anthropic_api/)——理解"协议兼容"如何让一套代码接两家模型
- 论文级概念（按需查）：ReAct（工具循环）、RAG、Reflexion（批判-修订的渊源）、LLM-as-a-Judge、Multi-Agent Debate
- 本仓库其他资料：`docs/echomind-llm-api-flow.html`（LLM API 流程图）、`rag-eval/README.md` 与 `rag-eval/BASELINE.md`（RAG 检索评测）、`skills/` 下的三个业务技能包

---

## 学习建议（方法论）

1. **先跑起来再读代码**：有体感才能读懂"为什么这么设计"。
2. **一次只追一条线**：以一次请求为主线贯穿读，不要按包顺序平铺读。
3. **测试是活文档**：拿不准一个类的契约时，先读它的测试。
4. **画图**：架构图、时序图、AgentBus 通信图——画出来才算真的理解了。
5. **带着问题读**：每个阶段列出的问题，先自己尝试回答再看代码验证。
6. **动手改**：改配置、加日志、写测试、加工具——被动阅读的留存率远低于动手实验。
7. **关注"边界"**：这个项目最值钱的知识都在边界上——超时边界、预算边界、熔断边界、回执边界、降级边界。读代码时始终问"如果这里失败了会怎样？"

---

## 深挖问题清单（检验学习成果）

如果你能不看代码回答下面这些问题，说明你已经吃透了本项目：

1. 为什么 ASK 超时要降级继续作答，而 DELEGATE 是终止性的？
2. 输入预算不足时，为什么删整轮历史而不是截断当前问题？工具结果为什么可以截断？
3. 校验失败（转人工）和校验不可用（503 可重试）在存储层有什么区别？
4. 为什么写工具的操作前回执要先落盘再执行？落盘失败为什么直接不执行？
5. 快照比较 + Lua 提交如何同时解决"前台写入 vs 后台压缩"和"并发维护覆盖"两类竞态？
6. 熔断冷却结束为什么要先放行一个探测请求？
7. 为什么向量集合前缀要含模型版本？
8. 回归基线为什么按 suite 分桶、退化时保留旧基线？
9. 为什么 AgentBus 需要树深度 ≤2 和访问栈循环检测两道"防循环"闸？
10. 评测为什么保存"当时的工具证据"而不是评测时重新调用工具？

---

*文档版本：v1（2026-11）。随代码演进，以 `README.md` 与源码为准。*
