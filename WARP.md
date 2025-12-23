# WARP.md

这是一个集成 AI 的全栈文献管理应用,主要功能包括文献上传(PDF/Word/Markdown)、AI 阅读指南生成、智能分类和检索。

## 核心架构

### 后端架构 (Spring Boot 3.5.5 + Java 21)
- **分层结构**: Controller → Service → Mapper → Entity
- **数据访问**: MyBatis-Plus (Java) + XML 映射文件 (`src/main/resources/mapper/`)
- **AI 集成**: 使用 OkHttp SSE 实现与 Kimi AI API 的流式通信
- **文件处理**: 
  - PDF: Apache PDFBox
  - Word: Apache POI (支持 .doc 和 .docx)
  - Markdown: CommonMark
- **统一响应**: 全局异常处理器 `GlobalExceptionHandler` + 统一返回格式 `Result`
- **数据库**: H2 嵌入式数据库,数据文件在 `./data/` 目录

### 前端架构 (Vue 3 + Vite)
- **技术栈**: Vue 3 Composition API (script setup) + Pinia + Element Plus + Vue Router
- **关键库**:
  - `marked`: Markdown 渲染
  - `mermaid`: 图表渲染
  - `@microsoft/fetch-event-source`: SSE 事件源处理
- **API 通信**: Axios + SSE 流式响应

### 关键流程
1. **文献上传流程**: 
   - 前端上传 → Controller 接收 → FileProcessingService 解析文件内容 → 保存到数据库和文件系统
   - 文件存储路径: `./uploads/documents/`

2. **AI 阅读指南生成**: 
   - 使用 SSE 实现实时流式响应
   - 提示词模板位于 `src/main/resources/prompts/`
   - LiteratureAiService 负责与 Kimi AI API 交互

3. **智能分类**: 
   - AI 自动为文献生成标签
   - 分类提示词: `prompts/literature-classification-system-prompt.txt`

## 常用命令

### 后端开发
```bash
# 开发模式运行
mvn spring-boot:run

# 编译
mvn clean compile

# 打包
mvn clean package

# 运行打包后的 JAR
java -jar target/literature-assistant-0.0.1-SNAPSHOT.jar

# 运行测试
mvn test
```

### 前端开发
```bash
cd literature-assistant-frontend

# 安装依赖
npm install

# 开发服务器 (端口 3000)
npm run dev

# 生产构建
npm run build

# 预览生产构建
npm run preview
```

### 完整应用启动
```bash
# macOS/Linux
./start.sh

# Windows
start.bat
```

## 配置说明

### 后端配置 (`application.yml`)
- **服务器**: 端口 8086, 上下文路径 `/api`
- **数据库**: H2 文件数据库 `./data/literature_assistant`
- **文件上传**: 最大 10MB, 存储在 `./uploads/documents`
- **AI 配置**: 
  - API 地址: `https://api.moonshot.cn/v1`
  - 模型: `kimi-k2-turbo-preview`
  - API Key: 由用户在运行时提供,不存储

### 前端配置 (`vite.config.js`)
- **开发服务器**: 端口 3000
- **API 代理**: `/api` → `http://localhost:8086`

## 访问地址
- 应用服务: http://localhost:8086/api
- API 文档 (Knife4j): http://localhost:8086/api/doc.html
- 前端应用: http://localhost:3000
- 健康检查: http://localhost:8086/api/literature/health

## 开发注意事项

### 后端开发
- 所有 API 返回使用 `Result<T>` 统一格式
- 业务异常抛出 `BusinessException`,由全局异常处理器捕获
- 使用 Lombok 简化代码 (如 `@Data`, `@Slf4j`)
- Controller 只负责参数校验和调用 Service
- 数据库查询优先使用 MyBatis-Plus 的 BaseMapper 方法
- 复杂查询使用 XML 映射文件 (`src/main/resources/mapper/LiteratureMapper.xml`)

### 前端开发
- 使用 Composition API 和 `<script setup>` 语法
- 状态管理使用 Pinia stores (`src/stores/`)
- HTTP 请求统一通过 Axios
- SSE 流式响应使用 `@microsoft/fetch-event-source`
- Element Plus 组件库用于 UI

### 添加新文件类型支持
1. 在 `FileProcessingService` 中添加解析方法
2. 更新 `application.yml` 中的 `allowed-extensions`
3. 在 `extractFileContent` 方法中添加类型判断分支

### 扩展查询条件
1. 在 `LiteratureQueryRequest` DTO 中添加字段
2. 更新 `src/main/resources/mapper/LiteratureMapper.xml` SQL 查询
3. 考虑数据库索引优化

## 数据库
- 数据库脚本: `src/main/resources/db.sql`
- 表名: `literature`
- 主键: `id` (自增)
- 包含字段: title, author, content, tags, file_path, file_type, reading_guide, created_at, updated_at, deleted

## AI 集成说明
- API Key 不存储在数据库,由用户每次使用时提供
- 支持流式响应,实时展示生成进度
- 提示词模板可在 `src/main/resources/prompts/` 目录自定义
