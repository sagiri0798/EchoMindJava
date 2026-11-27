# EchoMind Java

参考同目录 Python 项目 `EchoMind` 的当前实现，使用 Java 21、Spring Boot 3.5 和 LangChain4j 1.12.2 实现客服 Agent。支持 Anthropic / DeepSeek、三路意图识别、多 Agent 协作、知识库工具、标准 MCP、分层记忆、异步评测和在线监控。

**模型调用和工具消息统一采用 LangChain4j，已移除 Spring AI 依赖。Anthropic 和 DeepSeek 均使用 `AnthropicChatModel`。**

## 请求流程

```text
POST /chat
  → Bearer 身份与权限校验（可选）
  → Redis 会话锁与请求回执检查
  → 工作记忆、摘要、长期记忆、画像与业务字段
  → LLM + Embedding + Pattern 意图融合
  → General / Technical / Billing 路由；复合问题并行协作
  → LangChain4j AiServices（由模型按需选择工具）
      → 本地知识库：查询改写 → 并行召回 → BM25 + 向量召回 → LLM 重排
      → 外部 MCP：白名单、Agent 范围、Schema、超时、缓存与熔断
  → 回答校验：通过后交付；不合格转人工；校验不可用返回可重试失败
  → Lua 原子提交本轮消息、业务状态与请求回执
  → 有界后台队列压缩记忆和更新画像
```

人工升级由独立 `EscalationAgent` 生成含已知实体、原因和优先级的交接摘要，不调用模型；真实订单查询、退款执行和工单创建仍需接入业务系统。

## 框架职责

Spring Boot 管理接口、依赖注入、鉴权、业务路由、存储和监控。LangChain4j 管理模型协议、AI Service 消息交换、工具循环和可选语义召回；文档切片由独立的 `TextChunker` 处理。当前使用显式 Spring Bean 与 `AiServices.builder` 装配，无需依赖 Starter 才能使用 AI Services。

- `AgentAssistant` 声明 AI Service 接口，`AgentAiService` 为每次调用创建独立的 `MessageWindowChatMemory`，返回保留完成原因和 token 用量的 `Result<String>`。历史只接受 user / assistant 角色，摘要和业务状态作为独立背景传入。
- `BaseAgent` 负责角色提示、动态 Skills、统计和失败响应；`AgentOrchestrator` 负责业务路由及协作，不再维护底层工具消息循环。
- `RegistryToolProvider` 将动态本地 / MCP 工具接入 LangChain4j，`ToolRegistry` 保留权限、校验、超时、缓存和熔断。当前参数模型不支持根级 `anyOf` / `allOf` 等约束，注册时明确拒绝，避免静默丢失；属性内部的原始 Schema 约束保留。
- `ManagedChatModel` 对每轮请求执行输入预算检查并记录真实模型调用指标；`CheckedChatModel` 在执行工具或接受答案前拒绝截断、内容过滤及其他非完整响应。辅助文本任务经 `LlmGateway` 调用同一模型；画像、摘要、Judge 使用严格调用，不接受本地降级文本。
- `ConversationStore` 是持久化会话的唯一提交入口。AI Service 的记忆仅在单次运行内暂存，成功后由既有 Lua 一次性提交消息、状态和回执，避免双重写入。

这些边界对应官方的 [AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)、[Tools](https://docs.langchain4j.dev/tutorials/tools/) 与 [Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/) 用法。业务层的会话锁、幂等、鉴权和路由继续由应用负责。

## 与 Python 项目对齐的能力

| 能力 | Java 实现 |
| --- | --- |
| 意图识别 | 保留细粒度意图和三路融合；支持硅基流动 `BAAI/bge-m3`，整次匹配可回退本地 n-gram |
| Agent 工具选择 | AiServices + ToolProvider / ToolExecutor；最多 4 轮工具执行、每轮最多 8 个调用；入口不按意图预取知识库 |
| 多 Agent | 主辅路由、并行处理、回复整合、专业 Agent 失败后 General 降级 |
| 会话可靠性 | Redis 锁续租、锁失效保护、50 秒总预算、24 小时防重回执 |
| 多轮状态 | 已提取实体继承、来源追踪；切换订单或业务领域时清理过期字段 |
| 知识库导入 | 独立句子切片、500 Unicode 码点上限和最多 80 码点重叠；字符层保留完整 Unicode 码点；标题 / 显式 ID 分离，重导入删除多余旧片段 |
| 工具管理 | 通用注册表、JSON Schema 校验、Agent 范围、只读约束、TTL 缓存、超时、带代次的熔断 |
| 标准 MCP 客户端 | 官方 Java SDK，Streamable HTTP / stdio，初始化、分页发现、列表变化刷新、故障重连 |
| 标准 MCP 服务端 | `/mcp`，暴露 `search_local_knowledge` 和 `echomind://knowledge/stats` |
| 记忆维护 | 请求内 ChatMemory 按原生角色传入历史；后台压缩用快照比较和 Lua 提交；模型失败保留原历史和画像 |
| 轨迹 | Redis 保存 7 天、最近 200 条；身份哈希及错误信息脱敏；保留外部 MCP 来源 |
| 评测 | HTTP 202 任务、进度轮询、四维 Judge、Few-shot 校准、业务断言、同 suite 回归基线 |
| 评测隔离 | 独立 Agent 统计、意图缓存、工具缓存与熔断；真实知识库和外部连接仍共享 |
| 监控 | 单次聊天耗时直方图、成功率、近期 300 秒 / 最多 50 次路由窗口、冷却恢复探测、Z-score、告警恢复 |
| 权限与 CLI | 可选用户 / 管理员 Token，生产模式强制配置；`--cli` 交互模式 |

知识库和长期记忆默认使用 LangChain4j 官方内置的 `all-MiniLM-L6-v2` 生成真实 384 维语义向量，不需要额外 Embedding Key。开发环境使用本地持久化向量存储，Compose 使用官方 Chroma 适配器。知识片段、情景记忆和用户画像分三个集合，集合前缀包含模型版本，禁止与 Python 的既有集合混写。在线 BGE-M3 **只用于意图识别**。Java 继续保留 BM25 融合召回和 JSON 文档目录，排序及存储结构不要求与 Python 逐字一致。

工具默认只读。写工具需同时开启 `MCP_WRITE_ENABLED=true`、对应服务器配置 `read_only=false`，并满足工具名及角色白名单。聊天服务在执行前将 `tool_outcome_unknown`、`retryable=false` 回执持久化到 Redis；保存失败不执行操作。同一请求禁止重复执行相同工具和参数；尝试写操作后不再通过 General 重做。只有完成回答校验和最终提交，才替换该回执。进程崩溃、模型失败或超时后，重试会返回待人工核验的原回执，不自动重放操作。直接 MCP 调试接口和评测不执行写工具。真实业务工具仍需在其接口中落实身份、业务授权及幂等键；这里没有虚构退款、改订单或工单服务。

## 本地运行

需要 JDK 21+、Redis、Maven Wrapper（首次构建需下载依赖）。

```bash
cp .env.example .env
# .env 供 Compose 读取，本地 Maven 启动需自行 export 对应变量。
docker compose up -d redis

export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=你的Key
# Compose 中 Redis 映射宿主机 6380；已有本机 Redis 时按实际端口设置。
export REDIS_PORT=6380
export REDIS_PASSWORD
./mvnw spring-boot:run
```

Anthropic 切换为 `SPRING_PROFILES_ACTIVE=anthropic`，填写 `ANTHROPIC_API_KEY`。

两个 profile 都使用 LangChain4j 的 `AnthropicChatModel`，通过 Anthropic Messages 协议调用：

| profile | API Key | 默认 Base URL | 默认模型 |
| --- | --- | --- | --- |
| `anthropic` | `ANTHROPIC_API_KEY` | `https://api.anthropic.com` | `claude-sonnet-4-6` |
| `deepseek` | `DEEPSEEK_API_KEY` | `https://api.deepseek.com/anthropic` | `deepseek-flash` |

DeepSeek 默认模型为 `deepseek-flash`（官方当前映射为 V4.1 Flash），不再使用旧的 `deepseek-chat` 名称；`deepseek-v4-flash` 是兼容旧名，也会路由到最新 Flash。通过 `thinking.type=disabled` 显式使用非思考模式，适配客服短回复、JSON 校验和现有调用预算。模型映射以[官方模型说明](https://api-docs.deepseek.com/)为准。

DeepSeek 使用[官方 Anthropic 兼容接口](https://api-docs.deepseek.com/guides/anthropic_api/)。模型名分别由 `ANTHROPIC_MODEL`、`DEEPSEEK_MODEL` 覆盖。程序补齐 `/v1` 路径，最终调用 `/v1/messages`；旧的 `https://api.deepseek.com` 或 `/v1` 配置会自动转换为 `/anthropic/v1`。第三方网关需提供 Anthropic 兼容地址。

配置位于 `langchain4j.anthropic.chat-model` 和 `langchain4j.deepseek.chat-model`；`LLM_TIMEOUT_SECONDS` 默认 30 秒，框架内部重试关闭，继续由请求预算、工具层和 Judge 控制重试。

Anthropic 默认模型更新为 `claude-sonnet-4-6`。官方 API 的 Sonnet 3.5 已退役，配置有效 Key 后仍指定这些已知旧模型会在启动时明确报错；自定义代理可以保留自己的模型别名。模型生命周期以 [Anthropic 官方说明](https://platform.claude.com/docs/en/about-claude/model-deprecations) 为准。启动和测试不会自动发送付费模型探测请求。

每轮请求使用统一输入预算（包含系统提示、历史、当前问题、工具 Schema 和结果）：`LLM_MAX_INPUT_TOKENS=48000`，背景分配 `LLM_MAX_BACKGROUND_TOKENS=4000`，单个工具结果 `LLM_MAX_TOOL_RESULT_TOKENS=2000`。当前估算器按 UTF-8 字节数保守估算 token，并加协议开销，不依赖某一模型的分词器；这些数值是估算预算，输出 token 额度单独设置，部署时应按模型上下文大小预留余量。

预算不足时删除完整的旧对话轮次，保留当前问题与工具调用 / 结果关系；当前输入或工具 Schema 本身过大时明确失败，不静默截断用户问题。过长工具结果转换为含 `truncated=true`、片段和提示的合法 JSON，同一份有限资料也用于回答校验。


```bash
curl http://localhost:8080/health
# Swagger：http://localhost:8080/docs
```

没有有效模型 Key 时仍能启动、管理知识库并查看健康状态；需要模型的聊天会明确返回 `status=failed`、HTTP 503 和重试信息。普通辅助文本调用保留 `LLM_FALLBACK_ENABLED` 降级开关；Judge、Agent、画像及摘要调用不会把本地提示当作成功模型结果。摘要生成失败时不裁剪历史；画像提取失败时保留旧值，有效新画像与已有事实去重合并，并限制条目数和长度。每次压缩会合并旧摘要与新增对话并替换旧摘要，摘要最多 1500 个估算单位；Lua 同时比较消息快照和旧摘要，避免并发维护覆盖更新。

CLI 同样需要 Redis 和模型配置：

```bash
./mvnw package
java -jar target/echomind-java-0.1.0.jar --cli
# /new 新会话，/quit 退出
```

## 对话与重试

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"我想申请退款，订单号 #12345","user_id":"u1","conv_id":"c1","client_request_id":"turn-1"}'
```

新消息生成新的 `client_request_id`，重试原消息时复用它。同一用户、会话下，相同 ID 和消息返回已完成回执；不同消息返回 HTTP 409。不传 `conv_id` 时根据用户与请求 ID 生成稳定会话标识。兼容输入 `conversation_id`，响应统一使用 `conv_id`。

响应包含 `request_id`、`agent_types`、`tools_used`、`entities`、`intent_embedding_source`、`status`、`retryable`、`error_code` 等字段。 回答校验只有 `pass=true` 且 `grounded=true` 才交付原答案。明确拒绝时返回转人工回复（`partial`、`answer_verification_rejected`），只把该回复写入会话；校验超时、模型故障或结果格式错误返回 HTTP 503（`verification_unavailable`、可重试），不写入未核验答案，使用同一请求 ID 可恢复重试。`knowledge_used` 依据实际成功的知识库工具调用计算。工具失败后的可用回复标记 `partial`；所有 Agent 失败返回 HTTP 503。

防重保证以成功提交的回执为边界；进程在模型响应后、回执落盘前崩溃时，模型调用可能重做。消息与回执使用 Lua 原子写入，失败回复不加入正常对话历史。

## 知识库

```bash
curl -X POST http://localhost:8080/knowledge/add \
  -H 'Content-Type: application/json' \
  -d '{"documents":[{"title":"退款政策","document_id":"policy-refund","content":"退款申请需先审核。"}]}'

curl -X POST http://localhost:8080/knowledge/upload -F 'file=@policy.md'
curl -X POST 'http://localhost:8080/search?query=退款&top_k=3'
```

支持 `.txt`、`.md`、文档数组 `.json`，最大 10 MB，必须是 UTF-8。JSON 数组使用与 `/knowledge/add` 相同的文档字段。

未传 `document_id` 时同标题覆盖；传入后按 ID 更新，允许同标题不同来源。原子文件替换完成后才发布新索引，失败不会返回导入成功。旧版仅带标题的片段会在同标题重导入时迁移。

切片由独立 `TextChunker` 完成，不调用 LangChain4j 切片器。按 Unicode 码点计数，在窗口后半段优先寻找句末或换行边界，超长无标点文本也保证前进；每片至多 500 码点，重叠最多 80 码点。已有片段保留，重新导入后采用新边界。

语义召回由 `SemanticStorage` 和 `SemanticKnowledgeIndex` 装配，模型及 Chroma 适配器均使用 LangChain4j 官方实现：

```bash
# 开发环境：默认真实本地模型 + 三个持久化向量文件
export SEMANTIC_STORAGE_MODE=local
export SEMANTIC_LOCAL_DIR=data/java/vectors

# 独立服务：Compose 已配置，宿主机运行使用映射端口
export SEMANTIC_STORAGE_MODE=chroma
export CHROMA_BASE_URL=http://localhost:8002
export CHROMA_API_VERSION=V2
export CHROMA_COLLECTION_PREFIX=echomind_java_minilm_v1
curl http://localhost:8080/integrations/semantic
```

`CHROMA_TENANT` / `CHROMA_DATABASE` 默认 `default_tenant` / `default_database`。生产环境要求 `chroma`；连接、建索引或查询失败会明确报错，不隐式退回本地库。`disabled` 仅用于测试或明确的开发降级，此时知识库使用 BM25 + hash vector。开发环境 local 语义分支故障有明确降级日志。

知识库在导入和启动时分批生成向量，通过 `EmbeddingStoreContentRetriever` 检索并与 BM25 融合。重导入替换对应向量和清理旧片段；查询校验内容哈希和索引版本，避免混用更新前后的片段。JSON 文档目录仍是知识库重建、导入覆盖的依据；向量集合保存片段内容、元数据和向量。当前目录协调限于单进程，不支持多实例同时修改同一知识库目录。

长期记忆使用相同语义模型，先返回当前用户当前会话的相关摘要，不足五条时再补充同用户其他会话，去重且不跨用户检索。画像更新由用户级 Redis 租约保护，先写新版本再清理旧版本，读取不使用进程本地旧画像。旧 `memory-store.json` 在启动时导入语义库：情景记忆按稳定身份去重，已有新画像不被旧文件覆盖。后台维护按用户串行、按会话合并；运行期间的新提交会在下一轮处理，不再直接丢弃。

旧版 Redis 明文拼接键不会自动迁移到新的哈希键，旧键按原 TTL 过期。

## 在线意图 Embedding

```bash
export INTENT_EMBEDDING_PROVIDER=auto  # auto / local / siliconflow
export SILICONFLOW_API_KEY=你的Key
curl http://localhost:8080/integrations/embedding
```

固定访问硅基流动 `BAAI/bge-m3`，模板每批最多 32 条，匹配总预算 8 秒，失败冷却 60 秒。校验数量、索引、维度、有限数值和范数；失败时问题与模板一起切回本地向量空间。

`online_configured=true` 表示已配置 Key；实际生效以聊天响应 `intent_embedding_source=siliconflow/BAAI/bge-m3` 为准。真实模型权限、限流和费用以服务商为准。

## MCP 与 Skills

`config/mcp_servers.json` 默认配置 Microsoft Learn 的搜索和抓取工具，仅 General / Technical 可用。`MCP_ENABLED=false` 可以关闭外部连接；本地 `/mcp` 服务仍可使用。

```bash
curl http://localhost:8080/integrations/mcp
curl -X POST http://localhost:8080/integrations/mcp/call \
  -H 'Content-Type: application/json' \
  -d '{"name":"mcp_mslearn_microsoft_docs_search","arguments":{"query":"HTTP 401"}}'
```

配置必须显式指定 `allowed_tools`、`agents`；认证头从 `headers_env` 的环境变量读取。stdio 使用 `command`、`args`，通过 `env_vars` 列表显式传递额外环境变量。返回资料有大小限制，裁剪后仍为合法 JSON，并尽量保留小型结构化结果。仅工具参数被发送给外部 MCP，不自动发送完整会话和画像。

标准客户端可连接 `http://localhost:8080/mcp`，调用 `search_local_knowledge` 或读取 `echomind://knowledge/stats`。开启鉴权时需传 Bearer Token。

业务 Skills 位于 `skills/`，通过 `GET /skills` 查看、`POST /skills/reload` 热加载。

## Agent 角色契约与工具

`src/main/resources/agents/profiles.json` 保存与 Python `AgentProfile` 对齐的职责、处理流程、输入输出约束、升级条件及工具范围。模型仍通过 LangChain4j `AiServices` 调用，不维护另一套工具消息循环。每个角色的输入包包含意图、紧急度、置信度和本轮已合并实体；Billing 和 Technical 额外带业务边界。

| 角色 | 专属本地工具 | 默认温度 / 最大输出 token |
| --- | --- | --- |
| General | `inspect_request_context`、`suggest_required_fields` | 0.3 / 900 |
| Technical | `lookup_error_code`、`build_diagnostic_plan` | 0.1 / 1200 |
| Billing | `check_billing_fields`、`compare_amounts` | 0.0 / 1100 |
| Escalation | `create_handoff_summary` | 确定性执行，不调用模型 |

所有角色均声明知识库检索能力。角色工具从服务端传入的本轮 `AgentRequest` 读取实体，不接受模型伪造的用户或实体字段，也不使用跨请求共享结果缓存。金额差值使用 `BigDecimal`，不会据此判断重复扣款或执行退款。错误码工具不读取真实服务日志；排障工具不修改系统配置。

可通过 `ECHOMIND_GENERAL_MODEL`、`ECHOMIND_TECHNICAL_MODEL`、`ECHOMIND_BILLING_MODEL` 覆盖当前 Provider 下的模型名；空值继承当前模型。相应 `ECHOMIND_<ROLE>_TEMPERATURE` / `ECHOMIND_<ROLE>_MAX_TOKENS` 覆盖温度和输出预算，也支持 `echomind.agents.<role>.model` / `temperature` / `max-output-tokens` 配置。非法数值会在启动时明确报错。

`ResponseComposer` 负责协作回复的主次、冲突处理和去重。`ECHOMIND_COMPOSER_MODEL` / `TEMPERATURE` / `MAX_TOKENS` / `TIMEOUT_S` 配置汇总调用；超时或失败时保留主回复和补充说明，不向用户暴露内部 Agent 标签。

只读模式下，外部 MCP 工具同时满足服务配置白名单、角色范围、`readOnlyHint=true` 且 `destructiveHint!=true` 才会注册。缺少只读声明或声明为破坏性操作的工具会被过滤；重新发现时也会移除已不符合条件的工具。本服务自己的知识检索工具提供相应只读声明。默认配置下不开放写工具；显式启用写工具时仍必须经过操作前回执保护，调试接口不可绕过。

## Agent 通信协议（AgentBus）

在 hub-and-spoke 路由之上增加了一层**请求级多 Agent 通信协议**：Agent 在生成答案途中可以通过工具调用与其他 Agent 对话，所有消息经过收敛控制器并落成每请求一颗的通信图。

**类型化消息**：`ASK`（阻塞子问题求助）、`DELEGATE`（终止性转交）、`PROPOSE`（并行模式辅助结论）、`CRITIQUE`（校验器负评）、`ACCEPT`（composer 采纳）、`ESCALATE`（升级交接），携带 sender/receiver/轮次/时间戳/结果。实现位于 `com.echomind.agent.communication`。

**三种交互模式**：

- **求助（ask_agent 工具）**：如 Technical 排查时把退款政策作为子问题阻塞问 Billing，把结论融合进最终回复。单次 ASK 超时 8 秒，超时/目标熔断/循环时返回"未获支持"标记，发起方**降级继续作答而不是失败**。
- **委托（delegate_agent 工具）**：General 的 `triage_targets` 提示变为真实路由——确认超出能力范围时把整个请求（含原始写保护）移交目标 Agent，委托方成为主答者。
- **批判-修订**：`AnswerVerifier` 判负不再是死胡同——负评变成 `CRITIQUE` 消息注入原 Agent 的系统提示，修订一轮（封顶 1 轮）后复判；仍不过才走原升级兜底。

**收敛控制**（`ConvergenceController`）：每请求通信预算 20 秒（50 秒 SLA 内）、消息数 ≤6、树深度 ≤2、访问栈循环检测（A→B→A 立即终止并盖 `cycle_detected`）。ASK 目标选择复用 `AgentStats.routingScore()`——熔断冷却中的 Agent 不会被问。

**双模路由**：简单请求保持原有快路径（单 Agent / 并行+composer，零回归）；跨域意图、多实体、General 主路由时进入协商模式注入通信工具。并行模式不改各 Agent 行为，只在共享通信图上标注 `PROPOSE`/`ACCEPT`。

**消息图轨迹**：`GET /trace/bus/{requestId}` 与 `GET /trace/bus?limit=20` 返回每请求的通信 DAG（JSON），供前端渲染与排障。配置项 `echomind.bus.*`（`ECHOMIND_BUS_*` 环境变量）：`enabled`、`max-messages`、`max-depth`、`budget-ms`、`ask-timeout-ms`、`critique-revise`。

演示：问"我的应用一直报 500，会不会重复扣了我的订阅费"触发跨域协商；`/trace/bus/{request_id}` 查看 Technical↔Billing 的 ASK 链。

## 异步评测

前端提供两种评测入口：

- **运行评测**：执行固定回归用例，包括 11 条细粒度意图用例、4 条单轮对话和 1 组 3 轮对话（共 18 项）。默认用例、业务参考答案及核验资料与 Python `evaluation/evaluator.py` 一致。
- **评测当前会话**：读取提交时已保存的原始问答快照，默认评测最近 20 轮，最多 50 轮；使用之前的真实问答及当时保留的工具证据，不重新调用 Agent 或业务工具。前端可填写补充参考答案与核验资料，报告显示逐轮原始回复、四维分数及评分错误。

两个入口均使用 `customer-service-v1` 的 5 组完整 Few-shot 校准案例（与 Python `evaluation/few_shot.py` 相同），按相关性、准确性、完整性和有用性评分。案例包含问题、背景、回复、四维分数及理由。资源位于 `src/main/resources/evaluation/customer-service-v1.json`，修改案例或评分规则后应更新版本；回归 suite 标识包含案例、角色参数及 Judge 配置，避免与旧基线混比。报告汇总四维质量、通过率和有标注的意图指标；无意图样本时不伪造 0 分。基线按 suite 分开保存，只和相同 suite 的完整且无回归基线比较；质量、通过率退化超过 5% 时保留原基线，不让退化结果覆盖。

```bash
# 固定回归用例（开启鉴权时需要管理员 Token）
curl -X POST http://localhost:8080/eval/run -H 'Content-Type: application/json' -d '{}'

# 角色工具专项：非紧急 401 排障、精确金额差值、人工交接（3 项）
curl -X POST http://localhost:8080/eval/run \
  -H 'Content-Type: application/json' \
  --data-binary @src/main/resources/evaluation/agent-parity-cases.json

# 真实会话（开启鉴权时 user_id 由当前 Token 身份覆盖）
curl -X POST http://localhost:8080/eval/conversation \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"u1001","conv_id":"已有会话ID","max_turns":20}'

curl http://localhost:8080/eval/jobs/返回的job_id
```

提交返回 HTTP 202 和 `job_id`，轮询接口返回状态、进度及结果。任务按提交账户隔离。评分失败项不计入平均分；全部失败时没有有效分数。真实会话没有人工标注的意图真值，因此不计算意图准确率，也不更新固定回归基线。缺少可靠资料时，LLM 评分不能代替业务事实核验。

原始问答、当时的有限背景及工具证据与会话状态、回执在同一 Lua 中原子提交，按用户和会话隔离。原文最多保留 100 轮，TTL 与工作记忆一致（默认 24 小时），不受工作记忆摘要压缩影响。旧版本会话会读取仍存在的工作记忆，并在下一次成功提交时迁入原文记录；已经压缩或过期的原文无法恢复，报告会注明范围不完整。

## 鉴权与部署

开发模式不配置 Token 时可在本机使用。生产模式必须配置：

```bash
export ECHOMIND_ENV=production
export ECHOMIND_API_TOKENS='{"替换为至少24字符的用户Token":{"user_id":"u1","role":"user"},"替换为至少24字符的管理员Token":{"user_id":"admin","role":"admin"}}'
```

鉴权开启后，聊天用户来自 Token，忽略请求体伪造的 `user_id`。普通用户可访问聊天、检索、知识库统计、Skills 摘要和 MCP；普通用户还可评测自己的真实会话并读取自己提交的评测任务；导入、重载、固定回归评测、监控、轨迹和集成管理需要管理员。`/health` 无需 Token。

```bash
docker compose up -d --build
```

Compose 端口仅绑定本机：应用 8080、Nginx 8081、Redis 6380、Prometheus 9091、ChromaDB 8002。应用镜像包含 Skills 和 MCP 配置，数据保存在 `app-data` 卷。ChromaDB 实际承载三个语义集合，JSON 文档目录保留在应用数据卷中。开启鉴权时 Prometheus 的 `/metrics` 抓取也需要管理员 Bearer 凭据。

| 入口 | 用途 |
| --- | --- |
| `/health`、`/docs`、`/v3/api-docs` | 健康与 API 文档 |
| `/chat`、`/search` | 聊天与知识库检索 |
| `/knowledge/add`、`/knowledge/upload`、`/knowledge/stats` | 知识库管理 |
| `/skills`、`/skills/reload` | Skills |
| `/integrations/embedding`、`/integrations/semantic`、`/integrations/mcp`、`/integrations/mcp/call` | 集成状态与调试 |
| `/mcp` | 标准 MCP 服务 |
| `/eval/run`、`/eval/conversation`、`/eval/jobs/{job_id}` | 固定回归、真实会话评分及任务轮询 |
| `/monitor`、`/metrics`、`/actuator/prometheus` | 监控 |
| `/trace/tool/{request_id}`、`/trace/tools` | 脱敏工具轨迹 |

模型指标通过 `/metrics` 和 `/actuator/prometheus` 暴露：`echomind_llm_calls_total`（调用数）、`echomind_llm_duration_seconds`（耗时）、`echomind_llm_tokens_total`（input / output）、`echomind_llm_fallbacks_total`（降级 / 拒绝）。调用标签包含固定 stage、配置模型、结果和有限错误类别，不把用户内容或请求 ID 放入标签。未提供 token usage 的响应不伪造用量；工具多轮调用逐次计数，截断回复也保留提供方已报告的 token 用量。工具重排标记同时保留在缓存结果和轨迹中。

## 测试

```bash
./mvnw test
# 指向专用测试 Redis，运行 HTTP、会话和 MCP 集成测试；不要使用生产 Redis。
./mvnw -Dtest.redis.port=16379 -Dtest.chroma.url=http://localhost:8002 verify
./mvnw package
```

默认测试覆盖切片、导入覆盖、缓存失效、工具 Schema / 权限 / 熔断 / 超时、语义召回和向量清理、向量校验与降级、AiServices 工具循环与次数限制、原生消息角色和请求隔离、截断拒绝、输入预算与 Unicode / JSON 截断、模型调用指标、慢检索并发及快照一致性、校验失败交付策略、摘要合并与并发保护、模型故障时保留画像和历史、评测失败语义、状态继承和监控恢复。设置 `test.redis.port` 后增加真实 Redis、随机端口 Spring Boot、官方 MCP 客户端与外部客户端互通验证；测试模型响应由本地桩提供，不需要真实模型 Key。

在线模型质量、真实硅基流动调用、Microsoft Learn 外网可用性和 Docker 整套部署应在目标环境另行验收。

监控惩罚达到 `ECHOMIND_MONITOR_FALLBACK_PENALTY`（默认 0.5）时，专业 Agent 进入 `ECHOMIND_MONITOR_COOLDOWN_SECONDS`（默认 60 秒）冷却，路由层剔除该角色。冷却结束只放行一个探测请求，成功后清空旧窗口和惩罚。复合问题优先按领域信号补充协作角色，再用分数阈值兜底，避免低分的第二诉求被忽略。
