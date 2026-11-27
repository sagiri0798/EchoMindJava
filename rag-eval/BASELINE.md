# RAG v1 基线

运行日期：2026-10-29

环境：

- Java 21，EchoMind Java `0.1.0`
- `SEMANTIC_STORAGE_MODE=local`，使用项目内置 all-MiniLM 向量
- DeepSeek profile，查询改写和回答使用同一运行服务
- 10 篇语料、10 个知识片段、26 个问题、Top-K=5
- 参考答案和证据由同一 Judge 评分

## 完整生成流程

| 指标 | 结果 |
| --- | ---: |
| Hit@5 | 1.0000 |
| Recall@1 | 0.8750 |
| Recall@3 | 0.9792 |
| Recall@5 | 1.0000 |
| MRR@5 | 1.0000 |
| nDCG@5 | 0.9904 |
| Judge accuracy | 0.9827 |
| Judge overall | 0.9548 |
| RAG 闭环通过率 | 0.8846（23/26） |

归因结果：

| 归因 | 数量 |
| --- | ---: |
| `pass` | 23 |
| `agent_skipped_retrieval` | 3 |

三个 badcase 的最终回答 Judge 得分仍然较高，但 `/chat` 的 `knowledge_used=false`。这说明当前分别暴露“回答质量”和“是否真正使用检索证据”是必要的：

- `payment-duplicate-charge`
- `subscription-cancel-renewal`
- `handoff-urgent-account-security`

## 无模型改写对照

同一语料和本地向量在模型不可用、改写退回原查询时：

| 指标 | 结果 |
| --- | ---: |
| Recall@5 | 0.9583 |
| nDCG@5 | 0.9644 |

本次对照中启用查询改写后 Recall@5 从 0.9583 提升到 1.0000，但这是一次固定小样本基线，不能外推为生产收益。

## 解释边界

- 这是 26 条 smoke/回归基线，不是统计意义上的生产准确率。
- 语义存储模式是本地模式；ChromaDB 模式和真实企业语料要分别建立基线。
- `/search` 只返回重排后的最终 Top-K，因此当前暂不能区分初召回失败与重排淘汰。
- 标签仍需要业务专家复核，当前状态为 `pending_business_expert_review`。
