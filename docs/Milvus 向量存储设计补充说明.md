# Milvus 向量存储设计补充说明

## 1. 目的

这份补充文档只解决一件事：把第一版 RAG 的 Milvus 落地口径固定下来，避免后续在实现、建库、联调时反复改名字和字段。

原则保持不变：

- MySQL 是业务真相源
- Milvus 只负责向量检索
- MySQL 和 Milvus 通过稳定的关联字段做映射，不在 Milvus 里重复保存整份业务主数据

## 2. 第一版固定约定

第一版直接固定以下命名：

- Milvus database：`openagent`
- 默认 collection：`knowledge_segment`
- partition 命名：`kb_{knowledgeBaseId}`

当前环境补充说明（2026-03-17）：

- Milvus database `openagent` 已手工创建完成
- collection `knowledge_segment` 已手工创建完成
- 当前本地联调环境中，这个 collection 上已有向量索引按 `IP` 度量创建；后端配置需要和它保持一致
- 后续不再要求用户手工建 partition
- partition 应由系统在“新建知识库”成功后通过代码自动创建或校验存在

示例：

- 知识库 `id=1` 的 partition：`kb_1`
- 知识库 `id=25` 的 partition：`kb_25`

这样做的原因很简单：

- 当前架构已经确定为“单 collection + 多 partition”
- 多知识库联合检索时，只需要把用户勾选的知识库 ID 转成 partition 列表
- MySQL `knowledge_base` 表里已经预留了 `milvus_database_name`、`milvus_collection_name`、`milvus_partition_name`

## 3. 与 MySQL 的关联规则

现有 MySQL 表里已经有关键关联字段：

- `knowledge_base.milvus_database_name`
- `knowledge_base.milvus_collection_name`
- `knowledge_base.milvus_partition_name`
- `knowledge_segment.milvus_primary_key`

第一版建议固定映射如下：

| MySQL 字段 | 含义 | 第一版建议值 |
| --- | --- | --- |
| `knowledge_base.milvus_database_name` | 所属 Milvus 数据库 | `openagent` |
| `knowledge_base.milvus_collection_name` | 所属 collection | `knowledge_segment` |
| `knowledge_base.milvus_partition_name` | 所属 partition | `kb_{knowledgeBaseId}` |
| `knowledge_segment.milvus_primary_key` | 对应 Milvus 主键值 | 与 Milvus 主键字段值完全一致 |

这里要注意一个边界：

- `knowledge_segment.id` 是 MySQL 主键
- `knowledge_segment.milvus_primary_key` 是跨 MySQL / Milvus 的关联键

第一版不要直接依赖 MySQL 自增 ID 当 Milvus 主键，而是建议在应用层提前生成稳定字符串，然后同时写入：

- MySQL `knowledge_segment.milvus_primary_key`
- Milvus 主键字段

推荐格式：

- `ks_{knowledgeBaseId}_{fileId}_{segmentNo}`

示例：

- `ks_1_2001_0`
- `ks_1_2001_1`
- `ks_25_9008_12`

这样有几个好处：

- 插入 MySQL `knowledge_segment` 时就能拿到完整关联键，不需要等自增主键回填
- 删除、重建索引时，可以稳定定位到同一个 segment
- 联调时肉眼就能看出这条向量对应哪个知识库、哪个文件、哪个分段

## 4. 第一版 collection schema 建议

第一版建议 Milvus 里只保留“检索必须字段”和“关联必须字段”，不要把 `full_text` 再存一份进去。

推荐字段如下：

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `segment_ref` | `VarChar(128)` | 主键。值等于 `knowledge_segment.milvus_primary_key` |
| `knowledge_base_id` | `Int64` | 知识库 ID，便于排查和后续 filter 扩展 |
| `file_id` | `Int64` | 文件 ID |
| `segment_no` | `Int32` | 分段序号 |
| `page_no` | `Int32` | 页码。无页码时建议写 `-1` |
| `embedding` | `FloatVector(dim)` | 向量字段，`dim` 必须与当前 embedding 模型输出维度一致 |

建议说明：

- 主键字段使用 `VarChar`，不要用自增整型
- 向量字段名统一叫 `embedding`，后端代码里最好也按这个名字封装
- 如果当前 embedding 模型是 `BAAI/bge-m3`，通常可按 `1024` 维理解；但最终仍应以实际 embedding 服务返回维度为准，并同步写入 `knowledge_base.embedding_dimension`

## 5. 为什么 Milvus 不保存原文片段

第一版建议不要把这些字段放进 Milvus：

- `full_text`
- `text_preview`
- `metadata_json`
- `source_title`
- `source_path`

原因：

- 这些内容 MySQL `knowledge_segment` 已经保存
- 如果 Milvus 再存一份，会带来双写和一致性问题
- 检索阶段只需要先拿到命中 ID，再回查 MySQL 批量取原文即可

因此建议检索流程固定为：

1. 对用户问题做 embedding
2. 按 `knowledgeBaseIds` 转成 partition 列表检索 Milvus
3. Milvus 返回 `segment_ref` 和必要元数据
4. 再按 `knowledge_segment.milvus_primary_key in (...)` 批量查询 MySQL
5. 从 MySQL 取 `full_text`、`text_preview`、`metadata_json`，再注入模型上下文

## 6. 创建 collection 时的额外约定

第一版建议再固定两条：

- 同一个 collection 内，所有知识库必须使用相同 embedding 模型和相同维度
- 如果后续要混用不同 embedding 模型或不同维度，直接拆新的 collection，不要混进 `knowledge_segment`

也就是说：

- 当前默认 collection：`knowledge_segment`
- 仅用于同一 embedding schema 的知识库

如果后面确实要切模型，可以新建类似命名：

- `knowledge_segment_bge_m3_1024`
- `knowledge_segment_bge_large_1024`

但在当前阶段，不建议一开始就把命名搞复杂，先把 `knowledge_segment` 跑通。

## 7. 索引与度量建议

这份文档先锁定 schema，不强行锁定具体索引实现参数。

当前建议：

- 距离度量：优先 `COSINE`
- 索引类型：先用 Milvus 默认可接受方案，或 `AUTOINDEX`

当前实现补充（2026-03-18）：

- 代码层已经支持通过 `milvus.metric-type` 指定检索度量
- 若当前环境里的手工 collection / index 是按 `IP` 建的，本地配置应显式写成 `milvus.metric-type=IP`
- 如果后续重建 collection / index 并切回 `COSINE`，要同步更新配置，否则会出现 `expected=... actual=...` 的检索报错

后面如果检索规模上来，再单独评估：

- `HNSW`
- `IVF_FLAT`
- `IVF_SQ8`

第一版重点不是调优，而是先把“入库 -> 检索 -> 回查 MySQL -> 注入上下文”这条闭环跑通。

## 8. 建库执行清单

当前环境里，前 3 步已经手工完成；后续代码应负责第 4 步：

1. Milvus database：`openagent` 已创建
2. collection：`knowledge_segment` 已创建
3. 用户点击“新建知识库”
4. 后端创建 `knowledge_base` 记录
5. 后端按 `kb_{knowledgeBaseId}` 创建或校验 partition
6. 后端回写：
   - `milvus_database_name=openagent`
   - `milvus_collection_name=knowledge_segment`
   - `milvus_partition_name=kb_{knowledgeBaseId}`

## 9. 最终结论

第一版最稳的做法就是：

- Milvus database 固定为 `openagent`
- collection 固定为 `knowledge_segment`
- 一个知识库对应一个 partition：`kb_{knowledgeBaseId}`
- MySQL 与 Milvus 通过 `knowledge_segment.milvus_primary_key` <-> `segment_ref` 做一一关联

这样既和现有 MySQL 设计对齐，也能把后续 RAG 接入复杂度压到最低。
