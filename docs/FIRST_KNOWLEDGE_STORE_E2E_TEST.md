# First KnowledgeStore End-to-End Test

## Verified scope

- External embedding profile works.
- `/embedding test` returns a vector with expected dimensions.
- SQLite KnowledgeStore initializes successfully.
- Agent can call `knowledge_upsert`.
- Saved document creates documents, chunks, and chunk embeddings.
- `keyword_search` returns saved content.
- `knowledge_search` returns saved content.
- `knowledge_update` refreshes searchable content.
- `knowledge_delete` removes content from search results.
- ContextSession natural language entry remains active.

## Manual commands used

```text
/embedding status
/embedding test
/knowledge status
/chat 请把这段设定保存到知识库，collection 用 first-test：乌萨斯边境感染者救援点容易引发地方军警注意，罗德岛必须通过中间人暗线联系。标题写"乌萨斯边境救援点暗线联系规则"，sourceType 用 agent_note。
/knowledge status
/chat 用 keyword_search 在 collection=first-test 里查"乌萨斯 感染者 救援点"，并告诉我查到了什么。
/chat 用 knowledge_search 在 collection=first-test 里查："边境救助站会引发什么政治风险？" 然后根据检索结果回答。
/chat 请更新刚才 first-test 里的"乌萨斯边境救援点暗线联系规则"：补充一句"如果地方军警开始巡查，救援点应暂停公开物资分发，改用夜间小批量转运"。请使用 knowledge_update，不要新建重复文档。
/chat 用 keyword_search 在 collection=first-test 里查"夜间小批量转运"，告诉我是否查到了更新后的内容。
/chat 请删除 collection=first-test 里标题为"乌萨斯边境救援点暗线联系规则"的知识库文档。请使用 knowledge_delete。
/chat 用 keyword_search 在 collection=first-test 里查"乌萨斯 感染者 救援点"，告诉我还能不能查到。
```

## Notes

* LocalSmallEmbeddingModel is still a stub.
* ONNX Runtime is not implemented.
* `keyword_search` is lexical search, not semantic search.
* `knowledge_search` requires a valid active EmbeddingProfile.
* Different EmbeddingProfiles must not be mixed.
