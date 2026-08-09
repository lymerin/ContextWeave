# Collaboration Gateway MVP 联调验证

这份清单用于验证：

```text
文档工作台 -> WebSocket Gateway -> Redis Presence / Editing State -> 前端协作面板
```

## 1. 启动基础设施

```powershell
docker compose up -d postgres redis
docker compose ps
```

## 2. 启动 Knowledge Core

```powershell
$env:DEVCOLLAB_DB_URL="jdbc:postgresql://localhost:5432/devcollab"
$env:DEVCOLLAB_DB_USERNAME="devcollab"
$env:DEVCOLLAB_DB_PASSWORD="devcollab"
$env:DEVCOLLAB_REDIS_HOST="localhost"
$env:DEVCOLLAB_REDIS_PORT="6379"

.\mvnw.cmd -pl knowledge-core spring-boot:run
```

## 3. 启动 Collaboration Gateway

```powershell
$env:DEVCOLLAB_GATEWAY_PORT="8090"
$env:DEVCOLLAB_CORE_BASE_URL="http://localhost:8080"
$env:DEVCOLLAB_REDIS_HOST="localhost"
$env:DEVCOLLAB_REDIS_PORT="6379"
$env:DEVCOLLAB_JWT_ISSUER="devcollab-knowledge-core"
$env:DEVCOLLAB_JWT_AUDIENCE="devcollab-web"
$env:DEVCOLLAB_JWT_SECRET="devcollab-local-development-secret-change-me"

.\mvnw.cmd -pl collaboration-gateway spring-boot:run
```

## 4. 启动前端

```powershell
cd web
npm run dev
```

Vite 已配置 `/ws -> ws://localhost:8090` 代理，所以浏览器仍访问：

```text
http://localhost:5173
```

## 5. 手动验收路径

1. 登录；
2. 进入任意文档工作台；
3. 打开右侧“协作”页签；
4. 确认状态显示“已连接”；
5. 复制同一个文档地址到另一个浏览器窗口或另一个账号；
6. 两边都能看到在线成员；
7. 在任意 Block 输入框聚焦；
8. 另一边能看到“某用户正在编辑 Block xxxx”；
9. 输入框失焦或关闭页面后，编辑状态消失。

也可以直接执行自动验收脚本：

```powershell
node tools/e2e-gateway-check.mjs
```

脚本会自动创建一次性用户、工作区、文档和 Block，并模拟两个 WebSocket 客户端验证在线成员与编辑状态广播。

## 6. Redis 验证

```powershell
docker exec -it devcollab-redis redis-cli
KEYS gateway:document:*:presence
KEYS gateway:document:*:editing
```

预期：

- 有人在线时，presence key 存在；
- 有人编辑时，editing key 存在；
- 离开页面后 key 内对应 session 被清理；
- 即使异常断线，TTL 也会兜底过期。

## 7. 当前 MVP 不做什么

- 不做 CRDT / OT 自动合并；
- 不通过 Kafka 做实时协作；
- 不保存编辑状态到 PostgreSQL；
- 不保证同一 Block 多人编辑自动冲突合并；
- 不做跨地域 Gateway 路由。

当前阶段只验证实时连接、在线状态、编辑状态广播这条最小闭环。
