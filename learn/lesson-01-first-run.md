# 第一节：认识 EchoMind 并把它跑起来

> 对应《LEARNING_GUIDE.md》的「心智地图 + 阶段 0」。本课不要求读懂代码，目标是：**建立架构体感，亲手把系统跑起来，并看懂系统暴露出来的每一个信号**。
>
> 预计用时：半天。**本课所有命令均为 Windows PowerShell 语法，并已在 PowerShell 5.1 + 本项目实测通过**——不用 Git Bash，不用改语法，直接复制粘贴。

---

## 本课目标

学完本课你应该能做到：

1. 用一句话向别人解释 EchoMind 是什么、请求经过哪些环节
2. 在你的机器上独立启动/重启后端，并验证健康状态
3. 发一条聊天消息，**逐字段解读响应 JSON**
4. 触发一次 Agent 协作模式，在 `/trace/bus` 看到 Agent 之间的通信图
5. 导入知识库文档并观察到"工具调用"发生
6. 提交一次固定回归评测，看懂四维评分报告
7. 回答文末 7 道思考题

---

## 1. 心智地图：EchoMind 到底是什么

### 1.1 一句话架构

> EchoMind = **意图识别（路由）+ 多 Agent（生成）+ 工具（知识库/MCP）+ 校验（把关）+ 可靠提交（存储）**

它不是一个"调一次 LLM 就返回"的 Demo，而是一套完整的 **LLM 应用工程**。你接下来要学的，不是"怎么调 LangChain4j API"，而是**如何把一次大模型调用包装成可靠的生产服务**——路由、协作、工具安全、记忆、评测、监控、可靠性治理，每一层都是真实客服系统的刚需。

### 1.2 一次请求走过的路（全景图）

```
HTTP POST /chat
  → AccessFilter              鉴权校验（开发模式可跳过）
  → ChatService               会话锁 + 幂等回执（防重复处理）
  → MemoryManager             组装工作记忆/摘要/长期记忆/画像/业务字段
  → IntentRecognizer          三路意图融合：LLM + Embedding + Pattern
  → AgentOrchestrator         路由：General / Technical / Billing；复合问题并行
      → BaseAgent → AgentAiService   LangChain4j AiServices 工具循环
          → 本地知识库工具：查询改写 → BM25+向量并行召回 → LLM 重排
          → 外部 MCP 工具：白名单 → Schema 校验 → 超时 → 缓存 → 熔断
          → AgentBus：Agent 之间可以互相 ASK / DELEGATE / CRITIQUE
      → AnswerVerifier        校验：通过→交付；不合格→转人工；不可用→可重试
  → ConversationStore         Lua 原子提交消息 + 业务状态 + 回执
  → MemoryMaintenance         后台队列异步压缩记忆、更新画像
```

**现在不需要理解每一步的细节**（那是后面每一节课的任务）。本课只需要把这张图"挂"在脑子里，做实验时随时对照："我现在这个动作，触发了图里的哪一段？"

### 1.3 十一个子系统速览

| 子系统 | 包路径 | 一句话职责 | 对应课程 |
| --- | --- | --- | --- |
| 入口与 API | `com.echomind.api` | REST 接口、鉴权、错误规范 | 第 2 节 |
| 核心编排 | `com.echomind.chat` | 会话锁、幂等、主流程 | 第 2 节 |
| 意图识别 | `com.echomind.intent` | 三路意图融合 | 第 3 节 |
| Agent 层 | `com.echomind.agent` | 路由、基类、AiServices、校验 | 第 4 节 |
| Agent 通信协议 | `com.echomind.agent.communication` | **AgentBus**：消息与收敛控制 | 第 5 节 |
| 工具系统 | `com.echomind.tool` | 注册表、Schema 校验、熔断、写保护 | 第 6 节 |
| 知识库 | `com.echomind.knowledge` | 切片、BM25、语义索引、融合召回 | 第 7 节 |
| 记忆 | `com.echomind.memory` | 分层记忆、后台维护、原子提交 | 第 8 节 |
| LLM 网关 | `com.echomind.llm` | 模型适配、输入预算、截断拒绝 | 第 9 节 |
| 评测 | `com.echomind.evaluation` | LLM Judge 四维评分、回归基线 | 第 10 节 |
| 监控/轨迹/存储 | `monitor` / `trace` / `storage` / `security` / `skill` / `integration` | 可观测性、MCP、Skills | 第 11 节 |

### 1.4 三条贯穿全项目的设计思想（先埋种子）

后面每节课都会反复印证这三条，今天先记住它们的存在：

1. **LLM 只是组件，工程是骨架。** 路由、校验、存储、锁——全都在模型调用之外。模型只负责"理解 + 生成"，系统负责"可靠"。
2. **一切不可靠都有兜底。** 工具超时 → 降级继续作答；回答校验不过 → 转人工；Agent 连续失败 → 熔断冷却；摘要生成失败 → 保留原历史。读代码时始终问："如果这里失败了会怎样？"
3. **多 Agent 的价值在协作协议，而不只是路由。** 普通多 Agent 系统是"领导分派任务"；EchoMind 的 AgentBus 让 Agent **在生成过程中主动互相求助、转交、批判**——这是本项目最独特的部分（第 5 节专门讲）。

---

## 2. 环境准备与验证

### 2.1 你的机器已就绪清单

根据之前的配置，你的开发环境如下（本课按此编写）：

| 组件 | 位置/参数 | 备注 |
| --- | --- | --- |
| JDK 21 | `D:\workcode\jdk-21`（`java` 已在 PATH） | 项目要求 21+ |
| Maven 3.9.16 | `mvn` 已在 PATH，本地仓库 `D:\workcode\m2repository` | 首次构建已下载过依赖 |
| Redis | `D:\software\redis`，端口 **6380**，密码 `echomind123` | 系统 Redis（6379 无密码）不要用 |
| 后端端口 | **8082** | 8080 被本机 nginx 占用 |
| API Key | 项目根目录 `.env`（已 gitignore） | 存放 `DEEPSEEK_API_KEY`，**永远不要打印或提交** |
| 前端（可选） | Vite 5173，`EchoMindFrontend` 项目 | 本课程用 curl.exe 学后端，前端只是备用界面 |

### 2.2 PowerShell 的三个坑（本节已全部绕开）

第一节命令之所以必须用「本项目专用写法」，是因为 Windows PowerShell 5.1 有三个和 Linux 惯例不同的坑。**你只需要用，不需要记原理**（第 2 节会展开讲）：

| 坑 | 表现 | 本课的写法 |
| --- | --- | --- |
| `curl` 是别名 | `curl` 其实是 `Invoke-WebRequest` 的别名，参数全不兼容 | 一律写 **`curl.exe`** |
| 中文参数变乱码 | PowerShell 5.1 把命令行参数按 GBK 编码传给原生程序，中文 JSON 参数到后端就坏了（实测返回 400「文件必须是有效 UTF-8 文本…」） | JSON 先写进 **UTF-8 临时文件**，`curl.exe --data-binary @文件` |
| 中文响应变乱码 | PowerShell 5.1 用 GBK 解码 curl.exe 输出的 UTF-8 | 开头三行**编码设置** |

### 2.3 三步验证（启动前先确认）

打开一个 **PowerShell** 窗口，依次执行：

```powershell
# ① JDK 版本（期望 21.x；若提示找不到，改用 & 'D:\workcode\jdk-21\bin\java.exe' -version）
java -version

# ② Redis 6380 是否活着（返回 PONG 即正常；2>$null 是吞掉 -a 密码参数的安全警告）
& 'D:\software\redis\redis-cli.exe' -p 6380 -a echomind123 ping 2>$null

# ③ 后端是否已在运行（在运行会返回健康 JSON，可直接跳到实验部分）
curl.exe -s http://localhost:8082/health
```

如果 ② 不通，启动 Redis：

```powershell
& 'D:\software\redis\redis-server.exe' --port 6380 --requirepass echomind123 --dir D:\workcode\echomind-redis --appendonly yes
```

### 2.4 环境变量速查（本课只需认识，后续课程逐一深入）

PowerShell 下看 `.env` 里配了哪些变量——**只看名字和值长度，绝不打印密钥**：

```powershell
Get-Content .env | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { '{0} = (长度 {1})' -f $matches[1], $matches[2].Length }
}
```

| 变量 | 含义 | 本课要知道的 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE=deepseek` | 用哪家模型：`deepseek` / `anthropic` | 你的 Key 是 DeepSeek，所以是 `deepseek`。两家都走 Anthropic 消息协议 |
| `DEEPSEEK_API_KEY` | DeepSeek Key | 缺失时**不影响启动**，但聊天会返回 503 |
| `DEEPSEEK_MODEL` / `DEEPSEEK_BASE_URL` | 模型名与兼容接口地址 | DeepSeek 官方 Anthropic 兼容接口 |
| `REDIS_PASSWORD` | Redis 密码 | 与 §2.3 的启动参数一致 |
| `LLM_FALLBACK_ENABLED=true` | 模型失败时的兜底开关 | 兜底策略第 9 节讲 |
| `SERVER_PORT=8082`、`REDIS_PORT=6380` | 应用端口与 Redis 端口 | **不在 `.env` 里**，启动时显式覆盖（§3 方式 A），因为它们是机器相关的 |

> 注意：`.env` 里的 `#` 开头行是注释（包括被注释掉的 `#SPRING_PROFILES_ACTIVE=anthropic`），加载器会自动跳过。

---

## 3. 启动后端

### 方式 A：命令行（推荐本课使用）

PowerShell：

```powershell
cd "D:\idea_project\EchoMind所有代码+详细文档+简历\EchoMindJava"

# ① 加载 .env 到当前进程（只影响本次启动，不永久改系统；# 开头行自动跳过）
Get-Content .env | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith('#') -and $line -match '^([^#=]+)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}

# ② 机器相关的两个覆盖（本机必须 8082 + 6380）
$env:SERVER_PORT = '8082'
$env:REDIS_PORT = '6380'

# ③ 启动
java -jar target\echomind-java-0.1.0.jar
```

> - **先确认 8082 没有实例在跑**（§2.3 第 ③ 步有响应就是还在跑，先在那个窗口按 Ctrl+C 停掉），否则会报端口占用。
> - 如果 `target\echomind-java-0.1.0.jar` 不存在（改了代码/首次构建），先执行 `mvn package -DskipTests`（或用项目自带的 `.\mvnw.cmd package -DskipTests`）。
> - **为什么先 `.env` 再覆盖两个变量？** `.env` 提供 Key 和 profile，`SERVER_PORT`、`REDIS_PORT` 是机器相关的，放启动命令里覆盖最灵活。**改过 `.env` 后必须重启**——环境变量只在启动时读取一次。

### 方式 B：IDEA 共享运行配置

选择 `EchoMind Backend (8082)` 运行配置启动。

> ⚠️ **已知陷阱**：直接点 `EchoMindJavaApplication` 旁边的绿色箭头，会创建一个不带环境变量的新配置，默认抢 8080 端口（被 nginx 占用），启动报 `Port 8080 was already in use`。**永远选共享配置 `EchoMind Backend (8082)`**。

### 启动成功验证

```powershell
curl.exe -s http://localhost:8082/health
# 浏览器打开 Swagger：http://localhost:8082/docs
```

**本课第一个知识点**：没有模型 Key 时服务**依然能启动**，知识库管理、健康检查都可用；只有需要模型的聊天才会失败。这就是思想 #2「不可靠有兜底」的最小例子——依赖缺失要**降级为明确的错误**，而不是让整个系统起不来。

（可选）前端界面：在 `EchoMindFrontend` 目录下：

```powershell
$env:VITE_JAVA_API_URL = 'http://localhost:8082'; $env:VITE_DEFAULT_BACKEND = 'java'
npm run dev
```

浏览器开 `http://localhost:5173`。本课程后续都用 curl.exe，因为我们要学的就是前端背后发生的一切。

---

## 4. 五个实验

### 4.0 实验工具箱（先贴这一整段，后面全靠它）

```powershell
# ── ① 三个编码设置：让 curl.exe 的中文进得去、出得来 ──
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$Backend = 'http://localhost:8082'

# ── ② Show：把 curl 的 JSON 输出格式化 ──
function Show($text) { ConvertTo-Json -InputObject ($text | ConvertFrom-Json) -Depth 20 }

# ── ③ Post-Json：POST JSON 请求。中文必须走「UTF-8 文件 + --data-binary」，
#      因为 PS5 会把命令行参数按 GBK 编码（直接 -d '中文' 会被后端 400 拒绝）──
function Post-Json($path, $body) {
  $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json }
  $tmp = "$env:TEMP\echomind-body.json"
  [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))  # $false = 不带 BOM
  curl.exe -s -X POST "$Backend$path" -H 'Content-Type: application/json' --data-binary "@$tmp"
}

# ── ④ Chat：发一条聊天消息（user_id 默认 u1，与本节所有实验一致）──
function Chat($msg, $conv, $clientId, $uid = 'u1') {
  Post-Json '/chat' @{ message = $msg; user_id = $uid; conv_id = $conv; client_request_id = $clientId }
}

Write-Output '环境就绪'
```

> 约定：本课所有实验都用用户 `u1`、会话 `c1`、端口 8082。`Show` 的 `-InputObject` 参数是为了让 JSON 数组也能整体格式化。

### 实验 1：第一条消息——逐字段读懂响应

```powershell
$r1 = Chat '你好，我想了解一下退款政策' 'c1' 'turn-1'
Show $r1
```

> 如果你昨天已经跑过第一节（同 ID 同消息），会**直接命中 24 小时回执**拿到上次的回复——这本身就是幂等机制的活证据（第 2 节讲）。

拿到响应后，**对照下表逐字段解读**——这些字段就是系统的"内部状态暴露面"，后面每节课都在解释它们怎么来的：

| 字段 | 含义 | 观察点 |
| --- | --- | --- |
| `request_id` | 本次请求的服务端唯一 ID | 实验 2、3 查轨迹全靠它 |
| `agent_types` | 哪些 Agent 参与了回答 | 简单问题应该是 `["general"]` 或 `["billing"]`；复合问题是多个 |
| `tools_used` | 实际执行过的工具名列表 | 没查知识库时为空；实验 3 会看到知识工具 |
| `entities` | 从消息中提取的实体（订单号等） | 多轮对话里会继承（第 8 节） |
| `intent_embedding_source` | 意图向量用了谁 | `local-ngram` 表示本地模型 |
| `status` | `success` / `partial` / `failed` | `partial` = 回答可用但有环节失败 |
| `retryable` | 失败时能否重试 | 与幂等回执配合（第 2 节） |
| `error_code` | 失败原因编码 | 对照 `api/ApiErrors.java` |
| `knowledge_used` | 是否真的用了知识库 | 只在实际成功调用知识工具后为 true |
| `conv_id` | 会话 ID | 同一 conv_id + 新 client_request_id = 多轮对话 |
| `routing_reason` | 路由决策的完整解释 | `scores=[...]` 就是路由打分明细 |
| `intent_source_scores` | 意图三路的原始分数 | `llm` / `embedding` / `pattern` |

**再发一条同 ID 的消息试试**：

```powershell
$r2 = Chat '你好，我想了解一下退款政策' 'c1' 'turn-1'
$r1 -eq $r2          # 期望 True：字节级相同，服务端没有第二次调用模型
```

你会拿到**和第一次一样的回复**，而不是又一次模型调用。这是幂等回执：重复请求直接返回已完成结果。然后换 `turn-2`、换一句"那需要什么材料呢？"：

```powershell
Show (Chat '那需要什么材料呢？' 'c1' 'turn-2')
```

观察 `entities` 是否延续了上一轮的内容——这就是多轮记忆的雏形。

### 实验 2：触发 Agent 协作——看 Agent 之间"对话"

```powershell
# 一条横跨技术和计费两个领域的问题（README 的官方演示句）
Show (Chat '我的应用一直报 500，会不会重复扣了我的订阅费' 'c1' 'turn-3')
```

记下响应里的 `request_id`（假设是 `76f291ec-...`），然后：

```powershell
# 最近 20 条通信图（按请求列出）
Show (curl.exe -s "$Backend/trace/bus?limit=20")

# 你刚才那次请求的通信图
Show (curl.exe -s "$Backend/trace/bus/76f291ec-你的request_id")
```

**怎么看这张图**：节点是 Agent（general/technical/billing），边是消息类型——`ASK`（求助）、`DELEGATE`（转交）、`PROPOSE`/`ACCEPT`（并行协作）、`CRITIQUE`（批判）、`ESCALATE`（升级）。实测这条演示句通常触发**并行模式**：billing → technical 的 `PROPOSE` 边 + composer 的 `ACCEPT` 边；有时也会看到 `ASK` 边。**把这 JSON 画成一张节点-边图**，这是理解 AgentBus 的最好方式（协议细节第 5 节讲）。

> ⚠️ **重要认知（真实系统 vs 演示脚本）**：真实模型下的协作行为**不稳定**——并行/ASK 都可能触发，也可能模型直接作答、图上只有节点没有边。这不是 bug，而是设计使然：**用不用工具、求不求助是模型的自由裁量**。观察方法：
> 1. `/trace/bus?limit=20` 里看你的 request_id 的 messages 列表；
> 2. 没触发就换问法再试，例如明确说"你需要向计费专员确认这个问题"；
> 3. 想**确定性**地看到 ASK 全流程，跑脚本化模型测试（第 5 节细讲，命令已验证可跑通）：
>
> ```powershell
> mvn test "-Dtest=AskFlowTest"     # 3 个测试全绿；测试里的模型是本地桩，行为 100% 可控
> ```
>
> 「真实 LLM 系统不可复现、测试里用桩保证确定性」——这本身就是本课最重要的工程认知之一。

顺带看一眼工具轨迹（实验 1 的 request_id 也能看）：

```powershell
Show (curl.exe -s "$Backend/trace/tool/你的request_id")
```

注意轨迹里的用户身份是**哈希后的**，不存原始 user_id——脱敏设计（第 11 节讲）。

### 实验 3：知识库导入与检索——亲眼看到"工具调用"

```powershell
# 导入一篇业务文档（中文内容走 Post-Json 的 UTF-8 文件通道，不会乱码）
Show (Post-Json '/knowledge/add' @{ documents = @(@{ title = '退款政策'; document_id = 'policy-refund'; content = '退款申请需先审核。7 天无理由退款需商品未使用且包装完整。超过 7 天需提供质量问题证明，人工审核后 3 个工作日内原路退回。' }) })

# 上传文件的方式（.txt/.md/.json，UTF-8，≤10MB）：先创建一个 policy.md
[System.IO.File]::WriteAllText("$PWD\policy.md", '配送政策：标准配送 3-5 个工作日送达，订单满 99 元免运费。', (New-Object System.Text.UTF8Encoding($false)))
Show (curl.exe -s -X POST "$Backend/knowledge/upload" -F 'file=@policy.md')

# 看知识库状态：片段数量
Show (curl.exe -s "$Backend/knowledge/stats")

# 直接检索（中文查询词必须先百分号编码，否则又会踩参数编码坑）
$q = [System.Uri]::EscapeDataString('7天无理由退款')
Show (curl.exe -s -X POST "$Backend/search?query=$q&top_k=3")
```

然后**回到聊天**，问一个必须靠知识库才能答的问题：

```powershell
Show (Chat '我买了 3 天想退款，商品没拆封，能退吗？' 'c1' 'turn-4')
```

对比实验 1：这次 `tools_used` 里出现了知识检索工具，`knowledge_used=true`，回答内容也基于你导入的政策。

**你刚刚亲眼看到了「工具循环」**：模型判断"这个问题需要查资料"→ 调用知识库工具 → 系统检索并把结果回填 → 模型基于结果作答。这个循环最多 4 轮、每轮最多 8 个工具调用（第 4、6 节讲为什么要有上限）。

### 实验 4：跑一次固定回归评测——认识 LLM-as-Judge

```powershell
# 提交固定回归用例（18 项：11 条意图 + 4 条单轮 + 1 组三轮对话）
Show (Post-Json '/eval/run' '{}')
# → HTTP 202 + {"status":"queued","job_id":"..."}

# 轮询进度（多轮几次，评测要真实调用模型，需要几十秒到几分钟）
Show (curl.exe -s "$Backend/eval/jobs/你的job_id")
```

报告里每条用例都有**四维分数**：相关性、准确性、完整性、有用性——打分者不是人，是另一个 LLM（Judge），用 5 组 Few-shot 校准案例教它"什么是好答案"。

**两个值得现在就记住的设计**：

1. 提交返回 **202 + job_id 异步执行**，而不是同步等结果——因为评测耗时不可控，同步会堵死 HTTP 连接。
2. 评测跑在**独立统计、独立缓存、独立熔断**里——不能让"考试"污染"生产"指标（第 10 节展开）。

### 实验 5：通读 Swagger + 集成状态 + 指标

**① Swagger（`http://localhost:8082/docs`）通读一遍接口清单。** 接口清单 = 功能清单，你要能说出每个接口是干什么的：

| 接口 | 用途 |
| --- | --- |
| `/chat` `/search` | 聊天与检索 |
| `/knowledge/*` | 知识库管理 |
| `/skills` `/skills/reload` | 业务技能包与热加载 |
| `/integrations/embedding|semantic|mcp` | 三大集成的运行状态 |
| `/mcp` | 标准 MCP 服务（别人能把 EchoMind 当工具） |
| `/eval/*` | 评测 |
| `/monitor` `/metrics` | 监控与 Prometheus 指标 |
| `/trace/tool/*` `/trace/bus/*` | 脱敏轨迹 |

**② 看集成状态**（哪些依赖真实可用）：

```powershell
Show (curl.exe -s "$Backend/integrations/embedding")   # 意图向量：local 还是硅基流动在线
Show (curl.exe -s "$Backend/integrations/semantic")    # 知识库语义存储：local 还是 chroma
Show (curl.exe -s "$Backend/integrations/mcp")         # 外部 MCP 服务器（默认 Microsoft Learn）
```

**③ 看模型调用指标**——做完前面几个实验，模型已经被调用过多次：

```powershell
curl.exe -s "$Backend/metrics" | Select-String -Pattern 'echomind_llm' | Select-Object -First 5
```

你能看到 `echomind_llm_calls_total`、`echomind_llm_duration_seconds`、`echomind_llm_tokens_total` 等计数器。注意标签里只有 stage（画像/摘要/judge/agent…）、模型名、结果——**没有用户内容、没有请求 ID**（隐私 + 指标基数控制，第 11 节讲）。

---

## 5. 常见问题排查

| 症状 | 原因 | 解法 |
| --- | --- | --- |
| 启动报 `Port 8080 was already in use` | IDEA 绿色箭头创建了无环境变量的新配置 | 用共享配置 `EchoMind Backend (8082)`；命令行启动时显式 `$env:SERVER_PORT = '8082'` |
| 启动报 Redis 连接失败 | 6380 的 Redis 没起，或密码/端口不对 | 按 2.3 节启动 Redis，`& 'D:\software\redis\redis-cli.exe' -p 6380 -a echomind123 ping 2>$null` 验证 |
| 聊天返回 HTTP 503、`status=failed` | 没有有效 Key，或 profile 与 Key 不匹配 | 用 2.4 节的方式确认 `SPRING_PROFILES_ACTIVE` 是 `deepseek` 且 `DEEPSEEK_API_KEY` 长度不为 0；`/health` 和知识库接口应该仍正常 |
| 聊天返回 400「文件必须是有效 UTF-8 文本…」 | 中文 JSON 直接作为参数传给 curl.exe，被 PS5 按 GBK 编码 | 用工具箱里的 `Post-Json` / `Chat`（UTF-8 文件 + `--data-binary`），不要手写 `-d '中文'` |
| 响应里的中文是乱码 | 工具箱的三行编码设置没执行 | 重贴工具箱 ① 的三行 |
| 改了 `.env` 不生效 | 环境变量只在启动时读取 | 重启后端 |
| 聊天很慢 | 模型真实调用 + 工具循环，正常 | 先看 `/metrics` 的耗时分布；超时行为第 9 节讲 |

---

## 6. 本课小结：你看到了什么

把今天的实验映射回子系统表——**每个实验都摸到了系统的哪一块**：

| 实验 | 触达的子系统 | 你建立的认知 |
| --- | --- | --- |
| 实验 1 响应字段 | `api` + `chat` + `memory` | 一次请求携带了多少"内部状态"；幂等回执 |
| 实验 1 多轮追问 | `memory` | 多轮对话里实体/上下文在延续 |
| 实验 2 通信图 | `agent.communication` + `trace` | Agent 之间真的会"对话"，且可以可视化；真实模型的不确定性 |
| 实验 3 知识导入 | `knowledge` + `tool` | 工具循环的完整闭环：模型决策 → 检索 → 回填 → 作答 |
| 实验 4 评测 | `evaluation` | LLM-as-Judge、异步任务、评测隔离 |
| 实验 5 状态/指标 | `integration` + `monitor` + `llm` | 系统依赖的真实状态可查询；指标不含敏感信息 |

**核心收获**：EchoMind 不是黑盒。它的路由、工具、校验、评测、监控——全部通过 API 暴露为可观察的信号。**接下来所有课程，本质都是回答一个问题：这些信号是怎么产生的。**

---

## 7. 课后思考题（Checkpoint）

先自己想，再看提示：

1. 为什么没有模型 Key 服务也能启动？这种设计叫什么？
   <details><summary>提示</summary>依赖缺失 → 降级为明确的运行状态，而不是启动失败。对应思想 #2。</details>

2. `agent_types` 为 `["general"]` 和为多个值的请求有什么本质区别？
   <details><summary>参考答案</summary>单个 = 单 Agent 路径，一个 Agent 从识别到作答包办；多个 = 路由判定为**复合问题**（主 Agent + 辅助 Agent 并行执行，composer 汇总），或协商/降级过程中多个 Agent 先后参与。本质区别在 `AgentOrchestrator.routeDecision` 是否选出了 supportingAgents——涉及虚拟线程并发和 ResponseComposer 汇总（第 4 节详讲）。</details>

3. 重复提交相同 `client_request_id` + 相同消息，为什么拿到的是旧回复？这个机制防止了什么？
   <details><summary>参考答案</summary>第一次处理成功时，服务端把完整响应存进 Redis 回执键（TTL 24 小时）。第二次同 ID 同消息到来时直接返回存储的回执，不调模型、不写历史。防的是网络重试把同一个逻辑请求执行两次——重复回答、重复计费，对写操作甚至是重复扣款（第 2 节环节 2，含三种分支的完整规则）。</details>

4. 评测接口为什么是 202 + 轮询，而不是同步等结果？
   <details><summary>参考答案</summary>评测要真实调用模型跑 18 条用例，耗时几十秒到几分钟不可控。同步等待会长时间占住 HTTP 连接和线程，客户端也容易超时。202 + job_id 把耗时任务异步化，客户端按自己的节奏轮询 `/eval/jobs/{id}` 拿进度和结果。</details>

5. `/trace/bus` 里你的请求如果只有节点没有边，说明发生了什么？
   <details><summary>参考答案</summary>协商模式已经开启（Agent 拿到了 ask_agent 等协作工具），但**模型选择不调用它们**，直接作答。图里记录了参与者和消息，没有消息就没有边。用不用工具是模型的自由裁量，不是 bug——想确定性看到 ASK 全流程就跑 `mvn test "-Dtest=AskFlowTest"`（本地桩，100% 可控）。</details>

6. 为什么轨迹里存的是用户身份的哈希而不是原文？
   <details><summary>参考答案</summary>轨迹是可观测数据，但用户 ID 属于敏感信息：存原文 = 任何能看轨迹的人都能拿到用户标识，泄露面扩大。SHA-256 哈希后：同一用户的多次请求仍是同一个哈希（可关联分析），但无法反推原始 ID（不可逆）。隐私最小化与可观测性兼得（第 11 节展开）。</details>
7. `.env` 被 gitignore 了——用 `git check-ignore .env` 验证一下。想一想：如果 Key 被误提交到 git，最安全的处理方式是什么？
   <details><summary>参考答案</summary>仅仅删掉再提交是没用的——Key 还在 git 历史里（`git log -S 你的Key前几位` 能翻出来），任何拿到仓库历史的人都能恢复它。正确顺序：① **立即去密钥服务商（DeepSeek 控制台）吊销并重新生成 Key**，让泄露的那个失效——这是唯一真正"止血"的一步；② 再清理历史：用 `git filter-repo` 或 BFG Repo-Cleaner 从全部历史中抹掉该文件后 force push（仓库公开过尤其必要）；③ 事后加 pre-commit 检查（如 git-secrets）防复发。原则：**轮换密钥永远优先于清理现场**。</details>

---

## 8. 下节预告

**第二节：跟踪一次请求的全生命周期**（已发布：[lesson-02-request-lifecycle.md](./lesson-02-request-lifecycle.md)）——从 `EchoMindController` 一路走到 Lua 原子提交，覆盖会话锁、幂等回执、记忆组装、意图融合、路由、校验、存储八个环节。这是整个课程最重要的一节。

**课前准备（15 分钟）**：

1. 打开 `src/main/java/com/echomind/chat/ChatService.java`，只读方法名和注释，画出"你认为的调用顺序猜想图"
2. 打开 `src/main/java/com/echomind/api/EchoMindController.java`，看 `/chat` 入口做了哪几件事
3. 带着你的猜想图来上课——对得上说明架构直觉已建立，对不上正是下一节的收获

---

*第一节完。下一节：[lesson-02-request-lifecycle.md](./lesson-02-request-lifecycle.md) · 配套路线图：`learn/LEARNING_GUIDE.md`。*
