# Web3 钱包代币行情价格最终方案

## 目标

客户端钱包列表右侧第二行显示代币“行情价格”（单价），例如：

- 行情 $650.23
- 行情 $0.0123
- 行情 --

不是只显示余额估值 `≈ $0.00`。用户自己添加自定义 BEP-20 代币后，也会自动尝试获取行情价格。

## 服务端价格解析优先级

1. `walletTokens[].priceUsd`：默认代币后台固定价格。
2. `tokenIconRegistry[].priceUsd`：图标库里顺带配置的价格。
3. `tokenPriceRegistry`：新增的自定义代币价格库，适合项目币、小众币、测试网币。
4. BNB 原生币：Binance BNBUSDT。
5. 外部公开行情源：DEX Screener、DefiLlama、CoinGecko，按 `tokenPriceProviderOrder` 顺序查询。
6. 仍然找不到：客户端显示 `行情 --`，不再把未知价格误显示成 `$0.00`。

## 新增/增强接口

### 批量行情价格

```bash
GET /api/v1/wallet/token-prices?contractAddresses=0x...,0x...
```

返回 `prices[]`，包含默认代币、BNB，以及请求的自定义合约地址。

### 单个或批量元数据

```bash
GET /api/v1/wallet/token-metadata?contractAddress=0x...&symbol=ABC
GET /api/v1/wallet/token-metadata?contractAddresses=0x...,0x...
```

返回图标和价格。客户端添加自定义代币时会调用这个接口。

### 后台测试接口

```bash
GET /api/admin/wallet/token-price?contractAddress=0x...&force=1
```

后台“代币行情价格工具”使用这个接口测试价格解析。

## 新增后台配置项

```json
{
  "tokenPriceAutoEnabled": 1,
  "tokenPriceExternalTtlSeconds": 300,
  "tokenPriceProviderOrder": ["dexscreener", "defillama", "coingecko"],
  "tokenPriceRegistry": {
    "0x1111111111111111111111111111111111111111": "0.0123",
    "0x2222222222222222222222222222222222222222": {
      "symbol": "XYZ",
      "priceUsd": "1.25"
    }
  }
}
```

## 部署

```bash
# 1. 替换服务端
cp server.js /your/server/server.js

# 2. 替换 Android 钱包源码
cp -r wallet /your/android/project/path/

# 3. 替换管理后台源码
cp -r admin-web/src /your/admin-web/project/src

# 4. 编译管理后台
npm install
npm run build

# 5. 重启服务端
pm2 restart your-service
```

## 测试

```bash
curl "https://你的域名/api/v1/wallet/token-metadata?contractAddress=0x你的合约地址&force=1"

curl "https://你的域名/api/v1/wallet/token-prices?contractAddresses=0x你的合约地址&force=1"
```

如果代币没有主网流动性、没有被公开行情源收录，或者是测试网代币，需要在后台 `tokenPriceRegistry` 按合约地址配置固定 USD 价格。
