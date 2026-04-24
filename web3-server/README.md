# Web3 Red-Packet Backend + Contract (BNB Chain)

这个目录提供 Telegram Web3 红包功能的 **本地可运行** 实现（API + 合约）。

## 目录结构

- `contracts/TelegramRedPacket.sol`：链上红包合约（创建 / 领取 / 退款 + 状态查询）。
- `src/server.js`：本地 API 服务（Node.js + Express）。
- `package.json`：本地运行依赖和脚本。

## 1) 本地启动 API

```bash
cd web3-server
npm install
npm run dev
```

`npm run dev` 使用 Node.js watch 模式启动（`node --watch src/server.js`），修改 `src/server.js` 后会自动重启，便于开发联调及时生效。

默认监听 `http://127.0.0.1:8787`。

可选环境变量：

- `PORT`（默认 `8787`）
- `CHAIN_ID`（默认 `56`）
- `RED_PACKET_CONTRACT`（默认 `0xYourContractAddress`）
- `PUBLIC_HOST`（默认 `http://127.0.0.1:8787`）
- `RPC_URL`（默认 `https://data-seed-prebsc-1-s1.bnbchain.org:8545`）
- `MYSQL_HOST`（默认 `127.0.0.1`）
- `MYSQL_PORT`（默认 `3306`）
- `MYSQL_USER`（默认 `root`）
- `MYSQL_PASSWORD`（默认空）
- `MYSQL_DATABASE`（默认 `telegram_red_packet`）


### 管理后台

启动后访问：`http://127.0.0.1:8787/admin`

提供：
- 红包总览统计（总数、进行中、待确认、已领完、领取总次数）
- 最近红包列表和快速跳转详情 API

### 健康检查

```bash
curl http://127.0.0.1:8787/healthz
```

## 2) API 流程（本地联调）

### 预创建红包（prepare-create）

```bash
curl -X POST http://127.0.0.1:8787/api/v1/red-packets/prepare-create \
  -H 'Content-Type: application/json' \
  -d '{
    "dialogId": "123456",
    "creatorWallet": "0x1111111111111111111111111111111111111111",
    "totalAmountWei": "100000000000000000",
    "count": 5,
    "expiresAt": 1893456000,
    "tokenSymbol": "BNB",
    "tokenDecimals": 18,
    "greeting": "恭喜发财",
    "packetType": "normal"
  }'
```

> 说明：发送 **BNB 原生红包** 时不需要传 `tokenAddress`；发送 BEP-20 代币红包时再传对应合约地址。

### 创建确认（create-confirm，必须校验链上 PacketCreated）

```bash
curl -X POST http://127.0.0.1:8787/api/v1/red-packets/<packetId>/create-confirm \
  -H 'Content-Type: application/json' \
  -d '{"txHash":"0x..."}'
```

### 查询红包状态

```bash
curl "http://127.0.0.1:8787/api/v1/red-packets/<packetId>?wallet=0x2222222222222222222222222222222222222222"
```

### 领取预处理

```bash
curl -X POST http://127.0.0.1:8787/api/v1/red-packets/<packetId>/claim/prepare \
  -H 'Content-Type: application/json' \
  -d '{"claimerAddress":"0x2222222222222222222222222222222222222222"}'
```

### 领取确认（claim-confirm，必须校验链上 Claimed）

```bash
curl -X POST http://127.0.0.1:8787/api/v1/red-packets/<packetId>/claim-confirm \
  -H 'Content-Type: application/json' \
  -d '{
    "claimerAddress": "0x2222222222222222222222222222222222222222",
    "txHash": "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }'
```

## 3) 合约说明

`TelegramRedPacket.sol` 主要增强点：

- 限制最大份数（`MAX_PACKET_COUNT = 500`）。
- 限制最远过期时间（`MAX_EXPIRES_IN = 30 days`）。
- `claim/refund` 使用 `call` 转账，避免 `transfer` 的 gas stipend 风险。
- 增加 `getPacket`、`hasClaimed` 视图方法，便于客户端/服务端同步状态。

## 4) Android 接入建议

- 将 `BuildConfig.WEB3_RED_PACKET_HOST` 指向本地 API（如 `10.0.2.2:8787` / 局域网 IP）。
- 将 `BuildConfig.RED_PACKET_CONTRACT` 配置为部署后的合约地址。
- 生产环境建议开启管理后台鉴权（如 Nginx BasicAuth / JWT），并用真实签名替换 mock 的 `signatureHex`。

## 5) BNB Chain 文档列出的 BSC Testnet 公共 RPC

  https://data-seed-prebsc-1-s1.bnbchain.org:8545
  https://data-seed-prebsc-2-s1.bnbchain.org:8545
  https://data-seed-prebsc-1-s2.bnbchain.org:8545
  https://data-seed-prebsc-2-s2.bnbchain.org:8545
  https://data-seed-prebsc-1-s3.bnbchain.org:8545
  https://data-seed-prebsc-2-s3.bnbchain.org:8545

## 10) 验收标准

### 红包功能验收

| 编号 | 验收项 | 通过标准 |
| --- | --- | --- |
| RP-01 | 发送红包 | 聊天中显示红包卡片，不是普通文字 |
| RP-02 | BNB 红包 | 可以创建、发送、领取、确认 |
| RP-03 | 自定义代币红包 | 选择钱包已添加 BEP-20，完成 approve/create/claim |
| RP-04 | 抢红包 | 接收方点击卡片，在 Telegram 内部完成领取 |
| RP-05 | 重复领取 | 同一钱包第二次领取提示“已领取” |
| RP-06 | 抢完 | 剩余数量为 0 后卡片显示“已抢完” |
| RP-07 | 过期 | 超时后不能领取 |
| RP-08 | 退款 | 创建者可退回过期剩余金额 |
| RP-09 | 交易校验 | 服务端必须校验链上事件，不接受伪 txHash |
| RP-10 | 兼容性 | 老版本客户端看到可理解的文本，新版本显示卡片 |

### 钱包功能验收

| 编号 | 验收项 | 通过标准 |
| --- | --- | --- |
| W-01 | 返回按钮 | Web3 Wallet 页面顶部有返回 |
| W-02 | 首页商品化 | 不显示 API/RPC/Contract 调试信息 |
| W-03 | 资产卡 | 显示地址、总资产、链、复制 |
| W-04 | Token 列表 | BNB + 自定义代币都显示余额 |
| W-05 | 添加代币 | 输入合约地址可自动读取 symbol/decimals |
| W-06 | 转账 | 支持 BNB 和 BEP-20 转账 |
| W-07 | 安全中心 | 私钥/助记词查看必须二次验证 |
| W-08 | 主题 | 深色/浅色都适配 Telegram 风格 |
