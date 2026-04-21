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

默认监听 `http://127.0.0.1:8787`。

可选环境变量：

- `PORT`（默认 `8787`）
- `CHAIN_ID`（默认 `56`）
- `RED_PACKET_CONTRACT`（默认 `0xYourContractAddress`）
- `PUBLIC_HOST`（默认 `http://127.0.0.1:8787`）

### 健康检查

```bash
curl http://127.0.0.1:8787/healthz
```

## 2) API 流程（本地联调）

### 创建红包

```bash
curl -X POST http://127.0.0.1:8787/api/v1/red-packets/create \
  -H 'Content-Type: application/json' \
  -d '{
    "dialogId": "123456",
    "creatorWallet": "0x1111111111111111111111111111111111111111",
    "totalAmountWei": "100000000000000000",
    "count": 5,
    "expiresAt": 1893456000
  }'
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

### 领取确认（链上交易成功后）

```bash
curl -X POST http://127.0.0.1:8787/api/v1/red-packets/<packetId>/confirm \
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
- 生产环境请把内存存储替换成 DB，并用真实签名替换 mock 的 `signatureHex`。
