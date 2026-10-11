# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在此代码库中工作时提供指导。

## 项目概述

这是一个集成 AI 的全栈文献管理应用程序：
- 后端：Spring Boot 3.5.5 + Java 21
- 前端：Vue 3 + Vite
- 数据库：H2 嵌入式数据库 + MyBatis-Plus
- AI 集成：Kimi AI API 用于生成阅读指南和文档分类
- 功能：文档上传（PDF/Word/Markdown）、AI 驱动的阅读指南、文档搜索和管理

## 代码库结构

```
literature-assistant/
├── src/main/java/                 # 后端 Java 源代码
│   └── com/literature/     # 主包
│       ├── controller/           # REST API 控制器
│       ├── service/              # 业务逻辑服务
│       ├── entity/               # 数据库实体
│       ├── dto/                  # 数据传输对象
│       ├── mapper/               # MyBatis 映射器
│       ├── common/               # 共享工具和基类
│       └── config/               # 配置类
├── src/main/resources/           # 后端资源
│   ├── application.yml           # 主配置文件
│   ├── db.sql                    # 数据库模式
│   ├── mapper/                   # MyBatis XML 映射器
│   └── prompts/                  # AI 提示模板
├── literature-assistant-frontend/ # 前端 Vue 应用程序
│   ├── src/
│   │   ├── components/          # Vue 组件
│   │   ├── views/               # 页面组件
│   │   ├── stores/              # Pinia 状态管理
│   │   ├── router/              # Vue Router 配置
│   │   └── main.js              # 应用程序入口点
│   ├── package.json             # 前端依赖
│   └── vite.config.js           # Vite 配置
├── start.sh / start.bat         # 启动前后端的脚本
├── pom.xml                      # Maven 构建配置
└── README.md                    # 项目文档
```

## 常见开发任务

### 后端开发

1. **添加新的 API 端点**：在 `src/main/java/com/literature/controller/` 中向控制器添加方法
2. **添加数据库实体**：在 `src/main/java/com/literature/entity/` 中创建新类
3. **添加业务逻辑**：在 `src/main/java/com/literature/service/` 中实现服务
4. **添加数据传输对象**：在 `src/main/java/com/literature/dto/` 中创建 DTO
5. **数据库查询**：使用 `src/main/java/com/literature/mapper/` 中的 MyBatis 映射器和 `src/main/resources/mapper/` 中的 XML 文件

### 前端开发

1. **添加新页面**：在 `literature-assistant-frontend/src/views/` 中创建 Vue 组件
2. **添加可复用组件**：在 `literature-assistant-frontend/src/components/` 中创建 Vue 组件
3. **状态管理**：在 `literature-assistant-frontend/src/stores/` 中使用 Pinia 存储
4. **路由配置**：在 `literature-assistant-frontend/src/router/index.js` 中配置路由

## 构建和运行命令

### 后端

```bash
# 编译并运行后端（开发模式）
mvn clean compile
mvn spring-boot:run

# 打包用于生产环境
mvn clean package

# 运行打包后的 JAR
java -jar target/literature-assistant-0.0.1-SNAPSHOT.jar
```

### 前端

```bash
# 安装依赖
cd literature-assistant-frontend
npm install

# 开发服务器
npm run dev

# 生产构建
npm run build

# 预览生产构建
npm run preview
```

### 完整应用程序

```bash
# 使用一个脚本启动前后端
./start.sh     # Linux/macOS
start.bat      # Windows
```

## 配置

### 后端配置 (`application.yml`)
- 服务器端口：8086
- 上下文路径：/api
- 数据库：H2 嵌入式数据库位于 `./data/literature_assistant`
- 文件上传路径：`./uploads/documents`
- AI 服务：Kimi AI API 位于 `https://api.moonshot.cn/v1`

### 前端配置 (`vite.config.js`)
- 开发服务器端口：3000
- API 代理：`/api` -> `http://localhost:8086`

## API 端点

### 后端
- `POST /api/literature/generate-guide` - 生成阅读指南 (SSE)
- `POST /api/literature/page` - 查询文献列表
- `GET /api/literature/{id}` - 获取文献详情
- `GET /api/literature/{id}/download` - 下载文献文件
- `POST /api/literature/batch-import` - 批量导入文献 (SSE)
- `GET /api/literature/health` - 健康检查

### 前端
- `/` - 文献列表页面
- `/literature/:id` - 文献详情页面

## 开发指南

### 后端
1. 使用 Lombok 减少样板代码
2. 遵循 REST API 约定
3. 使用 MyBatis-Plus 进行数据库操作
4. 使用全局异常处理器处理异常
5. 使用 Hutool 工具库进行常见操作

### 前端
1. 使用 Vue 3 Composition API 和 `<script setup>`
2. 使用 Pinia 进行状态管理
3. 使用 Element Plus 作为 UI 组件
4. 使用 Axios 进行 HTTP 请求
5. 使用 marked 进行 Markdown 渲染
6. 使用 mermaid 进行图表渲染

## 测试

```bash
# 运行后端测试
mvn test
```

## 部署

1. 打包后端：`mvn clean package`
2. 构建前端：`npm run build`（在前端目录中）
3. 部署前端构建并代理 API 请求到后端
4. 确保后端可以访问 H2 数据库文件

## AI 集成

应用程序与 Kimi AI API 集成用于：
1. 从文档内容生成阅读指南
2. 使用标签对文档进行分类

API 密钥由用户在运行时提供，不会被存储。