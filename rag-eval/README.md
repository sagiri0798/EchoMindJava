# EchoMind Java RAG 评测

这个目录提供一套可重复的 RAG 评测闭环：

```text
冻结语料 -> 导入专用知识库 -> /search Top-K
         -> 计算 Recall@K / MRR / nDCG
         -> /chat 生成真实回答
         -> /eval/conversation 使用金标参考答案和证据评分
         -> 失败阶段归因 -> JSON 报告
```

数据集为 `dataset-v1.json`，包含 10 篇演示客服政策和 26 个问题，覆盖单文档、多跳以及不可回答样本。

## 数据约定

- `gold_chunks[].document_id + chunk` 是人工金标，不需要手写 Java 哈希。
- 运行器使用与 `Hashing.key` 相同的算法解析成稳定的 `chunk_id`。
- `gold_facts` 必须原样出现在对应金标文档中，验证阶段会检查。
- `answerable=false` 的样本必须没有 `gold_chunks`，并要求 `expected_abstain=true`。
- 当前 v1 每篇文档不超过 500 个 Unicode 码点，固定对应第 0 片。

## 1. 先验证数据

不启动服务，也不需要额外 Python 依赖：

```powershell
python .\rag-eval\run_rag_eval.py validate
python -m unittest discover -s .\rag-eval -p "test_*.py" -v
```

## 2. 启动隔离的 Java 服务

检索指标会受到其他知识库文档影响。第一次跑基线时，应使用空知识库：

```powershell
$env:KNOWLEDGE_SEED_DEMO="false"
$env:ECHOMIND_DATA_DIR="$env:TEMP\echomind-rag-eval"
$env:SEMANTIC_STORAGE_MODE="disabled"
$env:REDIS_PORT="6380"
.\mvnw.cmd spring-boot:run
```

生产式语义检索可改用：

```powershell
$env:SEMANTIC_STORAGE_MODE="chroma"
$env:CHROMA_BASE_URL="http://127.0.0.1:8002"
```

注意先修复 Java 启动类构建问题。若开启鉴权，导入使用管理员 Token，聊天和会话评分使用同一个普通用户 Token。

## 3. 跑 Top-K 召回

只测检索，不调用聊天模型：

```powershell
python .\rag-eval\run_rag_eval.py run `
  --seed `
  --require-empty-corpus `
  --retrieval-only `
  --top-k 5
```

报告默认写入 `rag-eval/reports/<run_id>.json`，会给出：

- Hit@1/3/5
- Recall@1/3/5
- Precision@K
- MRR@K
- nDCG@K
- 分类别汇总
- 不可回答问题仍然检索到内容的数量

`generation.rag_pipeline_pass_rate` 要求“检索命中、Agent 使用知识库、回答通过校验并达到评分阈值”；`generation.answer_pass_rate` 只判断最终回答质量，便于区分“答案碰巧正确但没用 RAG”和真正的 RAG 闭环成功。

## 4. 跑回答生成和归因

```powershell
$env:ECHOMIND_ADMIN_TOKEN="管理员 Token"
$env:ECHOMIND_RUN_TOKEN="普通用户 Token"

python .\rag-eval\run_rag_eval.py run `
  --seed `
  --require-empty-corpus `
  --top-k 5 `
  --min-recall 0.80 `
  --min-pass-rate 0.75
```

每个问题会：

1. 用 `/search` 获取最终 Top-K chunk ID；
2. 用唯一 `client_request_id` 调用 `/chat`；
3. 用 `/eval/conversation` 对已保存回答做相关性、准确性、完整性和有用性评分；
4. 按以下顺序给出首个可观察失败阶段：

```text
retrieval_error
retrieval_miss
partial_retrieval_miss
agent_failure
agent_skipped_retrieval
answer_verification_failed
ungrounded_generation
incorrect_generation
answer_quality_below_threshold
judge_unavailable
pass
```

如果没有管理员和用户 Token，可传 `--admin-token`、`--run-token`；开发模式未开启鉴权时可以为空。

## 5. 增量调试

```powershell
# 只跑退款和多跳问题
python .\rag-eval\run_rag_eval.py run --category refund --category multi_hop

# 只跑一个用例
python .\rag-eval\run_rag_eval.py run --case-id refund-arrival-time

# 跑真实回答但先不调用 Judge
python .\rag-eval\run_rag_eval.py run --skip-judge
```

## 当前归因边界

`/search` 暴露的是改写、召回和重排后的最终 Top-K，没有暴露重排前候选，因此当前可以区分：

- 最终 Top-K 没有金标：`retrieval_miss`
- 多跳问题只召回部分金标：`partial_retrieval_miss`
- 直接检索命中，但 Agent 没有使用知识库：`agent_skipped_retrieval`
- 使用了知识库但回答不符合证据：`ungrounded_generation` 或 `incorrect_generation`

还不能单独区分“初召回失败”和“已召回但被重排掉”。下一版应在 `KnowledgeToolManager` 保存重排前后候选 ID，再增加 `rerank_miss`。

## 报告指标怎么解释

- Recall 衡量是否找到了全部金标片段，不等同于回答正确率。
- nDCG 衡量金标片段是否排在前面，适合比较改写和重排策略。
- Judge 的四维分是模型判断，不能替代业务事实核验。
- 每次评测必须使用同一语料版本、Top-K、模型和 Judge 配置才能横向比较。

本次仓库内验证结果见 [BASELINE.md](BASELINE.md)。
