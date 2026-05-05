# Telegram Web3 红包服务端 + 独立管理前端

本包把服务端和后台前端拆开：

- `server.js`：Node/Express 服务端，只包含客户端业务接口、后台 API、可选静态文件托管，不再内联后台 HTML。
- `admin-web/`：独立后台前端，使用 React + Ant Design + ProComponents / ProLayout 的 Ant Design Pro 生态。

## 本地启动服务端

```bash
cp .env.example .env
npm install
npm run dev
```

服务端默认监听：

```text
http://127.0.0.1:8787
```

客户端接口仍然是：

```text
/api/v1/*
/healthz
```

后台 API 默认是：

```text
/api/admin/*
```

## 本地启动后台前端

```bash
cd admin-web
npm install
npm run dev
```

后台开发地址默认：

```text
http://127.0.0.1:5173/admin/
```

Vite 已配置 `/api/admin` 代理到 `http://127.0.0.1:8787`。

## 生产构建

```bash
npm --prefix admin-web install
npm run admin:build
npm install --omit=dev
npm start
```

构建后 `admin-web/dist` 会由 `server.js` 在 `/admin` 路径托管。也可以直接用 Nginx 托管 `admin-web/dist`，后台 API 指向 `/api/admin`。

## 必配环境变量

生产环境至少配置：

```env
ADMIN_USERNAME=admin
ADMIN_PASSWORD=你的强密码
ADMIN_SESSION_SECRET=一串足够长的随机字符串
```

如果没有配置 `ADMIN_PASSWORD` 或 `ADMIN_TOKEN`，后台 API 会返回未启用，不会裸奔开放。
