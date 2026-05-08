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

后台“客户端版本”页面支持：

- 历史版本分页列表、搜索、启用 / 停用；
- 发布新版本时上传 APK、版本号 `versionCode`、版本名称 `versionName`、更新内容、发布日期、是否强制更新；
- 更新已有版本时可重新上传 APK，也可以只修改版本名称、更新内容、强制更新和启用状态；
- `/api/v1/client/version/check` 会优先读取数据库中已启用且 `version_code` 最大的版本；如果没有发布过版本，则使用后台“运行参数配置”里的兜底版本信息；
- APK 保存目录、公开下载路径、下载 URL Base、最大上传大小都可以在后台“系统状态 -> 运行参数配置”中管理。

## 运行参数后台管理

服务启动时会自动创建 `system_settings` 表，并把 `.env` 里的运行参数作为初始值写入数据库；之后这些参数可以在后台 `系统状态 -> 运行参数配置` 中直接修改，保存后立即生效。

当前已支持后台管理的参数包括：

- `PUBLIC_HOST`、`MAX_EXPIRES_IN_SECONDS`；
- BSC RPC URL 列表和红包合约地址：`RPC_URLS` / `RPC_URL`、`RED_PACKET_CONTRACT` 首次种子配置，后台保存后立即生效；
- 客户端代理：`DEFAULT_PROXY_ADDRESS`、`DEFAULT_PROXY_PORT`、`DEFAULT_PROXY_USERNAME`、`DEFAULT_PROXY_PASSWORD`、`DEFAULT_PROXY_SECRET`；
- 客户端版本兜底信息：`APP_VERSION_CODE`、`APP_VERSION_NAME`、`APP_DOWNLOAD_URL`、`APP_VERSION_MESSAGE`、`APP_RELEASE_DATE`、`APP_APK_SIZE_BYTES`；
- APK 上传与下载：`APP_UPLOAD_PUBLIC_PATH`、`APP_UPLOAD_DIR`、`APP_UPLOAD_URL_BASE`、`MAX_APK_UPLOAD_BYTES`；
- `/api/v1/wallet/default-tokens` 返回的默认钱包代币列表。

`.env` 中这些值现在只作为首次启动的种子配置。数据库里已经存在对应 key 时，重启服务不会覆盖后台保存的值。

数据库、端口、后台登录凭据和链 ID 仍属于启动级配置，继续通过部署环境变量管理。RPC URL 和红包合约地址现在已经进入后台运行参数管理。

新增客户端链配置接口：

```text
GET /api/v1/wallet/chain-config
```

返回 `chainId`、`rpcUrls`、`bestRpcUrl`、`redPacketContract` / `contractAddress`。服务端会并发检测后台配置的所有 RPC，优先使用链 ID 正确、区块最新、延迟最低的节点；客户端拿到列表后也会本地测速再选择实际使用的 RPC。

`MAX_EXPIRES_IN_SECONDS` 与当前合约 `MAX_EXPIRES_IN = 30 days` 保持一致，服务端会按合约上限校验，避免生成链上无法创建的红包。


## 红包合约授权签名校验

新版 `TelegramRedPacketV2` 修复了两个与服务端、客户端流程不一致的合约入口问题：

- 不再允许绕过服务端直接调用 `claim(bytes32)` 领取红包；领取必须使用 `claim(bytes32 packetId, bytes signature)`。
- 不再允许任意钱包直接创建指定 `packetId` 的红包；创建必须使用带签名的 `createNativePacket(..., bytes signature)` 或 `createTokenPacket(..., bytes signature)`，避免恶意用户抢先占用服务端生成的 `packetId`。

签名由服务端生成：

- `/api/v1/red-packets/prepare-create` 返回 `createSignatureHex`。
- `/api/v1/red-packets/:packetId/claim/prepare` 返回 `signatureHex`。

签名内容绑定 `chainId`、合约地址、`packetId`、钱包地址、token、金额、数量和过期时间等关键字段，不能跨链、跨合约、给其他钱包复用，也不能篡改金额或 token。

服务端需要配置独立授权签名私钥：

```env
RED_PACKET_AUTH_SIGNER_PRIVATE_KEY=0x...
```

部署新合约后，需要把合约里的 `claimSigner` 设置为该私钥对应的钱包地址：

```solidity
setClaimSigner(0x你的服务端授权签名钱包地址)
```

建议生产环境使用专门生成的签名钱包，不要复用部署者钱包或业务收款钱包。该私钥只保存在服务端环境变量中，不放入后台运行参数页面。

## 必配环境变量

生产环境至少配置：

```env
ADMIN_USERNAME=admin
ADMIN_PASSWORD=你的强密码
ADMIN_SESSION_SECRET=一串足够长的随机字符串
```

如果没有配置 `ADMIN_PASSWORD` 或 `ADMIN_TOKEN`，后台 API 会返回未启用，不会裸奔开放。
