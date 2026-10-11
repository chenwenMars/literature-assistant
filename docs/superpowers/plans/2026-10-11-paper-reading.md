# MBA论文精读与写作学习 Implementation Plan

> **For agentic workers:** Use native execution in this session, task by task. User has authorized the remaining design and implementation decisions.

**Goal:** 实现可回查的六部分精读报告、阅读目录及持久化借鉴笔记。
**Architecture:** 复用Markdown和现有导入链路；增量保存原文片段与笔记；生成结果和运输连接生命周期解耦。
**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、H2、Vue 3、Marked 12、Mermaid 10、DOMPurify。
**Spec:** docs/superpowers/specs/2026-10-11-paper-reading-design.md

## Global Constraints
Java四空格，JS两空格单引号无分号；com.literature包；保留现有工作区改动和历史报告；不虚构成功或企业资料。

## Review Focus
DOCX表格不得丢失；AI断流不得保存为完整；历史报告不得套六章覆盖；笔记不得跨文献修改；模型HTML不得执行。

### Task 1: 解析与来源
- [x] 创建SourceDocument及嵌套Block；extractDocument(String)返回片段与能力说明，toPromptText()生成带编号输入。
- [x] FileProcessingServiceTest构造段落—表格—段落DOCX并断言顺序、嵌套文本；构造两页PDF并断言文件页序；保留Markdown表格；空白文本抛出BusinessException。
- [x] 运行 `mvn -Dtest=FileProcessingServiceTest test`。

### Task 2: 生成完整性与报告
- [x] 六部分提示词保留证据、研究类型、敏捷概念边界，增加来源链接契约及用户题目边界。
- [x] generateReadingGuideStream返回CompletableFuture<String>；模型stop和DONE才完成，其他结束原因/断流异常完成。Controller从future成功回调保存与分类，失败不保存部分正文。
- [x] generateClassification同步抛异常；旧异步接口明确更新分类失败状态。批量使用同步分类并以实际结果计数。
- [x] AI测试增加length、缺DONE、错误JSON、无正文、分类无效格式；执行 `mvn -Dtest=LiteratureAiServiceTest test`。

### Task 3: 原文快照与笔记
- [x] db.sql增量添加source_document/report_version/error_message和literature_note；entity/VO/mapper同步字段。
- [x] GET /literature/{id}/source返回快照；POST同路径为历史文献解析并保存，不修改历史报告。
- [x] GET/POST /literature/{id}/notes，PUT/DELETE /literature/{id}/notes/{noteId}，Controller校验，service核对活动文献及联合主键条件。
- [x] MVC测试执行完整CRUD、跨文献、非法输入、历史重解析和删除后拒绝访问；运行相关测试及 `mvn test`。

### Task 4: 阅读与笔记页面
- [x] GuideReader组件共用安全Markdown渲染、目录、Mermaid和来源链接；导入预览接入组件，取消原有全局错误屏蔽。
- [x] 详情折叠文件信息，提供原文抽屉搜索、选择摘录/章节建笔记、笔记CRUD和Markdown导出。
- [x] Node测试HTML过滤、重复标题锚点、来源链接、代码围栏中的伪标题；执行 `node --test tests/*.test.js` 和 `npm run build`。

### Task 5: 验证与交付
- [x] 更新提示词验收文档和功能说明；明确历史报告及真实模型质量验证边界。
- [x] 隔离H2启动应用，用真实API建立明确测试文献；浏览器验证来源、目录、笔记刷新、修改删除、导出、移动端、图表及可见失败。
- [x] 最终 `mvn clean package`、前端测试/构建、diff检查；记录实际结果，保留用户其他改动。

## 执行结果（2026-10-11）

- Java 21执行 `mvn clean package`：36项测试全部通过，JAR构建成功。包含连接关闭后保持已保存报告成功状态的回归测试。
- 前端 `npm test`：13项通过；`npm run build`成功，仍有Mermaid及组件库的大文件体积提示。
- 隔离H2/端口验收：目录、来源跳转和搜索、历史原文重解析、章节及选中文字记笔记、持久化刷新、修改删除、导出、全屏、窄屏、单篇和批量失败提示通过。未改动正在运行的开发服务与其数据库。
- 实际核对流程图、思维导图及时序图文字；流程图改用SVG文字避免安全过滤移除foreignObject内部标签。图表语法错误保留代码并明确展示错误。
- `git diff --check`通过。保留用户既有的包迁移、删除功能及其他工作区改动，未提交。
- 人工报告与本地HTTP协议测试仅用于功能验证。真实模型的论文分析质量尚未评测，验收标准见 `docs/prompt-evaluation.md`。
