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


## 客户端版本管理

后台新增“客户端版本”页面，支持：

- 历史版本分页列表、搜索、启用 / 停用；
- 发布新版本时上传 APK、版本号 `versionCode`、版本名称 `versionName`、更新内容、发布日期、是否强制更新；
- 更新已有版本时可重新上传 APK，也可以只修改版本名称、更新内容、强制更新和启用状态；
- `/api/v1/client/version/check` 会优先读取数据库中已启用且 `version_code` 最大的版本；如果没有发布过版本，则继续使用 `.env` 中的 `APP_VERSION_*` 兜底配置；
- APK 默认保存到 `./uploads/apks`，并通过 `/uploads/apks/<filename>` 公开下载。生产环境可以通过 `APP_UPLOAD_URL_BASE` 指向 CDN 或 Nginx 静态资源域名。

相关环境变量：

```env
APP_UPLOAD_PUBLIC_PATH=/uploads/apks
APP_UPLOAD_DIR=./uploads/apks
APP_UPLOAD_URL_BASE=
MAX_APK_UPLOAD_BYTES=157286400
MAX_EXPIRES_IN_SECONDS=2592000
```

`MAX_EXPIRES_IN_SECONDS` 与当前合约 `MAX_EXPIRES_IN = 30 days` 保持一致，服务端会按合约上限截断，避免后台生成链上无法创建的红包。

## 必配环境变量

生产环境至少配置：

```env
ADMIN_USERNAME=admin
ADMIN_PASSWORD=你的强密码
ADMIN_SESSION_SECRET=一串足够长的随机字符串
```

如果没有配置 `ADMIN_PASSWORD` 或 `ADMIN_TOKEN`，后台 API 会返回未启用，不会裸奔开放。
