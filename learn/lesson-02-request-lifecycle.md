# 第二节：跟踪一次请求的全生命周期

> 对应《LEARNING_GUIDE.md》的「阶段 1（核心！）」。第一节你**看到了**系统暴露的信号；这一节你要**亲手追出**这些信号是怎么产生的：从 `EchoMindController` 一路走到 Lua 原子提交，覆盖会话锁、幂等回执、记忆组装、意图融合、路由、校验、存储、后台维护九个环节。这是整个课程最重要的一节。
>
> 预计用时：1～2 天。**本节所有 PowerShell 命令都已在 Windows PowerShell 5.1 + 本项目实测通过**（第一节的坑见 §2.1，这一节不会再踩）。

---

## 本课目标

学完本课你应该能做到：

1. 不看代码画出一次 `/chat` 请求的完整调用链（九环节）
2. 说出每个环节「防什么、失败会怎样、谁负责可靠」
3. 用 Redis 亲眼看到锁、回执、工作记忆、状态四类键的生死
4. 制造并解释幂等回执的三种分支（命中 / 409 / 重做）
5. 读懂响应里的 `routing_reason`、`intent_source_scores` 等字段对应代码里的哪一行
6. 解释 50s / 20s / 8s / 5s / 15s / 86400s 这一组数字之间的嵌套关系
7. 回答文末 8 道思考题

---

## 0. 课前核对：你的猜想图 vs 真实调用链

第一节的课前准备让你读 `ChatService.java` 和 `EchoMindController.java`，画出「你认为的调用顺序」。现在对答案——真实调用链长这样：

```
HTTP POST /chat
├─ EchoMindController.chat          # 取鉴权账号；status=failed → HTTP 503（EchoMindController.java:78-84）
├─ ChatService.chat                 # 用户/会话归一；50 秒总预算（ChatService.java:31-45）
│  ├─ ① 会话锁        ConversationStore.acquire      # Redis setIfAbsent，15s TTL，4s 续租
│  ├─ ② 幂等回执      ConversationStore.receipt      # 命中→直接返回；换消息→409；失败可重试→重做
│  ├─ ③ 记忆组装      MemoryManager.getContext       # 工作记忆 + 摘要 + 相关历史 + 画像 + 业务状态
│  ├─ ④ 意图融合      IntentRecognizer.recognize     # LLM 0.70 + Embedding 0.20 + Pattern 0.10
│  ├─ ③' 状态合并     ConversationState.merge       # 实体继承/切换，domain 跟踪
│  ├─ ⑤ 路由          AgentOrchestrator.run         # 澄清 / CRITICAL升级 / 打分 / 并行 / 协商
│  │  └─ ⑥ 工具循环   AgentAiService.reply          # 快路径 4 轮×8 工具；证据收集
│  ├─ ⑦ 校验          AnswerVerifier.verify         # 5s；pass+grounded；批判修订封顶 1 轮
│  └─ ⑧ 原子提交      ConversationStore.commit      # Lua：锁token校验 + wm/history双写 + 回执 + 状态
└─ ⑨ 后台维护         MemoryMaintenance.submit      # 异步：压缩工作记忆 + 更新画像
```

**每个环节对应的「它回答的问题」**：

| 环节 | 文件:行 | 它在回答 | 第一节你已见过的现象 |
| --- | --- | --- | --- |
| ① 会话锁 | `ConversationStore.java:58` | 同一会话的两个请求能并发吗？ | （本节的实验 2 第一次见） |
| ② 幂等回执 | `ChatService.java:49` | 这个请求是不是已经做过？ | 重复提交拿到一模一样的 JSON |
| ③ 记忆组装 | `MemoryManager.java:60` | 模型需要知道哪些背景？ | 多轮追问时实体在延续 |
| ④ 意图融合 | `IntentRecognizer.java:92` | 用户在问什么类别？ | 响应里的 `intent_source_scores` |
| ⑤ 路由 | `AgentOrchestrator.java:73` | 谁来答？一个还是多个？ | `routing_reason`、`agent_types` |
| ⑥ 工具循环 | `AgentAiService.java:55` | 需要哪些证据？ | `tools_used`、`/trace/tool` |
| ⑦ 校验 | `AnswerVerifier.java:23` | 答案可信吗？ | `verified`、`grounded` |
| ⑧ 原子提交 | `ConversationStore.java:34` | 怎么可靠地记下来？ | Redis 里的 `wm:` / `receipt:` / `state:` |
| ⑨ 后台维护 | `MemoryMaintenance.java:16` | 记忆不膨胀、画像保鲜 | （本节的实验 6 第一次见） |

> **读代码姿势**：在 IDEA 里从 `ChatService.chat()` 出发，Ctrl+点击一路追下去。每进入一个环节前先自问三句话：
> 1. 这个环节**防的是什么**？
> 2. 如果它**失败了会怎样**？（这是第一节思想 #2：「一切不可靠都有兜底」）
> 3. **谁负责可靠**——模型还是代码？（记住思想 #1：「LLM 只是组件，工程是骨架」）

---

## 1. 九个环节逐个深读

### 环节 0：HTTP 入口——`EchoMindController.chat`（`EchoMindController.java:78`）

```java
public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http) {
    var account = (AccessFilter.Account) http.getAttribute("echomind.account");
    ChatResponse response = chats.chat(request, account == null ? null : account.userId());
    return ResponseEntity.status(response.status().equals("failed") ? 503 : 200).body(response);
}
```

控制器只做三件事：**取鉴权结果**（`AccessFilter` 放在 request attribute 里，开发模式没有）、**委托给 ChatService**、**把 `status=failed` 映射成 HTTP 503**。

> 注意一个细节：响应 JSON 里的 `status` 字段和 HTTP 状态码**是两套东西**。`status=partial`（部分环节失败但回答可用）时 HTTP 仍然是 200；只有 `failed` 才映射 503。客户端应该先看 HTTP 码，再看 JSON 字段。

### 环节 1：会话锁——`ConversationStore.acquire`（`ConversationStore.java:58-89`）

```java
public Lease acquire(String user, String conversation) {
    return acquireKey("conversation_lock:" + scope(user, conversation));
}
private Lease acquireKey(String key) {
    String token = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(15))) {
        if (System.nanoTime() >= deadline) throw new ResponseStatusException(503, "会话繁忙，请使用相同请求 ID 重试");
        Thread.sleep(50);
    }
    return new Lease(key, token);
}
```

- `scope(user, conversation)` 是 **SHA-256 哈希**（`Hashing.java:14`——长度前缀 + 哈希，防键名冲突也防 Redis 里裸存用户 ID）。所以你在 Redis 里看到的键都是 64 位十六进制。
- **拿锁 = `setIfAbsent`（SET NX）+ 15 秒 TTL**。拿不到就每 50ms 重试，**5 秒内拿不到直接 503**（"会话繁忙，请使用相同请求 ID 重试"——注意它教用户重试的正确姿势：**相同请求 ID**，这样重试会命中回执而不是重做）。
- 每个请求的锁值是**随机 UUID token**。释放时用 Lua 做 **compare-and-del**（`ConversationStore.java:33`）：只有 token 还是我的才删。**如果进程在处理中途挂了，锁靠 15s TTL 自动过期**，绝不产生死锁。
- 处理时间超过 15 秒怎么办？`Lease` 里有个单线程调度器**每 4 秒续租一次**（`ConversationStore.java:80-83`）。续租脚本同样校验 token（`RENEW`，第 32 行）——锁过期后我不会把别人的锁续上。
- `lease.assertValid()`（`ChatService.java:71`）：执行前再查一次「锁还在不在我手上」。丢了 → 503「会话锁已失效」。

**为什么锁和回执是两件事**：锁回答「**此刻**能不能并发处理」，回执回答「**过去**有没有处理过」。锁的生命周期是一次请求（15 秒续租），回执的生命周期是 24 小时。防的是完全不同的两种灾难：并发写坏状态 vs 网络重试重复扣费。

### 环节 2：幂等回执——`ChatService.java:49-53`

```java
var receipt = conversations.receipt(user, conv, clientId);
if (receipt != null) {
    if (!receipt.message().equals(input.message())) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一 client_request_id 不能用于不同消息");
    if (!receipt.response().status().equals("failed") || !receipt.response().retryable()) return receipt.response();
}
```

回执键：`receipt:{会话scope}:{client_request_id哈希}`，TTL **86400 秒（24 小时）**（`ConversationStore.java:53`）。三种分支：

| 情况 | 行为 |
| --- | --- |
| 同 ID + 同消息 + 已成功 | **直接返回存储的回执**——不调模型、不写历史 |
| 同 ID + 不同消息 | **HTTP 409**（客户端 bug，拒绝静默处理） |
| 同 ID + 上次 `failed` 且 `retryable=true` | **放行重做**（上次什么都没写，重做没有副作用） |

> 为什么「防重」按 `client_request_id` 而不是按会话？因为**重试是同一个逻辑请求的重发**，而会话里会有很多不同的请求。网络超时后客户端拿同一个 ID 重发，服务端若已经处理完，直接回放结果——这就是第一节实验 1 里「第二次发同一条消息拿到一模一样的回复」的机制。**回执把「至少一次」的投递语义变成了「恰好一次」的效果**（写操作的极端情况见环节 7 的 `tool_outcome_unknown`）。

### 环节 3：记忆组装——`MemoryManager.getContext`（`MemoryManager.java:60-66`）

```java
public MemoryContext getContext(String userId, String conversationId, String query) {
    List<ConversationMessage> recent = getWorkingMemory(userId, conversationId);          // 工作记忆：最近 19 条原文
    List<String> relevantHistory = semantic.recall(userId, conversationId, query);        // 长期记忆：语义召回 ≤5 条
    Map<String, Object> profile = semantic.profile(userId);                               // 用户画像
    String summary = safeRedisGet(summaryKey(userId, conversationId));                    // 会话摘要
    return new MemoryContext(recent, relevantHistory, profile, summary);
}
```

五类背景，一次请求全组装好：

| 层 | Redis 键 | 内容 | 本课要记住的 |
| --- | --- | --- | --- |
| 工作记忆 | `wm:{scope}`（list） | 最近的原生消息 | 读最近 `workingMax-1=19` 条（`MemoryManager.java:107`） |
| 摘要 | `summary:{scope}` | 压缩后的会话摘要 | 超出窗口的历史浓缩在此 |
| 长期记忆 | 语义存储 | 同用户历史片段召回 | 先本会话、后同用户、不跨用户（细节第 8 节） |
| 画像 | 语义存储 | 偏好 + 实体 | 后台维护时用 LLM 提炼 |
| 业务状态 | `state:{scope}` | 订单号等实体 + domain | 由 `ConversationState.merge` 管理 |

组装好后，`ChatService.java:62-65` 用 **PromptBudget 平分 4000 token 的背景预算**：

```java
int half = budget.backgroundLimit() / 2;                    // 4000 / 2 = 2000 token
String fullContext = "[业务状态…]\n" + budget.truncate(state.toString(), half)
        + "\n[记忆背景]\n" + budget.truncate(context.backgroundText(mapper), half);
```

`fullContext` 就是后面注入给 Agent 的「[业务状态] + [记忆背景]」整段文本——**你在实验 3 的 Redis 考古里会看到它被原样存进了消息元数据**。

**实体继承怎么实现的**：`ConversationState.merge`（`ConversationState.java:8-24`）。关键逻辑：提取到新实体 → 覆盖；没提取到 → 保留旧值；**换订单/换问题 → 清空重来**；「你好」「谢谢」这类**中性意图不切换 domain**，所以寒暄之后问退款，实体照样继承。`state:` 键里还存了 `sources`——**每个实体来自哪次 request_id**，可追溯。

### 环节 4：意图融合——`IntentRecognizer.recognize`（`IntentRecognizer.java:92-122`）

三路并行识别，然后**加权投票**（`IntentRecognizer.java:226-261`）：

```java
addScore(scores, llm, 0.70);       // ① LLM 分类：理解最强，也最慢最贵
addScore(scores, semantic, 0.20);  // ② Embedding 相似度：本地 n-gram 向量，快且免费
addScore(scores, pattern, 0.10);   // ③ 关键词规则：确定性兜底
```

- **LLM 路失败时**（超时/解析失败），直接退回 ②③ 融合，不中断请求（`IntentRecognizer.java:232-240`）。
- 一条「细化规则」：LLM 给出宽泛大类（如 `technical`）而关键词明确命中细分类（如「验证码」→ `technical_login`）且融合分 < 0.8 时，**用细分类纠正**（第 250-256 行）。
- 融合分 < 0.5 → 判为 `other` 低置信。
- **结果缓存**：按（消息 + 历史）哈希缓存，本地 Embedding 缓存 30 秒、在线 300 秒（第 120 行）——多轮对话里历史每轮都在变，所以缓存主要救「同请求重试」和「同消息不同会话」。
- 响应里的 `intent_source_scores` 就是三路**加权前的原始分数**（llm/embedding/pattern），`intent_embedding_source=local-ngram` 说明本机在用本地向量（没配在线 Embedding Key）。

意图结果还携带**紧急度**（`urgency()`，第 324 行：「紧急/立刻」→ CRITICAL）和**正则提取的实体**（`extractEntities()`，第 273 行：订单号、金额、日期、错误码）。紧急度和实体都会影响下一环节的路由。

### 环节 5：路由——`AgentOrchestrator.run`（`AgentOrchestrator.java:73-140`）

决策顺序（`routeDecision()`，第 267-310 行）：

1. **CRITICAL 紧急度 → 直接 ESCALATION**（第 268-270 行）
2. 意图是 `escalation`/`human_handoff` → ESCALATION（第 271-279 行）
3. 否则**按意图打分**（`domainScores()`，第 312-372 行）：意图给基础分（技术类 +0.75、计费类 +0.75、泛查询 +0.55），**关键词命中加微调分**（"500"/"报错"→technical，每条 +0.18 封顶 +0.45），**实体加成**（error_code→technical +0.2，amount→billing +0.15，order_id→general +0.1）
4. 选出最高分当主 Agent；其他候选分数 **≥0.45 且 ≥ 主分 × 0.55** 的成为辅助 Agent（第 298-303 行）→ 辅助非空就是**并行模式**（虚拟线程并发执行，`runParallel()` 第 204 行，composer 汇总）
5. 单 Agent 时判断是否进入**协商模式**（`NegotiationTrigger`，第 113 行）：辅助非空 / 跨域关键词命中 ≥2 类 / 主路由是 general / 实体种类 ≥2——任一成立就给 Agent 挂上 AgentBus 工具
6. 低置信 `other` 意图 → **澄清反问**，不瞎猜（`needsClarification()`，第 429-438 行）
7. 专属 Agent 失败 → **降级 GeneralAgent 兜底**（第 465-471 行），轨迹里会记一条 `agent_fallback`

响应里的 `routing_reason` 就是这么拼出来的（第 374-395 行）：`intent=refund, group=billing, primary=billing, supporting=none, scores=[billing=0.93, general=0.10, technical=0.00]`——**一句话把环节 4 和环节 5 的决策全部暴露给你**。

### 环节 6：工具循环——`AgentAiService.reply`（`AgentAiService.java:55-85`）

LangChain4j `AiServices` 的「模型选工具 → 执行 → 结果回填 → 再生成」循环。四个工程细节：

```java
var memory = MessageWindowChatMemory.withMaxMessages(100);   // 每次调用新建，用完即弃
...
if (request.history() != null) for (var entry : request.history()) {
    switch (entry.getOrDefault("role", "")) {
        case "user" -> memory.add(UserMessage.from(content));
        case "assistant" -> memory.add(AiMessage.from(content));
        default -> { /* Persisted history cannot introduce system instructions. */ }
    }
}
...
.maxSequentialToolsInvocations(ctx.enabled() ? 6 : 4)         // 快路径 4 轮，协商模式 6 轮
.hallucinatedToolNameStrategy(call -> ToolExecutionResultMessage.from(call, provider.execute(call)))
```

1. **每轮独立的消息窗口**（100 条上限）：内存只在这次请求内暂存，**持久化只由环节 8 的 ConversationStore 做**——两个写入口、一个真相源。
2. **历史注入只接受 user/assistant 角色**：持久化的历史里万一混进了别的角色（比如 system），**直接丢弃**——防提示注入。
3. **工具循环上限 4 轮 × 8 个调用**（协商模式 6 轮）：防「模型永远在查资料不答题」烧钱烧时间。轨迹收集上限 32 条（`RegistryToolProvider.java:58`）。
4. **幻觉工具名不炸循环**：模型编了一个不存在的工具名，系统把它当作一次「工具不存在」的结果回填，让模型自己纠偏，而不是异常中断。

工具执行中，成功的证据会被收集进 `request.evidence()`——这就是环节 7 校验的输入之一，也是 Redis 里 `metadata.evidence` 的来源。

### 环节 7：校验——`AnswerVerifier`（`AnswerVerifier.java:23-51`）

用**另一个 LLM**（temperature 0）在 **5 秒内**回答三个问题：`pass`（解决了用户问题吗）、`grounded`（基于上下文吗，还是编的）、`need_escalation`（要转人工吗）。

`ChatService.java:94-103` 的三个出口：

| 校验结果 | 出口 | HTTP | 写入历史？ |
| --- | --- | --- | --- |
| `pass=true && grounded=true` | 正常交付 | 200 | ✅ 写 |
| 校验明确拒绝（available 但不过） | 罐头话术「暂时无法可靠确认…联系人工」+ `partial` + `answer_verification_rejected` | 200 | ✅ 只写**罐头话术** |
| 校验器本身不可用（LLM 超时/异常） | 「稍后重试」+ `failed` + `retryable=true` + `verification_unavailable` | **503** | ❌ **什么都不写** |

**为什么「校验不可用」要单独成态**：校验没过，答案是嫌疑犯；校验器挂了，答案是**嫌疑未定**。前者交付罐头话术（不可重试），后者必须让客户端**用相同 ID 重试**（可重试）——重试时校验器可能恢复了。把两种失败混为一谈，客户端就不知道该怎么办。

校验不过还有一次补救：**批判-修订循环封顶 1 轮**（`ChatService.java:78-91`）——校验器的理由变成一条 `CRITIQUE` 消息注入原 Agent 重答一次，再复判。**封顶 1 轮**是因为修订是 LLM 调用，不封顶就是新的无限循环（协议细节第 5 节讲）。

> **写操作的特殊保护**（`ChatService.java:104-108`）：如果请求里尝试过**写工具**（MCP 写操作），且结果失败或校验未过——提交一个 `tool_outcome_unknown` 回执（`retryable=false`）再返回。**「操作前回执」在写工具执行前就已落盘**（`ChatService.java:67-70` 的 `ToolExecutionGuard`），防的是最坏的灾难场景：退款请求已发给银行，进程在「回执落盘前」崩溃，客户端重试 → **重复退款**。有操作前回执，重试只会命中「结果未确认，请人工核验」。（第 8 节深入）

### 环节 8：Lua 原子提交——`ConversationStore.COMMIT`（`ConversationStore.java:34-55`）

为什么提交要用一段 Lua 脚本？因为一次提交要**同时改 7 个键**：锁、工作记忆、回执、状态、历史、历史计数、遗留标记。Lua 在 Redis 里**单线程原子执行**，中间不会被别的命令插队——这就是「原子提交」。

```lua
if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end          -- ① 锁 token 校验：锁丢了就不许写
if ARGV[7] == '1' then                                             -- ② 只有非失败回复才进历史
  ...
  redis.call('lpush',KEYS[2],ARGV[2],ARGV[3])                      -- ③ wm: 压入 user + assistant
  redis.call('set',KEYS[4],ARGV[6],'EX',ARGV[5])                   -- ④ 回执（86400s）
  redis.call('lpush',KEYS[5],ARGV[2],ARGV[3])                      -- ⑤ history: 同步双写
  redis.call('incrby',KEYS[6],2)                                   -- ⑥ 历史计数 +2
end
redis.call('set',KEYS[3],ARGV[4],'EX',86400)                       -- ⑦ state 永远写（含失败）
return 1
```

三个设计点：

1. **第一步先校验锁 token**：如果锁已过期被别人拿走（比如处理超时），**宁可不写**，让客户端重试——脏写比丢一轮更可怕。
2. **`wm:` 和 `history:` 双写**：`wm:` 是工作记忆（压缩的原料），`history:` 是全量历史（供会话导出/评测用，上限 200 条）。**失败的回复只写回执和状态，不进任何历史**——这样下次重试时上下文是干净的，模型不会把「失败罐头话术」当成既定事实。
3. **回执 TTL 24 小时、状态 TTL 24 小时**，和记忆 TTL 对齐：一天之后会话过期，同 ID 重试才会真的重做——**回执的寿命必须和会话记忆的寿命一致**，否则「命中旧回执 + 丢失旧上下文」会拼出一个驴唇不对马嘴的回复。

### 环节 9：后台维护——`MemoryMaintenance`（`MemoryMaintenance.java:16-35`）

成功提交后，`ChatService.java:118` 把（用户, 会话）丢进后台队列。**2 个工作线程、每用户一条队列、队列 64 上限**，做两件事（`MemoryManager.maintain()`）：

1. **压缩**（`MemoryManager.java:133-191`）：工作记忆 ≥ `compressAt=15` 条时，把最老的 N-5 条交给 LLM 合并成摘要（**保留最近 5 条**），然后用 Lua 做**「快照比较」裁剪**——先比对裁剪前的那段消息和摘要版本**完全没变**才执行 `ltrim`（第 172-182 行）。防的是：压缩期间前台又提交了新消息，若直接裁剪会把新消息误删（第 8 节展开）。
2. **更新画像**（`MemoryManager.java:83-105`）：取最近 10 条，LLM 提炼偏好和实体，与旧画像**合并**（不是覆盖）。失败就保留旧值——**失败保守策略**。

---

## 2. 实验准备：PowerShell 环境初始化

### 2.1 第一节的坑是怎么来的（先明白原理）

你在 PowerShell 里跑第一节命令遇到的坑，根源基本是这五个：

| 坑 | 原因 | 本节的解法 |
| --- | --- | --- |
| `curl` 报参数错 | PowerShell 5.1 里 `curl` 是 `Invoke-WebRequest` 的**别名**，不是 curl.exe | 一律写 **`curl.exe`** |
| `set -a && . ./.env` 报错 | 这是 **bash 语法**，PowerShell 没有 `export`/`. file` | 用 `[Environment]::SetEnvironmentVariable`（实验 6 用到） |
| `SERVER_PORT=8082 java -jar` 报错 | **bash 的行内环境变量**语法 | `$env:SERVER_PORT = '8082'` |
| 聊天返回 400「文件必须是有效 UTF-8 文本…」 | PowerShell 5.1 把**命令行参数按系统 ANSI 代码页（中文 Windows 是 GBK）编码**传给原生程序，中文 JSON 全变乱码（实测 `[Console]::InputEncoding` 也救不了参数传递） | **JSON 写进无 BOM 的 UTF-8 文件**，`curl.exe --data-binary @文件` 原样发送 |
| 响应里的中文是乱码 | PowerShell 5.1 用 GBK 解码 curl.exe 输出的 UTF-8 | `[Console]::OutputEncoding = UTF8` |

### 2.2 本节的实验工具（整段贴进 PowerShell，全课复用）

打开一个 **PowerShell** 窗口，粘贴以下整段：

```powershell
# ── ① 三个编码设置：让 curl.exe 的中文进得去、出得来 ──
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$Backend = 'http://localhost:8082'
$rc = 'D:\software\redis\redis-cli.exe'    # 你的 Redis CLI 路径（6380 专用）

# ── ② Chat：发消息。JSON 先写 UTF-8 临时文件再交给 curl（躲开 PS5 参数编码坑）──
function Chat($msg, $conv, $clientId, $uid = 'u2') {
  $body = @{ message = $msg; user_id = $uid; conv_id = $conv; client_request_id = $clientId } | ConvertTo-Json
  $tmp = "$env:TEMP\echomind-body.json"
  [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))  # $false = 不带 BOM
  curl.exe -s -X POST "$Backend/chat" -H 'Content-Type: application/json' --data-binary "@$tmp"
}

# ── ③ Show：格式化显示 JSON（-InputObject 避免数组被逐条枚举）──
function Show($text) { ConvertTo-Json -InputObject ($text | ConvertFrom-Json) -Depth 20 }

# ── ④ Hash-Key：复刻 Hashing.key（长度前缀 + SHA-256），用来从用户/会话ID算出 Redis 键里的哈希 ──
function Hash-Key($parts) {
  $joined = (($parts | ForEach-Object { "$($_.Length):$_" }) -join '')
  $sha = [System.Security.Cryptography.SHA256]::Create()
  ([System.BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($joined)))).Replace('-','').ToLower()
}

Write-Output '环境就绪'
```

> 说明：
> - `Hash-Key` 就是 `Hashing.java:14` 的 PowerShell 复刻。**哈希是单向的**——从 Redis 键推不回用户 ID，这就是脱敏（第 11 节讲）。
> - `2>$null` 会出现在 redis-cli 命令末尾，作用是**吞掉 `-a` 密码参数的安全警告**（它打在 stderr 上）。
> - 习惯 Git Bash 也可以：本节命令大多通用，`Hash-Key` 等价物是 `printf '%s' "3:u2" "5:ps-c1" | sha256sum`。
> - 本节实验全部使用**新用户 `u2`** 和新会话 ID（`ps-*`），不会污染你第一节用 `u1/c1` 造的数据。

---

## 3. 六个实验

### 实验 1：幂等回执的三种分支（环节 ②）

```powershell
# 分支一：正常处理
$r1 = Chat '你好，我想了解一下退款政策' 'ps-idem' 'turn-1'
Show $r1

# 分支二：同 ID 同消息 → 命中回执，字节级相同（服务端没有第二次调模型）
$r2 = Chat '你好，我想了解一下退款政策' 'ps-idem' 'turn-1'
$r1 -eq $r2          # 期望 True

# 分支三：同 ID 换消息 → HTTP 409
$body = @{ message = '换一个问题，怎么开发票？'; user_id = 'u2'; conv_id = 'ps-idem'; client_request_id = 'turn-1' } | ConvertTo-Json
$tmp = "$env:TEMP\echomind-body.json"
[System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
curl.exe -s -w "`nHTTP_STATUS=%{http_code}" -X POST "$Backend/chat" -H 'Content-Type: application/json' --data-binary "@$tmp"
# 期望：{"detail":"同一 client_request_id 不能用于不同消息"} + HTTP_STATUS=409
```

再到 Redis 里看看回执键本身：

```powershell
$scope = Hash-Key @('u2','ps-idem')
& $rc -p 6380 -a echomind123 keys ('receipt:' + $scope + ':*') 2>$null          # 回执键
& $rc -p 6380 -a echomind123 ttl ('receipt:' + $scope + ':' + (Hash-Key @('turn-1'))) 2>$null   # ~86400
```

### 实验 2：会话锁的生死（环节 ①）

开**两个 PowerShell 窗口**（都已贴好 §2.2 的工具块）。

窗口 1，发一条需要多轮 LLM 调用的慢请求（跨技术+计费，预计 10～30 秒）：

```powershell
Chat '我的应用一直报 500，会不会重复扣了我的订阅费，请详细说明排查步骤和账单核对方法' 'ps-lock' 'lock-1'
```

窗口 2，趁请求还在进行，连续按 `↑` `回车` 反复执行：

```powershell
& $rc -p 6380 -a echomind123 keys 'conversation_lock:*' 2>$null        # 请求进行中：能看到一个 conversation_lock:xxx
$scope = Hash-Key @('u2','ps-lock')
& $rc -p 6380 -a echomind123 ttl ('conversation_lock:' + $scope) 2>$null   # 14~15，每 4 秒被续回
& $rc -p 6380 -a echomind123 get ('conversation_lock:' + $scope) 2>$null    # UUID token
```

窗口 1 返回后，窗口 2 再查 `keys 'conversation_lock:*'` → **键消失了**（compare-and-del 释放）。

你看到的是：**锁在请求开始前诞生，在提交后死亡，15 秒是它的最长寿命**。如果处理时间超过 15 秒，续租线程每 4 秒把它续回 15 秒——处理多久，锁活多久。

> 单窗口紧凑版（同样效果）：
> ```powershell
> $job = Start-Job -ScriptBlock {
>   $body = @{ message = '我的应用一直报 500，会不会重复扣了我的订阅费，请详细说明排查步骤和账单核对方法'; user_id = 'u2'; conv_id = 'ps-lock2'; client_request_id = 'lock-2' } | ConvertTo-Json
>   $tmp = "$env:TEMP\echomind-body2.json"
>   [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
>   curl.exe -s -X POST 'http://localhost:8082/chat' -H 'Content-Type: application/json' --data-binary "@$tmp"
> }
> Start-Sleep -Milliseconds 1500
> & $rc -p 6380 -a echomind123 keys 'conversation_lock:*' 2>$null
> Wait-Job $job | Out-Null; Receive-Job $job | Out-Null; Remove-Job $job
> ```

### 实验 3：Redis 考古——一次请求留下的所有痕迹（环节 ③⑥⑧）

继续用 `ps-idem`（实验 1 的会话）：

```powershell
$scope = Hash-Key @('u2','ps-idem')

& $rc -p 6380 -a echomind123 llen ('wm:' + $scope) 2>$null            # 2：一问一答
& $rc -p 6380 -a echomind123 lrange ('wm:' + $scope) 0 0 2>$null      # 最新一条 = assistant 消息（含 metadata）
& $rc -p 6380 -a echomind123 get ('state:' + $scope) 2>$null          # {"domain":"commerce","entities":{...},"sources":{...}}
& $rc -p 6380 -a echomind123 llen ('history:' + $scope) 2>$null       # 2：全量历史（与 wm 双写）
& $rc -p 6380 -a echomind123 ttl ('wm:' + $scope) 2>$null             # ~86400
```

看 `lrange` 输出的 assistant 消息，`metadata` 里有五个字段值得逐一对上代码：

| metadata 字段 | 来自代码 | 含义 |
| --- | --- | --- |
| `request_id` / `intent` / `agent_type` | 环节 ④⑤ 的结果 | 这次回答的「出生证明」 |
| `status` / `tools_used` | 环节 ⑥⑦ 的结果 | 工具与校验的最终结论 |
| `evidence` | `ChatService.java:117`（预算截断 3000 token） | **环节 ⑥ 收集的工具证据原文** |
| `context` | `ChatService.java:117`（预算截断 2000 token） | **环节 ③ 组装的业务状态+记忆背景原文**——模型真正「看到」的世界 |

> 这两个字段（evidence/context）就是系统的**免费探针**：`LEARNING_GUIDE` 阶段 1 建议「在 ChatService 加日志」，其实每条已提交的消息里已经带着全链路的关键中间产物。想验证某个环节的输出，不用改代码重启，直接读 Redis。

### 实验 4：实体继承与切换（环节 ③'）

```powershell
Show (Chat '我的订单 A-12345 出问题了，金额 88 元' 'ps-ent' 'e-1')
Show (Chat '那这个订单的状态怎么样' 'ps-ent' 'e-2')        # 没提订单号 → order_id 仍在（继承）
Show (Chat '换个订单 B-99999，重新查一下' 'ps-ent' 'e-3')    # 换单 → 实体重置，只剩 B-99999
```

观察点：

1. `e-1` 的 `entities` 里有 `order_id=[A-12345]`、`amount=[88 元]`。**注意 `error_code` 里也出现了 `A-12345`**——正则 `\b[A-Z][A-Z0-9_-]{2,16}\b`（`IntentRecognizer.java:282`）把订单号误判成了错误码。**真实系统的实体提取永远有瑕疵**，所以下游（路由打分、画像）都只把它当弱信号。
2. `e-2` 不提订单号，`order_id` 依然继承——`ConversationState.merge` 的 keep 逻辑。
3. `e-3` 的「换个订单」命中 `newTask` 正则（`ConversationState.java:13`）→ 实体清空重建，连 `amount` 也消失了。
4. 回头看 `state:` 键：`sources` 里记录着**每个实体是哪次 request_id 提取的**。

### 实验 5：意图三路分数的实况（环节 ④⑤）

```powershell
Show (Chat '为什么重复扣款？这个月多扣了钱' 'ps-intent-a' 'i-1')

# 30 秒内、换一个全新会话、发完全相同的消息（同消息+同空历史 → 意图缓存命中）
Show (Chat '为什么重复扣款？这个月多扣了钱' 'ps-intent-b' 'i-2')
```

对比两条响应的这些字段：

- `intent` / `intent_group`：细分类 `payment_issue` 归一化到 `billing` 组（`INTENT_GROUPS`，`IntentRecognizer.java:68`）。
- `intent_source_scores`：三路**加权前**的原始分数。`pattern` 是关键词命中的确定性得分；`embedding` 是本地 n-gram 与意图示例的相似度；`llm` 是模型自报的置信度。
- `intent_confidence`：加权融合后的最终分（0.70×llm + 0.20×embedding + 0.10×pattern）。
- `routing_reason`：`scores=[billing=…, general=…, technical=…]`——环节 5 的打分表直接摊在桌面上。
- `intent_embedding_source=local-ngram`：没配在线 Embedding，本地向量顶上（思想 #2 的又一例）。

两条响应的 `intent_source_scores` **完全一致**，就是 30 秒意图缓存的证据（`IntentRecognizer.java:120`）——第二次的意图环节零模型调用。

### 实验 6（可选，约 2 分钟）：8 轮对话触发记忆压缩（环节 ⑨）

压缩阈值 `compressAt=15` 条消息（`EchoMindProperties.java:73`）。用一个**新会话**连续聊 8 轮（每轮 2 条，共 16 条 ≥ 15），触发后台压缩：

```powershell
$qs = @('你好','你们支持哪些业务？','退款政策是什么？','发票怎么开？','能查物流吗？','投诉渠道有哪些？','会员积分怎么用？','谢谢，再见')
$i = 1
foreach ($q in $qs) {
  Write-Output ("--- 第 $i 轮 ---")
  $r = Chat $q 'ps-comp1' ("r$i")
  Write-Output (($r | ConvertFrom-Json).status)
  $i++
}

# 压缩在后台线程执行（要调一次 LLM 生成摘要），等 20 秒再查
Start-Sleep -Seconds 20
$scope = Hash-Key @('u2','ps-comp1')
& $rc -p 6380 -a echomind123 llen ('wm:' + $scope) 2>$null            # 期望 5：老消息被压缩，只剩最近 5 条
& $rc -p 6380 -a echomind123 llen ('history:' + $scope) 2>$null       # 期望 16：全量历史一条不少
& $rc -p 6380 -a echomind123 exists ('summary:' + $scope) 2>$null     # 期望 1：摘要诞生了
& $rc -p 6380 -a echomind123 get ('summary:' + $scope) 2>$null        # LLM 合并出的 2-3 句摘要
```

对比 `wm:` 和 `history:`：**压缩只裁剪工作记忆，全量历史不动**。再发第 9 轮消息，看响应是否正常——摘要会作为「[会话摘要]」出现在模型的背景里，前面的对话事实没有丢。

> 每轮要真实调 3 次模型（意图 + Agent + 校验），8 轮约 1.5～2.5 分钟，耐心等。想手动聊也行：后端支持交互式 CLI——用 `java -jar target\echomind-java-0.1.0.jar --cli` 启动（`ChatCli.java`，`/new` 开新会话，`/quit` 退出）。

---

## 4. 时间预算串讲：那一组数字是什么关系

把本课出现过的所有数字摆在一起：

| 数字 | 位置 | 含义 |
| --- | --- | --- |
| **50 秒** | `ChatService.java:37` | 整轮请求的总预算（`Tasks.within` 在虚拟线程上设截止，`Tasks.java:10`） |
| 30 秒 | `LLM_TIMEOUT_SECONDS`（`application.yml:42`） | **单次** LLM 调用的超时（意图/Agent/摘要都受它管） |
| 5 秒 | `AnswerVerifier.java:32` | 校验器单次调用的更紧超时 |
| 20 秒 | `EchoMindProperties.java:234` | 协商模式下 AgentBus 的通信总预算 |
| 8 秒 | `EchoMindProperties.java:235` | 单次 ASK 求助的超时 |
| ≤6 条 / ≤2 层 | `EchoMindProperties.java:232-233` | AgentBus 消息数与深度的收敛闸 |
| 5 秒 / 15 秒 / 4 秒 | `ConversationStore.java:64,66,83` | 拿锁截止 / 锁 TTL / 续租周期 |
| 86400 秒 | `ConversationStore.java:53` | 回执与记忆的 TTL（24 小时） |
| 4 轮×8 调用 | `AgentAiService.java:76` | 工具循环上限（协商模式 6 轮） |

关系图：

```
50 秒总预算（最外层，超时 → 504「请用相同 client_request_id 重试」）
 ├─ 拿锁 ≤5 秒 ── 锁 TTL 15 秒，每 4 秒续租（处理多久锁活多久）
 ├─ 意图：1 次 LLM ≤30 秒（失败 → 本地融合兜底）
 ├─ Agent：工具循环 4 轮 × 8 调用，每轮含若干次 LLM ≤30 秒
 │    └─ 协商模式：AgentBus 总 20 秒，单次 ASK 8 秒（超时 → 降级继续作答）
 ├─ 校验：≤5 秒（不可用 → 503 可重试；批判修订 +1 次 Agent 调用）
 └─ Lua 提交 + 回执（86400 秒内，同 ID 重试不会再走上面任何一步）
```

三个规律：

1. **每一层都给内层留了余量**：8s ASK ⊂ 20s Bus ⊂ 50s 总。任何一个内层失控，外层都能兜住，不会出现「一个 Agent 挂起拖死整个服务」。
2. **超时后的行动指南都是「用相同 ID 重试」**：504、503、锁失败——所有可重试的失败都指向同一个动作。因为回执保证重试要么命中旧结果、要么干净地重做。
3. **86400 是所有「记忆」的统一寿命**：回执、工作记忆、状态、历史、摘要全部对齐 24 小时。会话过期后一切归零，回执也不会再命中——系统在「防止重复」和「防止僵尸数据」之间取了同一个平衡点。

---

## 5. 常见问题排查（PowerShell 版）

| 症状 | 原因 | 解法 |
| --- | --- | --- |
| 响应中文乱码 | `[Console]::OutputEncoding` 没设 UTF-8 | 重贴 §2.2 的三行编码设置 |
| 聊天 400「文件必须是有效 UTF-8 文本…」 | 中文 JSON 直接作为参数传给 curl.exe，被 PS5 按 GBK 编码 | 用 `Chat` 函数（写 UTF-8 文件 + `--data-binary @file`） |
| `curl` 报 Invoke-WebRequest 的参数错误 | 打成了 `curl`（PS 别名） | 用 `curl.exe` |
| redis-cli 输出为空 | 键不存在（哈希算错？会话 ID 拼错？） | 先 `keys 'prefix:*'` 看全貌；核对 `Hash-Key @('u2','ps-idem')` 的拼写 |
| 聊天返回 503 `status=failed`、`error_code=verification_unavailable` | 校验器 LLM 调用失败/超时 | 用**相同 client_request_id** 重试（回执保证不重做已成功的部分） |
| 聊天返回 `tool_outcome_unknown` | 该请求尝试过写操作但结果未确认 | 联系人工核验，**不要**换 ID 重试（防重复执行写操作） |
| `keys` 命令在别处被警告「生产禁用」 | `KEYS` 会阻塞 Redis | 学习环境无妨；生产用 `SCAN`（本机 Redis 5.0 的 redis-cli `--scan` 实测无输出，可用 `keys` 代替） |

---

## 6. 本课小结：把九个环节串成一句话

> 一次请求 = **锁**（此刻能并发吗）→ **回执**（过去做过吗）→ **组装记忆**（模型该知道什么）→ **融合意图**（用户在问什么）→ **路由**（谁来答）→ **工具循环**（要什么证据）→ **校验**（答案可信吗）→ **Lua 原子提交**（怎么可靠记下来）→ **后台维护**（记忆怎么不膨胀）。

对照第一节的三条设计思想：环节 ④⑥⑦ 里模型是组件（理解+生成），环节 ①②③⑤⑧ 全是工程骨架（可靠）；④ 失败退本地、⑤ 失败降 General、⑥ 超时降级、⑦ 不可用转可重试——全是兜底；⑧ 的 Lua 是「可靠性」三个字的代码化。

**核心收获**：LLM 应用的主链路里，模型调用只占三个环节，剩下六个环节都在回答「不可靠时怎么办」。**「LLM 只是组件，工程是骨架」不是口号，是这套调用链的结构本身。**

---

## 7. 课后思考题（Checkpoint）

先自己想，再看提示：

1. 「会话锁」和「幂等回执」是两件事。各自防什么？为什么不能用一个机制同时解决？
   <details><summary>提示</summary>锁防的是「同一时刻两个请求并发改状态」，回执防的是「同一个逻辑请求被网络重试执行两次」。锁的生命周期是一次处理（秒级），回执是 24 小时——时间尺度不同，防的灾难也不同。</details>

2. 工具循环为什么封顶 4 轮 × 8 个调用？不设上限会怎样？
   <details><summary>提示</summary>循环的每一步都是真实 LLM 调用——成本、延迟都无上限增长；而且模型可能「永远在查资料不答题」。上限把它变成可预算、可超时的确定性行为。</details>

3. 校验不通过时，未核验的答案**不写入会话历史**——把罐头话术写进历史会有什么问题？
   <details><summary>提示</summary>下一轮请求会把这些话术当「既定事实」喂给模型；重试时上下文也不干净。历史只记录「发生了什么」，不记录「为什么失败」。</details>

4. 50 秒总预算、20 秒通信预算、8 秒 ASK 超时、5 秒校验超时——这些数字之间是什么关系？为什么 ASK 超时后发起方不是失败而是继续作答？
   <details><summary>提示</summary>嵌套关系见 §4 的关系图。ASK 只是「问同事要个参考结论」，拿不到也不影响自己答——主答案少一段，比整轮失败好。这是「降级继续」哲学在通信层的体现。</details>

5. 回执 TTL 是 24 小时。24 小时后客户端用同一个 ID 重试，会发生什么？对**写操作**这为什么尤其危险？系统怎么防？
   <details><summary>提示</summary>回执过期 → 重做。写操作重做 = 重复扣费/重复退款。防法是「操作前回执」`tool_outcome_unknown`：写工具执行前先落盘回执，崩溃重试只会命中「结果未确认，请人工核验」。</details>

6. 锁的 TTL 是 15 秒、续租每 4 秒。如果处理时间超过 15 秒且续租失败，会发生什么？为什么释放锁要用「比对 token 再删」而不是直接删？
   <details><summary>提示</summary>锁过期被别的请求拿走 → 原请求 `assertValid` 失败 → 503。直接删会删掉「别人刚拿到的锁」——compare-and-del 保证只删自己的。</details>

7. 为什么意图结果对本地 Embedding 只缓存 30 秒，对在线 Embedding 缓存 300 秒？
   <details><summary>提示</summary>缓存的是「这次识别结论」，跟哪个 Embedding 无关；差别在于在线 Embedding 的**成本**——调用要花钱/花网络。30 秒够覆盖「同请求重试」场景，300 秒进一步省在线成本。</details>

8. 「你好」之后问退款，实体为什么能继承？「换个订单」之后为什么全清空？找到 `ConversationState.java` 里对应的两个分支。
   <details><summary>提示</summary>中性意图不切换 domain → `keep=true` 保留旧实体；「换个/新的/另一个」命中 `newTask` 正则 → 重建。这就是用「规则兜底 LLM 判断」的例子。</details>

---

## 8. 下节预告

**第三节：意图识别——三路融合的取舍**（已发布：[lesson-03-intent-fusion.md](./lesson-03-intent-fusion.md)）。把环节 ④ 拆开讲透：为什么需要三路融合、LLM 分类的成本/延迟/不确定输出、在线 Embedding 失败如何回退本地（为什么「整次匹配」失败才回退而不是逐条回退）、`IntentResult` 携带的信息下游谁在用、实体继承的完整状态机。

**课前准备（20 分钟）**：

1. 打开 `src/main/java/com/echomind/intent/IntentEmbedding.java`，回答：本地模式和在线模式分别怎么算相似度？
2. 在 `IntentRecognizer.java` 里找到「细化规则」（第 250-256 行），自己构造一条会被它纠正的消息，预测三路各自的输出
3. 带着思考题 7 的答案来上课

---

*第二节完。前一节：[lesson-01-first-run.md](./lesson-01-first-run.md) · 路线图：`learn/LEARNING_GUIDE.md`。*
