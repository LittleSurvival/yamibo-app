# Cloud Nine — 接口调用文档

本文档面向接口调用方（前端、第三方客户端），描述后端 Worker 暴露的全部 HTTP / WebSocket 接口。

> 架构背景：后端为 Cloudflare Worker（Hono），`<domain>/api/*` 路由到 Worker。文件元数据主存 KV，文件列表 / 搜索 / 存储统计 / 回收站 / 分享记录走 D1。文件真实内容存储在 Telegram 超级群组（Bot API）。

---

## 1. 基础信息

### 1.1 Base URL

| 环境 | 地址 |
|------|------|
| 生产 | `<your-domain.com>/api` |
| 本地默认（前端同域） | `window.location.origin + "/api"` |

前端 `API_BASE` 解析优先级：

1. `window.__TCD_API_BASE__`（由 `index.html` 内联 `<script>` 设置）
2. 同域 `/api`（默认）

### 1.2 认证方式

除公开接口外，均使用 **JWT Bearer**：

```
Authorization: Bearer <access_token>
```

| Token 类型 | 有效期 | 签发方式 |
|-----------|--------|---------|
| access_token | 15 分钟 | 登录 / 注册 / 刷新 |
| refresh_token | 7 天 | 登录 / 注册 / 刷新 |
| preview token | 30 分钟 | `GET /api/files/:id/preview` |
| admin token | 24 小时 | `POST /api/admin/login` |

> access_token 过期后，前端应调用 `POST /api/auth/refresh` 换取新 token 对（项目内 `ApiClient` 已实现自动刷新 + 重试）。

### 1.3 通用响应格式

所有 JSON 接口统一返回：

```jsonc
{
  "success": true,          // 是否成功
  "data": { ... },          // 业务数据（成功时）
  "message": "...",         // 提示信息（可选）
  "error": "..."            // 错误信息（失败时）
}
```

二进制接口（下载 / chunk / stream）直接返回文件流，失败时才返回上述 JSON。

### 1.4 常见 HTTP 状态码

| 状态码 | 含义 |
|-------|------|
| 200 / 201 | 成功 |
| 400 | 参数错误 / 校验失败 |
| 401 | 未认证 / 密码错误 |
| 403 | 无权限 / 分享提取码错误 |
| 404 | 资源不存在 |
| 409 | 冲突（用户名/邮箱/同名文件夹已存在） |
| 410 | 分享已过期 / 已达最大下载次数 |
| 429 | 登录尝试过于频繁 |

---

## 2. 认证接口

### 2.1 注册 — `POST /api/auth/register`

**认证**：无需。

**请求体**：

```jsonc
{
  "username": "alice",        // 必填，3-32 字符，仅字母/数字/下划线
  "password": "password123",  // 必填，至少 8 字符
  "email": "alice@example.com" // 可选
}
```

**成功响应** `201`：

```jsonc
{
  "success": true,
  "data": {
    "user": { "id": 1, "username": "alice", "email": "alice@example.com" },
    "access_token": "<jwt>",
    "refresh_token": "<jwt>",
    "expires_in": 900
  }
}
```

**错误响应**：`400`（参数/用户名/密码校验失败）、`409`（用户名或邮箱已存在）。

### 2.2 登录 — `POST /api/auth/login`

**认证**：无需。

**请求体**：

```jsonc
{ "username": "alice", "password": "password123" }
```

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "user": {
      "id": 1,
      "username": "alice",
      "email": "alice@example.com",
      "avatar_url": null,
      "storage_used": 0
    },
    "access_token": "<jwt>",
    "refresh_token": "<jwt>",
    "expires_in": 900
  }
}
```

**错误响应**：`401`（用户名或密码错误）、`429`（连续失败 5 次，锁定 1 分钟）。

### 2.3 刷新 Token — `POST /api/auth/refresh`

**认证**：无需。

**请求体**：

```jsonc
{ "refresh_token": "<refresh_token>" }
```

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "access_token": "<new_jwt>",
    "refresh_token": "<new_jwt>",
    "expires_in": 900
  }
}
```

**错误响应**：`400`（缺少 refresh_token）、`401`（无效或已过期）。

### 2.4 当前用户信息 — `GET /api/auth/me`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "id": 1,
    "username": "alice",
    "email": "alice@example.com",
    "avatar_url": null,
    "storage_used": 123456,
    "created_at": "..."
  }
}
```

### 2.5 登出 — `POST /api/auth/logout`

**认证**：JWT（吊销当前 access_token，加入 KV 黑名单 15 分钟）。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "已登出" }
```

---

## 3. 文件接口

> 文件与文件夹共用统一 ID（KV UUID）。列表、搜索、回收站均走 D1。

### 3.1 文件列表 — `GET /api/files`

**认证**：JWT。

**Query 参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `parent_id` | string | 父文件夹 ID；省略时返回根目录（自动创建用户根文件夹） |
| `type` | string | 可选，`file` / `folder` 过滤 |

**成功响应** `200`（`data` 为混合条目数组）：

```jsonc
{
  "success": true,
  "data": [
    { // 文件条目
      "id": "uuid-file",
      "name": "report.pdf",
      "type": "file",
      "size": 1048576,
      "size_formatted": "1.00 MB",
      "mime_type": "application/pdf",
      "is_chunks": false,
      "created_at": 1750000000000,
      "updated_at": 1750000000000,
      "download_url": "/api/files/uuid-file/download"
    },
    { // 文件夹条目
      "id": "uuid-folder",
      "name": "docs",
      "type": "folder",
      "size": 0,
      "size_formatted": "",
      "child_count": 0,
      "created_at": 1750000000000,
      "path": ""
    }
  ]
}
```

### 3.2 新建文件夹 — `POST /api/files/folder`

**认证**：JWT。

**请求体**：

```jsonc
{ "name": "docs", "parent_id": null }  // parent_id 可选，省略则建在根目录
```

**成功响应** `201`：

```jsonc
{
  "success": true,
  "data": {
    "id": "uuid-folder",
    "name": "docs",
    "parent_id": null,
    "created_at": 1750000000000,
    "updated_at": 1750000000000,
    "path": "/1/docs",
    "child_count": 0,
    "user_id": 1
  }
}
```

**错误响应**：`400`（名称为空 / 含路径分隔符）、`409`（同名文件夹已存在）。

### 3.3 重命名 — `PUT /api/files/:id/rename`

**认证**：JWT。

**请求体**：

```jsonc
{ "name": "new-name.pdf" }
```

**成功响应** `200`：

```jsonc
{ "success": true, "message": "重命名成功" }
```

### 3.4 移动 — `PUT /api/files/:id/move`

**认证**：JWT。

**请求体**：

```jsonc
{ "target_parent_id": "uuid-folder" }  // 省略则移动到根目录
```

**成功响应** `200`：

```jsonc
{ "success": true, "message": "移动成功" }
```

### 3.5 移入回收站（软删除） — `DELETE /api/files/:id`

**认证**：JWT。文件与文件夹均支持（文件夹递归软删除）。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "已移入回收站" }
```

### 3.6 回收站列表 — `GET /api/files/trash`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": [
    {
      "id": "uuid-file",
      "name": "old.txt",
      "type": "file",
      "size": 123,
      "mime_type": "text/plain",
      "is_chunks": 0,
      "deleted_at": 1750000000000,
      "created_at": 1750000000000,
      "updated_at": 1750000000000
    }
  ]
}
```

### 3.7 恢复 — `POST /api/files/trash/:id/restore`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "已恢复" }
```

### 3.8 彻底删除单项 — `DELETE /api/files/trash/:id`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "已彻底删除" }
```

### 3.9 清空回收站 — `DELETE /api/files/trash`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "回收站已清空" }
```

> 注意：`DELETE /api/files/trash` 必须注册在 `DELETE /api/files/:id` 之前（服务端已保证）。

### 3.10 搜索 — `GET /api/files/search`

**认证**：JWT。

**Query 参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `q` | string | 关键词（LIKE 模糊匹配，最多返回 200 条） |

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": [
    {
      "id": "uuid-file",
      "name": "report.pdf",
      "type": "file",
      "size": 1048576,
      "size_formatted": "1.00 MB",
      "mime_type": "application/pdf",
      "is_chunks": false,
      "created_at": 1750000000000,
      "updated_at": 1750000000000
    }
  ]
}
```

**错误响应**：`400`（关键词为空）。

---

## 4. 上传接口

> 上传分三档：≤10MB 走单文件直传；>10MB 走分块（chunk + complete）；超大文件走 WebSocket。详细协议见 `UPLOAD_DOWNLOAD_ARCH.md`。

### 4.1 单文件上传 — `POST /api/files/upload`

**认证**：JWT。

**请求**：`multipart/form-data`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `file` | File | 文件内容 |
| `parent_id` | string | 可选，父文件夹 ID |

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "file_id": "uuid-file",
    "name": "a.jpg",
    "size": 123456,
    "mime_type": "image/jpeg",
    "is_chunks": false,
    "download_url": "/api/files/uuid-file/download"
  }
}
```

### 4.2 上传单个分块 — `POST /api/files/upload/chunk`

**认证**：JWT。

**请求**：请求体为二进制 chunk（原始 bytes）。

**请求头**：

| Header | 说明 |
|--------|------|
| `X-Filename` | 文件名（如 `movie.mp4`） |
| `X-Chunk-Index` | 分块索引（整数） |
| `X-Parent-Id` | 可选，父文件夹 ID |

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "index": 0,
    "file_id": "<telegram_file_id>",
    "message_id": 12345
  }
}
```

### 4.3 完成分块上传 — `POST /api/files/upload/complete`

**认证**：JWT。

**请求体**：

```jsonc
{
  "filename": "movie.mp4",
  "total_size": 1073741824,
  "parent_id": null,
  "file_ids": [
    { "index": 0, "file_id": "<telegram_file_id>", "size": 10485760 },
    { "index": 1, "file_id": "<telegram_file_id>", "size": 10485760 }
  ]
}
```

> `file_ids` 必须按 `index` 升序（服务端会再排序并校验 index 连续性、分块大小总和与 `total_size` 一致）。

**成功响应** `200`：

```jsonc
{ "success": true, "data": { "file_id": "uuid-file", "is_chunks": true } }
```

**错误响应**：`400`（file_ids 为空 / 大小校验不符）。

### 4.4 WebSocket 上传 — `WS /api/ws/upload?token=<access_token>`

**认证**：JWT（query 参数 `token`）。

用于超大文件（>100MB）分卷上传，参数：256KB 帧、65ms 间隔、2 卷并发、15s 心跳。详见 `UPLOAD_DOWNLOAD_ARCH.md`。

---

## 5. 下载接口

### 5.1 下载文件 — `GET /api/files/:id/download`

**认证**：JWT。

**响应**：文件二进制流。图片/视频/音频 `Content-Disposition: inline`，其余 `attachment`。支持 Range 请求。失败返回 JSON。

### 5.2 获取分块元数据 — `GET /api/files/:id/chunks`

**认证**：JWT（仅 `is_chunks=true` 的大文件）。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "name": "movie.mp4",
    "size": 1073741824,
    "mime_type": "video/mp4",
    "chunks": [
      { "index": 0, "size": 10485760 },
      { "index": 1, "size": 10485760 }
    ]
  }
}
```

### 5.3 下载单个分块 — `GET /api/files/:id/chunk/:index`

**认证**：JWT。

**响应**：`application/octet-stream` 二进制流（带 `Cache-Control: max-age=31536000`）。失败返回 JSON。

---

## 6. 预览接口

> 用于 `<img>` / `<video>` 等无法携带 `Authorization` 头的原生标签，通过短期 token 鉴权。

### 6.1 获取预览 token — `GET /api/files/:id/preview`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "token": "<preview_jwt>",
    "expires_in": 1800,
    "mime_type": "video/mp4",
    "name": "movie.mp4",
    "size": 1073741824,
    "is_chunks": true,
    "chunks_count": 100
  }
}
```

### 6.2 预览流 — `GET /api/files/:id/stream?token=<preview_token>`

**认证**：preview JWT（query 参数 `token`）。

**响应**：文件二进制流，支持 `Range`（大文件按需提取字节范围）。失败返回 JSON。

---

## 7. 分享接口

> 分享类型：单文件 / 多文件（`file_ids`）/ 单文件夹（`folder_id`）/ 多文件夹（`folder_ids`）。文件夹分享为动态实时同步（访问时实时查询 D1）。

### 7.1 创建分享 — `POST /api/shares`

**认证**：JWT。

**请求体**（四选一：`file_id` / `file_ids` / `folder_id` / `folder_ids`）：

```jsonc
{
  "file_ids": ["uuid-1", "uuid-2"],  // 多文件；或单文件 file_id / 文件夹 folder_id / folder_ids
  "password": "1234",                 // 可选，提取码
  "expire_hours": 24,                 // 可选，过期小时数
  "max_downloads": 10                 // 可选，最大下载次数
}
```

**成功响应** `201`：

```jsonc
{ "success": true, "data": { "token": "aB3xY9zKqW2p" } }
```

**错误响应**：`400`（缺少文件 ID）、`403`（无权分享该文件夹）、`404`（文件/文件夹不存在）。

### 7.2 我的分享列表 — `GET /api/shares`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": [
    {
      "id": 1,
      "file_id": "[\"uuid-1\",\"uuid-2\"]",
      "token": "aB3xY9zKqW2p",
      "has_password": true,
      "expire_at": "2025-07-30T00:00:00.000Z",
      "download_count": 3,
      "max_downloads": 10,
      "created_at": "...",
      "file_name": "2 个文件",
      "file_type": "file",
      "file_size": 2097152,
      "file_count": 2
    }
  ]
}
```

> 文件夹分享 `file_type` 为 `"folder"`，`file_name` 形如 `"文件夹: docs"` 或 `"文件夹 (2 个): a、b"`。

### 7.3 取消分享 — `DELETE /api/shares/:id`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "已取消分享" }
```

### 7.4 访问分享 — `GET /api/s/:token`

**认证**：无需（密码分享需 `?pw=`）。

**Query 参数**：

| 参数 | 说明 |
|------|------|
| `pw` | 分享提取码 |
| `folder_id` | 文件夹分享：浏览的子文件夹 ID |

**文件分享成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "files": [
      {
        "index": 0,
        "id": "uuid-1",
        "name": "report.pdf",
        "size": 1048576,
        "mime_type": "application/pdf",
        "is_chunks": false,
        "chunks_count": 0
      }
    ],
    "has_password": true,
    "download_count": 3,
    "max_downloads": 10,
    "is_multi": false
  }
}
```

**文件夹分享成功响应** `200`（单文件夹）：

```jsonc
{
  "success": true,
  "data": {
    "is_folder": true,
    "folder_name": "docs",
    "folder_id": "uuid-folder",
    "root_folder_id": "uuid-folder",
    "breadcrumbs": [
      { "id": "uuid-folder", "name": "docs" },
      { "id": "uuid-sub", "name": "sub" }
    ],
    "files": [
      {
        "index": 0,
        "id": "uuid-file",
        "name": "a.txt",
        "type": "file",
        "size": 10,
        "mime_type": "text/plain",
        "is_chunks": false,
        "chunks_count": 0
      }
    ],
    "has_password": false,
    "download_count": 0,
    "max_downloads": null
  }
}
```

**密码未提供时** `200`：

```jsonc
{ "success": true, "data": { "need_password": true } }
```

**错误响应**：`403`（提取码错误）、`404`（分享不存在）、`410`（已过期 / 达最大下载次数）。

### 7.5 下载分享文件 — `POST /api/s/:token/download`

**认证**：无需（密码分享在 body 传 `password`）。

**请求体**：

```jsonc
{
  "password": "1234",   // 可选
  "verify_only": false, // true 时仅验证密码，不计数不下载
  "file_index": 0,      // 可选，多文件分享指定文件
  "folder_id": "uuid"   // 可选，文件夹分享定位文件所在子文件夹
}
```

**响应**：
- `verify_only=true`：`200` `{ "success": true, "message": "验证通过" }`
- 正常下载：文件二进制流（小文件）或 404/410 JSON
- 错误：`403`（提取码错误）、`410`（达最大下载次数）

> 下载计数在 D1 中原子递增，`max_downloads=N` 即 N 次完整下载。

### 7.6 获取分享分块元数据 — `GET /api/s/:token/chunks`

**认证**：无需（密码用 `?pw=` 或 Header `X-Share-Password`）。

**Query 参数**：`pw`、`file_index`、`folder_id`。

> 本接口是大文件分块下载的入口，会原子递增下载计数。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "name": "movie.mp4",
    "size": 1073741824,
    "mime_type": "video/mp4",
    "chunks": [ { "index": 0, "size": 10485760 } ]
  }
}
```

### 7.7 下载分享单个分块 — `GET /api/s/:token/chunk/:index`

**认证**：无需（密码用 `?pw=` 或 Header `X-Share-Password`）。

**Query 参数**：`pw`、`file_index`、`folder_id`。

**响应**：`application/octet-stream` 二进制流（不递增计数）。

### 7.8 分享预览流 — `GET /api/s/:token/stream`

**认证**：无需（密码用 `?pw=` 或 Header `X-Share-Password`）。

**Query 参数**：`pw`、`file_index`、`folder_id`。

**响应**：文件二进制流，支持 `Range`，不计下载次数。

---

## 8. 用户接口

### 8.1 存储统计 — `GET /api/user/storage`

**认证**：JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": { "used": 123456789, "files": 42, "folders": 5 }
}
```

### 8.2 更新个人信息 — `PUT /api/user/profile`

**认证**：JWT。

**请求体**：

```jsonc
{ "email": "new@example.com", "avatar_url": "https://..." }  // 均可选
```

**成功响应** `200`：

```jsonc
{ "success": true, "message": "更新成功" }
```

**错误响应**：`400`（无更新字段）、`409`（邮箱已被使用）。

### 8.3 修改密码 — `PUT /api/user/password`

**认证**：JWT。

**请求体**：

```jsonc
{ "old_password": "old12345", "new_password": "new12345" }  // 新密码至少 8 字符
```

**成功响应** `200`：

```jsonc
{ "success": true, "message": "密码修改成功" }
```

**错误响应**：`400`（缺少字段 / 新密码过短）、`401`（旧密码错误）、`404`（用户不存在）。

---

## 9. 管理接口

> 管理 token 通过 `POST /api/admin/login` 获取（24 小时有效），其余管理接口使用 `Authorization: Bearer <admin_token>`。

### 9.1 管理员登录 — `POST /api/admin/login`

**认证**：无需（密码与 Worker Secret `ADMIN_PASSWORD` 比对）。

**请求体**：

```jsonc
{ "password": "<admin_password>" }
```

**成功响应** `200`：

```jsonc
{ "success": true, "data": { "token": "<admin_jwt>" } }
```

### 9.2 概览统计 — `GET /api/admin/stats`

**认证**：Admin JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "users": 10,
    "files": 120,
    "folders": 30,
    "shares": 15,
    "trash": 8,
    "storage_bytes": 9876543210
  }
}
```

### 9.3 用户列表 — `GET /api/admin/users`

**认证**：Admin JWT。

**Query 参数**：`page`（默认 1，每页 20）。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "users": [
      { "id": 1, "username": "alice", "email": "...", "storage_used": 123, "created_at": "..." }
    ],
    "total": 10,
    "page": 1,
    "limit": 20
  }
}
```

### 9.4 用户文件列表 — `GET /api/admin/users/:id/files`

**认证**：Admin JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": [
    { "id": "uuid", "name": "a.txt", "size": 10, "mime_type": "text/plain", "is_chunks": 0, "created_at": 1750000000000, "updated_at": 1750000000000 }
  ]
}
```

### 9.5 删除用户 — `DELETE /api/admin/users/:id`

**认证**：Admin JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "用户已删除" }
```

### 9.6 全部文件列表 — `GET /api/admin/files`

**认证**：Admin JWT。

**成功响应** `200`：

```jsonc
{
  "success": true,
  "data": {
    "files": [ { "id": "uuid", "name": "a.txt", "size": 10, "mime_type": "text/plain", "is_chunks": 0, "created_at": 1750000000000, "user_id": 1 } ],
    "total": 120
  }
}
```

### 9.7 删除文件 — `DELETE /api/admin/files/:id`

**认证**：Admin JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "文件已删除" }
```

### 9.8 管理员下载文件 — `GET /api/admin/files/:id/download`

**认证**：Admin JWT。**响应**：文件二进制流。

### 9.9 管理员分块元数据 — `GET /api/admin/files/:id/chunks`

**认证**：Admin JWT。**响应**：同 5.2 格式。

### 9.10 管理员下载分块 — `GET /api/admin/files/:id/chunk/:index`

**认证**：Admin JWT。**响应**：`application/octet-stream` 二进制流。

### 9.11 分享列表 — `GET /api/admin/shares`

**认证**：Admin JWT。**响应**：`{ "success": true, "data": [...] }`，含 `token`、`file_name`、`username`、`download_count` 等。

### 9.12 删除分享 — `DELETE /api/admin/shares/:id`

**认证**：Admin JWT。

**成功响应** `200`：

```jsonc
{ "success": true, "message": "分享已删除" }
```

### 9.13 数据迁移回填 — `GET /api/admin/migrate`

**认证**：Admin JWT。一次性将 KV 元数据回填到 D1（部署迁移后执行一次）。

**成功响应** `200`：

```jsonc
{ "success": true, "data": { "files": 100, "folders": 20 } }
```

---

## 附：WebSocket 下载

- **端点**：`WS /api/ws/download?token=<access_token>`（亦兼容 `/ws/download`）
- **认证**：JWT（query 参数 `token`）

---

## 附：错误响应示例

```jsonc
{ "success": false, "error": "用户名或密码错误" }
```

```jsonc
{ "success": false, "error": "分享已过期" }
```

```jsonc
{ "success": false, "error": "需要提取码", "need_password": true }
```
