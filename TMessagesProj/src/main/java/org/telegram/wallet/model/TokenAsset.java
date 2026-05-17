package org.telegram.wallet.model;

public class TokenAsset {
    public String symbol;
    public String contractAddress;
    public int decimals = 18;
    public boolean favorite;
    /** USD 单价，来自服务端默认代币配置或价格接口；空字符串表示未知。 */
    public String priceUsd = "";
    /** 代币图标地址。支持 http/https 绝对地址；服务端也会把 /uploads/... 转成绝对地址。 */
    public String iconUrl = "";
}
