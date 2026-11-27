# 第三节：意图识别——三路融合的取舍

> 对应《LEARNING_GUIDE.md》的「阶段 2」。第二节你跟着一次请求走完了九环节；这一节把环节 ④ **拆开讲透**：LLM / Embedding / Pattern 三路各自怎么打分、为什么权重是 0.70 / 0.20 / 0.10、哪条消息会被"细化规则"纠正、实体怎么提取和继承。
>
> 预计用时：1 天。**所有实验命令均为 PowerShell 语法，且已在 PowerShell 5.1 + 本项目实测通过**（实验数据就是实测输出）。沿用第一节 §4.0 的工具箱。

---

## 本课目标

学完本课你应该能做到：

1. 手算一条消息的融合置信度：`0.70×llm + 0.20×embedding + 0.10×pattern`
2. 说清三路各自的**成本、失败模式、兜底方式**
3. 解释「细化规则」在什么条件下把 LLM 的大类纠正成细分类
4. 解释在线 Embedding 为什么「整次匹配」失败才回退本地，而不是逐条回退
5. 说出 `IntentResult` 携带的 8 个信息分别被谁消费
6. 构造消息触发 CRITICAL 紧急度 → 升级路由
7. 回答文末 8 道思考题

---

## 0. 课前核对（第二节的课前准备）

1. `IntentEmbedding.java` 的本地模式怎么算相似度？——**1~3 字符 n-gram 哈希进 256 维向量，余弦相似度**（`IntentEmbedding.java:113-122`）；在线模式是 **bge-m3 向量 + 点积**（第 105-112 行，向量已归一化，点积即余弦）。
2. 「细化规则」在哪？——`IntentRecognizer.java:250-256`。第二节实验 5 里 `payment_issue` 那条消息就是它（或 LLM 直接细分类）的产物。
3. 意图缓存为什么 30s/300s？——第 120 行：**按 Embedding 来源分档**。本地向量免费，30 秒够覆盖「同请求重试」；在线 Embedding 要花钱花网络，缓存 300 秒进一步省成本。两个档位防的是**不同的浪费**。

---

## 1. 为什么意图识别值得单独一课

看调用链里意图的下游消费者：

| 消费者 | 用了 IntentResult 的什么 |
| --- | --- |
| `AgentOrchestrator.routeDecision` | `intent`（选主 Agent）、`confidence`（澄清判断）、`urgency`（CRITICAL 直接升级） |
| `AgentOrchestrator.domainScores` | `intent` + `entities`（打分基础分和加成） |
| `ConversationState.merge` | `entities` + `intentGroup`（实体继承、domain 跟踪） |
| `AgentAiService.rolePacket` | 全部（喂给 Agent 的输入包） |
| `ChatResponse` | 全部（暴露给客户端） |

**意图判错的代价**：错误的 Agent（技术问题派给计费）、错误的工具权限面（计费工具去查技术问题）、错误的升级决策。而意图识别本身**必须快、必须便宜、必须失败也能出结果**——因为它是每条消息都要过的第一道模型关卡。三路融合就是为了同时满足这三个"必须"。

---

## 2. 三路识别逐个拆

### 2.1 第一路：LLM 分类（`llmRecognize`，`IntentRecognizer.java:124-162`）

```
温度 0.1、最多 256 token、18 个意图各带 1 条示例（TEMPLATES，第 27-46 行）
返回 {"intent":"technical","confidence":0.9,"reasoning":"一句话说明"}
```

三个细节：

1. **提示词里明确鼓励细分类**：「如果用户问题能匹配细粒度业务意图，请优先返回细粒度意图，而不是宽泛大类。例如退款优先返回 refund，发票优先返回 invoice，登录故障优先返回 technical_login。」（第 131-133 行）——示例只给每个意图**一条**，靠这条指令引导模型走细粒度。
2. **输出解析是防御式的**（第 144-152 行）：先 `sliceJsonObject` 截取首个 `{...}`，再校验 `intent` 是合法枚举名、`confidence` 是 0~1 的有限数。模型多吐一个字、少吐一个括号都不至于炸。
3. **LLM 路失败 → 返回 `OTHER/0.0/failed=true`**（第 154-161 行），**而不是抛异常**。失败只是"这一路没分"，融合照样进行——这是三路架构存在的前提。

### 2.2 第二路：Embedding 相似度（`IntentEmbedding.java`）

把用户消息和 18 类意图的模板算相似度。**两种实现**：

**本地模式（当前你在用的，`intent_embedding_source=local-ngram`）**：`localVector()`（第 113-122 行）把文本的 **1~3 字符 n-gram** 哈希进一个 **256 维向量**（hash 为偶数 +1、奇数 −1），归一化后与模板向量算余弦。实测效果：

| 消息 | embedding 分 | 原因 |
| --- | --- | --- |
| `应用一直崩溃` | **0.9999…**（≈1.0） | 它就是 TECHNICAL_CRASH 的模板原文（第 44 行） |
| `我的快递什么时候到` | 0.818 | 接近 LOGISTICS 模板「快递什么时候到？」 |
| `订单 A-12345 出问题了` | 0.226 | 和任何模板都不像 |

> n-gram 向量没有语义理解——「崩了」和「崩溃」只有字符重叠才能相似。但它**零成本、零延迟、永远可用**，作为快速通道和兜底刚好。

**在线模式（`SILICONFLOW_API_KEY` 配置后）**：`match()`（第 39-48 行）先走 `online()`（第 49-74 行）：

- 8 秒预算（第 42 行）；失败 → **冷却 60 秒**（第 43 行），期间全部走本地
- 模板向量**按内容指纹缓存**（第 50-64 行）：模板集没变就不重新请求；批量 32 条一次请求
- 维度一致性校验：模板向量和查询向量维度不同 → **直接拒绝这次在线结果**（第 60、69-71 行），回退本地——不同 Embedding 模型产生的向量**不可比**（这也是第 7 节知识库向量集合要带模型版本的原因）

**为什么「整次匹配」失败才回退，而不是逐条回退**：看 `match()` 的结构——在线匹配是一个整体动作（拿模板向量 + 拿查询向量 + 对比）。如果模板在线、查询回退本地，**两个向量空间混在一起算相似度，分数毫无意义**。所以要么整次在线，要么整次本地。**「部分降级」在这里不是更灵活，而是产生不可信的结果**——这是可靠性设计里很重要的一条判断：降级必须是**整块的、语义自洽的**。

### 2.3 第三路：Pattern 关键词（`patternRecognize`，`IntentRecognizer.java:179-224`）

先查**细分类关键词表**（第 181-191 行），命中直接返回；没命中再查**大类表**（第 197-206 行）。打分公式（第 210-224 行）：

```
score = min(1.0, 0.5 + 0.25 × (命中数 − 1))     # 命中 1 个关键词 = 0.5，每多 1 个 +0.25
```

**实测注意**（实验 7 会看到）：pattern 分经常是 **0.0**——「订单 A-12345 出问题了」里没有任何一个关键词命中（ORDER_STATUS 的表里是「订单状态」「发货了吗」「处理到哪」，而消息里只有「订单」两个字）。**规则路只有命中/未命中，没有模糊地带**——这是它的缺点（覆盖窄），也正是它的优点（命中即确定）。

---

## 3. 融合投票：加权与纠偏（打分机制的核心）

`vote()`（`IntentRecognizer.java:226-261`）：

```java
addScore(scores, llm, 0.70);       // 理解力最强，但会失败、会漂移
addScore(scores, semantic, 0.20);  // 快、便宜、永远可用，但无语义
addScore(scores, pattern, 0.10);   // 确定性强，但覆盖面窄
```

每条路的分 = 该路的 confidence × 权重，**同类意图累加**，取最高者。然后两道后处理：

**① 细化规则**（第 250-256 行）：

```java
if (GENERIC_INTENTS.contains(best.getKey())      // 融合最高分是宽泛大类
        && SPECIFIC_INTENTS.contains(patternIntent)  // 关键词命中了细分类
        && patternConfidence >= 0.5
        && best.getValue() < 0.8) {                  // 且大类分不算稳
    sourceScores.put("refined_by_pattern", patternConfidence);
    return new VoteResult(patternIntent, Math.max(best.getValue(), patternConfidence), sourceScores);
}
```

一句话：**LLM 说了大类、关键词命中细分类、融合分又不够稳 → 信细分类**。实测消息「页面一直报 500 错误」触发了它：`intent_source_scores` 里出现 `"refined_by_pattern":0.5`，最终 intent=`technical_crash`、confidence=0.63。

**② 置信度门槛**（第 257-259 行）：融合分 < 0.5 → `OTHER`。`OTHER` + 消息长度 > 2 + 置信度 < 0.5 → 第二节环节 5 里的**澄清反问**（不瞎猜）。

**为什么 LLM 权重最高但只有 0.70？** 如果 LLM 占 1.0，它一失败（超时/格式错）整个意图识别就跟着失败；如果三路均分，LLM 的理解力优势被稀释。0.70 让 LLM **主导但不独裁**：LLM 在线时它说了算；LLM 失败时（`failed=true`）直接跳过加权，退回 ②③ 融合（第 232-240 行）——**权重表本身也是兜底方案的一部分**。

---

## 4. IntentResult：一次识别的全部产物

`IntentResult.java` 是个 8 字段 record。每个字段谁在用：

| 字段 | 内容 | 消费者 |
| --- | --- | --- |
| `intent` | 18 类之一 | 路由选人、打分 |
| `confidence` | 融合置信度 | 澄清判断、响应暴露 |
| `urgency` | LOW/MEDIUM/HIGH/CRITICAL | CRITICAL → 升级路由 |
| `intentGroup` | 细分类归一到大类（`INTENT_GROUPS`，第 68-78 行：refund→billing 等） | `ConversationState` 定 domain；响应 `intent_group` |
| `entities` | 正则提取的实体 | 状态合并、路由加成、rolePacket |
| `reasoning` | LLM 的一句话理由 | 响应暴露（调试） |
| `latencyMs` / `sourceScores` | 三路耗时与原始分 | 响应暴露（本课实验全靠它） |

**紧急度**（`urgency()`，第 324-340 行）：先**剥离否定表达**（第 326-327 行把「不紧急」「没那么紧急」删掉再判断——否则「不紧急」会被误判成紧急）；「紧急/立刻」→ CRITICAL，「今天/马上/尽快」或意图是升级类 → HIGH，COMPLAINT → MEDIUM。CRITICAL 在 `routeDecision` 里**跳过一切打分，直接升级**（实测见实验 5）。

**实体提取**（`extractEntities()`，第 273-285 行）：5 条正则——order_id、date、error_code（`[45]\d{2}` 加 `[A-Z][A-Z0-9_-]{2,16}`）、amount。**已知瑕疵**（实测）：`error_code` 第二条正则会把订单号 `A-12345` 误判成错误码。真实系统的实体提取永远有瑕疵，所以下游只把它当**弱信号**（路由加成最多 +0.2）——这也是「LLM 只是组件，工程是骨架」的反面：**规则组件也会错，系统用权重而非信任来使用它**。

---

## 5. 缓存：30 秒和 300 秒的差别

`recognize()` 第 92-95、117-121 行：按 `(消息, 历史)` 的哈希缓存结果。

- 本地 Embedding：**30 秒**。够覆盖「网络重试重发同请求」——重试间隔通常几秒。
- 在线 Embedding：**300 秒**。多出来的部分省的是**在线 API 的钱**。
- 缓存键带历史：多轮对话里历史每轮都变，所以缓存主要救「同请求重试」和「同消息开新会话」，救不了「多轮追问」——这是**正确性优先**的选择（历史不同，意图就可能不同）。

> 一个隐蔽点：缓存的是**整个 IntentResult**（包括实体、紧急度）。同消息 30 秒内重试，连实体提取都省了。

---

## 6. 实验（全部命令已实测，输出即下文数据）

先贴第一节 §4.0 的工具箱（三行编码设置 + `Show` + `Post-Json` + `Chat`）。本节实验用新用户 `u3`、新会话 `i3-*`。

### 实验 1：融合公式手算——用响应里的分数验证代码里的权重

```powershell
$o = (Chat '为什么重复扣款？这个月多扣了钱' 'i3-1' 'f1') | ConvertFrom-Json
Show (Chat '为什么重复扣款？这个月多扣了钱' 'i3-1' 'f1')   # 想看完整响应就加这行
$s = $o.intent_source_scores
'0.70*llm + 0.20*embedding + 0.10*pattern = ' + (0.70*$s.llm + 0.20*$s.embedding + 0.10*$s.pattern)
'intent_confidence = ' + $o.intent_confidence
```

实测输出：`llm=0.95, embedding=0.7559, pattern=0.75` → 手算 **0.89118…**，`intent_confidence` = **0.89118…**，完全相等。**你现在知道了 `intent_confidence` 这个字段的完整来历**。

### 实验 2：三路分数的性格——同一类问题，三路各给多少分

```powershell
$msgs = @('验证码收不到了怎么办', '登录一直报 401 错误', '账户好像被盗了', '发票抬头怎么改')
foreach ($m in $msgs) {
  $o = (Chat $m 'i3-2' ("q" + [array]::IndexOf($msgs, $m))) | ConvertFrom-Json
  '{0} → intent={1} conf={2} scores={3}' -f $m, $o.intent, $o.intent_confidence, (ConvertTo-Json -InputObject $o.intent_source_scores -Compress)
}
```

实测四组（注意三路的不同表现）：

| 消息 | intent | llm | embedding | pattern |
| --- | --- | --- | --- | --- |
| 验证码收不到了怎么办 | technical_login | 0.9 | 0.696 | 0.5 |
| 登录一直报 401 错误 | technical_login | 0.95 | 0.720 | 0.5 |
| 账户好像被盗了 | account_security | 0.97 | 0.645 | 0.5 |
| 发票抬头怎么改 | invoice | 0.95 | 0.934 | 0.75 |

观察：① pattern 都是 0.5——关键词各命中 1 个；② embedding 对「发票抬头怎么改」给 0.934（与 INVOICE 模板字符重叠多），对「账户好像被盗了」只给 0.645；③ llm 稳在高位且直接给了细分类。**三路确实在"从不同角度"看同一条消息。**

### 实验 3：细化规则实况——让 LLM 说大类、关键词说细类

```powershell
$o = (Chat '页面一直报 500 错误' 'i3-3' 'r1') | ConvertFrom-Json
$o.intent; $o.intent_confidence
ConvertTo-Json -InputObject $o.intent_source_scores -Compress
```

实测：`intent=technical_crash, confidence=0.63`，`intent_source_scores` 里多了一个键 **`"refined_by_pattern":0.5`**。LLM 原本给出大类 `technical`（融合分 < 0.8），关键词「报 500」命中细分类 `technical_crash`（≥0.5）→ 细化规则生效。**`refined_by_pattern` 这个键就是细化规则生效的签名。**

### 实验 4：Embedding 的"字符相似"本质

```powershell
foreach ($m in @('应用一直崩溃', '我的快递什么时候到', '订单 A-12345 出问题了')) {
  $o = (Chat $m 'i3-4' ("e" + [array]::IndexOf(@('应用一直崩溃','我的快递什么时候到','订单 A-12345 出问题了'), $m))) | ConvertFrom-Json
  '{0} → embedding={1}' -f $m, $o.intent_source_scores.embedding
}
```

实测：`应用一直崩溃` → **0.9999…**（模板原文，几乎完全重合）；`我的快递什么时候到` → 0.818；`订单 A-12345 出问题了` → 0.226。**本地 n-gram 向量只认字符重叠，不认语义**——这就是为什么它只配 0.20 的权重。

### 实验 5：CRITICAL 紧急度 → 跳过打分直接升级

```powershell
$o = (Chat '我的账户被盗了，非常紧急，请立刻帮我处理' 'i3-5' 'u1') | ConvertFrom-Json
$o.routing_reason     # 期望：紧急度为 CRITICAL，触发升级路由
$o.agent_type         # 期望：escalation
```

实测：`routing_reason="紧急度为 CRITICAL，触发升级路由"`、`agent_type=escalation`。注意意图本身是 `account_security`（本来会路由到 billing），**紧急度在路由里压过了意图**。

### 实验 6：30 秒意图缓存

```powershell
$o1 = (Chat '我的应用一直崩溃' 'i3-6a' 'c1') | ConvertFrom-Json
$o2 = (Chat '我的应用一直崩溃' 'i3-6b' 'c2') | ConvertFrom-Json   # 30 秒内、全新会话、同消息
$o1.intent_source_scores.llm -eq $o2.intent_source_scores.llm       # 期望 True
```

实测：两个全新会话（历史都为空 → 缓存键相同）30 秒内发同一条消息，`intent_source_scores` 完全一致——**第二次的意图识别零模型调用**。

### 实验 7：实体提取与它的瑕疵

```powershell
$o = (Chat '订单 A-12345 出问题了，金额 88 元' 'i3-7' 'x1') | ConvertFrom-Json
ConvertTo-Json -InputObject $o.entities -Compress
$o.intent_source_scores.pattern   # 期望 0.0：没有任何关键词命中
```

实测：`entities = {order_id:[A-12345], error_code:[A-12345], amount:[88 元]}`——**订单号被 error_code 正则误伤**；同时 pattern 分 = **0.0**（消息里没有「订单状态」「发货了吗」等关键词）。一条消息同时演示了规则路的两个极端：**正则实体在误伤、关键词在漏报**——所以它们在融合里只配 0.10 的权重、在路由里只配 0.2 的加成。

---

## 7. 常见问题排查

| 症状 | 原因 | 解法 |
| --- | --- | --- |
| `intent_embedding_source` 是 `local-ngram` 而不是 `siliconflow/...` | 没配 `SILICONFLOW_API_KEY`，或在线失败进入 60 秒冷却 | `/integrations/embedding` 看 `online_configured` 和 `cooldown` |
| `intent` 判成 `other`、回复变成澄清反问 | 融合分 < 0.5（LLM 犹豫 + 无关键词 + 字符不相似） | 把问题问具体；或看 `intent_source_scores` 是哪一路拖了后腿 |
| `intent_source_scores` 里出现 `refined_by_pattern` | 细化规则生效（正常现象） | 用实验 3 理解触发条件 |
| 同消息重发但分数不同 | 超过缓存窗口（本地 30s / 在线 300s），或历史变了导致缓存键不同 | 实验 6 的对照方式重试 |

---

## 8. 课后思考题（Checkpoint）

先自己想，再看提示：

1. 为什么需要三路融合？只用 LLM 分类有什么问题（成本、延迟、不确定输出）？只用规则有什么问题？
   <details><summary>提示</summary>LLM：每条消息都花钱、几百毫秒、还会超时和漂移；规则：免费、快、确定，但覆盖窄、没有模糊地带。融合 = 拿 LLM 的理解力 + 规则/向量的确定性兜底。</details>

2. 在线 Embedding 失败时为什么「整次匹配」失败才回退本地，而不是逐条回退？
   <details><summary>提示</summary>两个向量空间混在一起算相似度，分数不可比、不可信。降级必须是整块的、语义自洽的（§2.2）。</details>

3. 细化规则为什么要加 `best.getValue() < 0.8` 这个条件？如果 LLM 大类分已经 0.9，还该不该被关键词纠成细分类？
   <details><summary>提示</summary>0.9 说明三路共识很强，此时关键词纠偏可能才是错的（关键词会误伤——想想实验 7）。只有大类分"不够稳"时才信细分类。</details>

4. `IntentResult` 里除了意图类别还携带了什么？这些信息下游谁在用？
   <details><summary>参考答案</summary>7 个字段：`confidence`（Orchestrator 判断是否需要澄清）；`urgency`（CRITICAL → 跳过打分直接升级路由）；`intentGroup`（`ConversationState.merge` 用它定 domain，如 refund→commerce）；`entities`（状态合并、路由加成、Agent 的 rolePacket 输入包）；`reasoning`/`latencyMs`/`sourceScores`（组装进 ChatResponse 暴露给客户端，本课实验全靠它们）。</details>

5. 实体继承：多轮对话中上一轮的实体如何传给本轮？
   <details><summary>参考答案</summary>`ConversationState.merge`（`ConversationState.java:8-24`）：本轮正则提取到实体 → 覆盖旧值；没提取到 → `keep=true` 保留旧值；「换个/新的/另一个」命中 `newTask` 正则 → 清空重建；greeting/feedback 等中性意图不切换 domain，所以寒暄后实体照常继承。`state:` 键里的 `sources` 还记录每个实体来自哪次 request_id。现象见第二节实验 4：不提订单号时 order_id 仍在，说「换个订单」后只剩新订单号。</details>

6. 为什么意图缓存对本地 Embedding 只缓存 30 秒、对在线 300 秒？缓存键为什么必须包含历史？
   <details><summary>参考答案</summary>30 秒够覆盖「网络重试重发同请求」（重试间隔通常几秒），本地向量免费所以窗口短；在线 Embedding 花钱花网络，300 秒是为了省在线 API 的调用成本。缓存键必须含历史：同一句话在不同历史下意图可以完全不同（上一轮聊退款，这轮「那需要什么材料呢」是退款语境）——不含历史会把上一轮的结论错误复用到本轮。代价是多轮追问基本不命中缓存，这是正确性优先的取舍。</details>

7. 为什么 pattern 的分数公式是 `0.5 + 0.25×(命中数−1)` 而不是命中数越多越线性上涨？
   <details><summary>参考答案</summary>两个原因：① 关键词之间高度相关（「退款」「退货」常一起出现），命中 10 个关键词不代表 10 倍确定，边际信息量递减；② pattern 分要和 llm/embedding 分（都是 0~1）在同一尺度上加权，`min(1.0, ...)` 封顶保证 pattern 路在 0.10 权重下不会因命中泛滥而膨胀。命中 1 个 = 0.5 的"确定起点"来自实测共识：单个关键词命中已是有力信号。</details>

8. 「不紧急，随便问问」这条消息的紧急度会是什么？找到代码里防误判的那两行。
   <details><summary>参考答案</summary>LOW。`urgency()` 的判断顺序里（`IntentRecognizer.java:326-327`）先剥离否定表达——「不(是|用|需要|太)?紧急」等中文和「not urgent/emergency」等英文会被删掉——否则「不紧急」里的「紧急」会被误判成 CRITICAL。剥离后剩余文本没有其他紧急词、不是升级意图、不是投诉，落回 LOW。</details>

---

## 9. 下节预告

**第四节：Agent 框架层——LangChain4j 的正确打开方式**（已发布：[lesson-04-agent-framework.md](./lesson-04-agent-framework.md)）。把环节 ⑤⑥ 拆开：四个 Agent 类的极简设计、profiles.json 角色契约、AiServices 的动态代理魔法、每轮独立的消息窗口、**AgentStats 的路由打分公式**（`/health` 里那个 `routing_score` 的完整来历——你在第一节实验 5 里见过它）。

**课前准备（20 分钟）**：

1. 打开 `src/main/java/com/echomind/agent/AgentStats.java`，只读 `routingScore()` 一行公式，猜猜 0.7 和 0.3 分别代表什么
2. 打开 `src/main/resources/agents/profiles.json`，对比四个角色的 `temperature` 差异，想想为什么 BILLING 和 ESCALATION 是 0.0
3. 打开 `src/main/java/com/echomind/agent/AgentAiService.java` 的 `reply()`，找出「工具循环上限」和「幻觉工具名处理」两行

---

*第三节完。上一节：[lesson-02-request-lifecycle.md](./lesson-02-request-lifecycle.md) · 路线图：`learn/LEARNING_GUIDE.md`。*
