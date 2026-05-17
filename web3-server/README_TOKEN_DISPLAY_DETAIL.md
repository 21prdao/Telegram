# Web3 钱包代币列表、详情页与行情刷新更新

本包在上一版“代币图标 + 行情价格 + 代币详情页”基础上继续优化客户端展示和刷新策略：

1. 钱包首页代币列表左侧副标题显示单价，例如 `$654.85`、`$0.0446`、`$0.005389`，不再显示代币地址。
2. 钱包首页右侧持有量不追加代币符号，只显示数量；右下角显示该持仓换算后的美元估值，例如 `$0.00`、`$12.35`、`$<0.01`。
3. 不再显示 `$--` 这种占位。未知单价显示 `--`，未知的非零持仓估值显示 `--`，零余额估值显示 `$0.00`。
4. 极小单价使用类似 TokenPocket 的紧凑格式，避免小数点后连续很多 0：例如 `0.00008009` 显示为 `$0.0₄8009`，`0.00000123` 显示为 `$0.0₅123`。
5. 新增 `WalletUiRefreshPolicy`，客户端在钱包首页、代币列表页、代币详情页可见时，每 60 秒自动刷新一次余额、行情价格、持仓估值和图标元数据。
6. 点击代币项进入 `TokenDetailActivity`，显示代币图标、符号、合约地址、复制合约地址、单价、持有量、持仓估值、小数位、网络等信息；详情页也会每 60 秒刷新。

## AndroidManifest 注意事项

如果你的主工程 AndroidManifest.xml 里没有自动声明 wallet/ui 下的新 Activity，请手动加入：

```xml
<activity
    android:name="org.telegram.wallet.ui.TokenDetailActivity"
    android:screenOrientation="portrait"
    android:exported="false" />
```

已有源码包里没有上传 AndroidManifest，所以这里无法直接替你改 manifest；Java 代码已经完整加入。
