import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Form,
  Image,
  Input,
  InputNumber,
  Row,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from "antd";
import { UploadOutlined } from "@ant-design/icons";
import dayjs, { type Dayjs } from "dayjs";
import { adminRequest, buildQuery } from "../api";
import { formatTime } from "../utils";

type RpcStatusRow = {
  name?: string;
  url: string;
  enabled?: boolean;
  ok: boolean;
  latencyMs: number | null;
  blockNumber: number | null;
  chainId: number | null;
  error?: string;
  checkedAt?: number;
};

type SystemInfo = {
  now: number;
  serverStartedAt: number;
  health: {
    rpcOk: boolean;
    blockNumber: number | null;
    dbOk: boolean;
  };
  database: {
    version: string;
    tables: Array<{ tableName: string; estimatedRows: number }>;
  };
  rpcStatus?: RpcStatusRow[];
  config: Record<string, any>;
};

type WalletToken = {
  symbol: string;
  contractAddress: string;
  decimals: number;
  priceUsd?: string;
  iconUrl?: string;
};

type TokenIconRegistryItem = {
  symbol?: string;
  contractAddress: string;
  decimals?: number;
  priceUsd?: string;
  iconUrl: string;
};

type TokenPriceRegistryItem = {
  symbol?: string;
  contractAddress?: string;
  tokenAddress?: string;
  priceUsd: string;
};

type RpcEndpoint = {
  name?: string;
  url: string;
  enabled?: boolean;
};

type RuntimeSettingsValues = {
  publicHost: string;
  rpcUrls: RpcEndpoint[];
  redPacketContract: string;
  maxExpiresInSeconds: number;
  appUploadPublicPath: string;
  appUploadDir: string;
  appUploadUrlBase: string;
  maxApkUploadMB: number;
  fallbackVersionCode: number;
  fallbackVersionName: string;
  fallbackDownloadUrl: string;
  fallbackVersionMessage: string;
  fallbackReleaseDate: number;
  fallbackApkSizeBytes: number;
  proxyAddress: string;
  proxyPort: number;
  proxyUsername: string;
  proxyPassword: string;
  proxySecret: string;
  bnbIconUrl: string;
  tokenIconPublicPath: string;
  tokenIconDir: string;
  tokenIconRegistry: TokenIconRegistryItem[];
  tokenPriceAutoEnabled: number;
  tokenPriceExternalTtlSeconds: number;
  tokenPriceProviderOrder: string[];
  tokenPriceRegistry: TokenPriceRegistryItem[];
  walletTokens: WalletToken[];
};

type RuntimeSettingsPayload = {
  values: RuntimeSettingsValues;
  definitions: Array<{
    key: string;
    group: string;
    label: string;
    type: string;
    required?: boolean;
    min?: number;
    max?: number;
    maxLength?: number;
    description?: string;
  }>;
  updatedAt: number;
};

type RuntimeSettingsFormValues = Omit<
  RuntimeSettingsValues,
  | "fallbackReleaseDate"
  | "walletTokens"
  | "rpcUrls"
  | "tokenIconRegistry"
  | "tokenPriceProviderOrder"
  | "tokenPriceRegistry"
> & {
  fallbackReleaseDate?: Dayjs | null;
  walletTokensText?: string;
  rpcUrlsText?: string;
  tokenIconRegistryText?: string;
  tokenPriceProviderOrderText?: string;
  tokenPriceRegistryText?: string;
};

type TokenIconUploadFormValues = {
  symbol?: string;
  contractAddress?: string;
  decimals?: number;
  priceUsd?: string;
  filename?: string;
  saveToRegistry?: boolean;
};

type TokenIconUploadResponse = {
  filename: string;
  iconUrl: string;
  url?: string;
  originalName?: string;
  sizeBytes?: number;
  sha256?: string;
  savedToRegistry?: boolean;
  tokenIconRegistryCount?: number;
};

type TokenMetadataResponse = {
  symbol?: string;
  contractAddress?: string;
  tokenAddress?: string;
  decimals?: number;
  priceUsd?: string;
  priceSource?: string;
  liquidityUsd?: number;
  priceConfidence?: number;
  pairAddress?: string;
  dexId?: string;
  iconUrl?: string;
  source?: string;
  baseCurrency?: string;
  updatedAt?: number;
};

function BoolTag({ value }: { value: boolean }) {
  return <Tag color={value ? "green" : "red"}>{value ? "OK" : "异常"}</Tag>;
}

function parseWalletTokens(text?: string): WalletToken[] {
  const raw = String(text || "").trim();
  if (!raw) return [];
  const parsed = JSON.parse(raw) as WalletToken[];
  if (!Array.isArray(parsed)) {
    throw new Error("默认钱包代币列表必须是 JSON 数组");
  }
  return parsed;
}

function parseTokenIconRegistry(
  text?: string,
):
  | TokenIconRegistryItem[]
  | Record<string, string | Partial<TokenIconRegistryItem>> {
  const raw = String(text || "").trim();
  if (!raw) return [];
  const parsed = JSON.parse(raw) as
    | TokenIconRegistryItem[]
    | Record<string, string | Partial<TokenIconRegistryItem>>;
  if (!Array.isArray(parsed) && (!parsed || typeof parsed !== "object")) {
    throw new Error("自定义代币图标库必须是 JSON 数组或对象");
  }
  return parsed;
}

function parseTokenPriceRegistry(
  text?: string,
):
  | TokenPriceRegistryItem[]
  | Record<string, string | Partial<TokenPriceRegistryItem>> {
  const raw = String(text || "").trim();
  if (!raw) return [];
  const parsed = JSON.parse(raw) as
    | TokenPriceRegistryItem[]
    | Record<string, string | Partial<TokenPriceRegistryItem>>;
  if (!Array.isArray(parsed) && (!parsed || typeof parsed !== "object")) {
    throw new Error("自定义代币价格库必须是 JSON 数组或对象");
  }
  return parsed;
}

function parseTokenPriceProviderOrder(text?: string): string[] {
  const raw = String(text || "").trim();
  if (!raw) return [];
  const parsed = JSON.parse(raw) as string[];
  if (!Array.isArray(parsed)) {
    throw new Error("行情价格来源顺序必须是 JSON 数组");
  }
  return parsed.map((item) => String(item || "").trim()).filter(Boolean);
}

function stringifyRpcUrls(rpcUrls?: RpcEndpoint[]): string {
  if (!Array.isArray(rpcUrls)) return "";
  const hasMeta = rpcUrls.some((item, index) => {
    if (!item) return false;
    const defaultName = `RPC ${index + 1}`;
    return (
      item.enabled === false || Boolean(item.name && item.name !== defaultName)
    );
  });
  if (hasMeta) return JSON.stringify(rpcUrls, null, 2);
  return rpcUrls
    .filter((item) => item?.url)
    .map((item) => item.url)
    .join("\n");
}

function parseRpcUrlsText(text?: string): string[] | RpcEndpoint[] {
  const raw = String(text || "").trim();
  if (!raw) return [];
  if (raw.startsWith("[")) {
    const parsed = JSON.parse(raw) as RpcEndpoint[];
    if (!Array.isArray(parsed)) throw new Error("RPC URL 列表必须是数组");
    return parsed;
  }
  return raw
    .split(/[\n,]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function stringifyConfigValue(value: unknown): string {
  if (value === undefined || value === null) return "";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function TokenIconTools({ onRegistrySaved }: { onRegistrySaved: () => void }) {
  const [uploadForm] = Form.useForm<TokenIconUploadFormValues>();
  const [metadataForm] = Form.useForm<{
    contractAddress?: string;
    symbol?: string;
  }>();
  const [iconFile, setIconFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [lastUploaded, setLastUploaded] =
    useState<TokenIconUploadResponse | null>(null);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [metadata, setMetadata] = useState<TokenMetadataResponse | null>(null);

  const uploadIcon = async () => {
    let values: TokenIconUploadFormValues;
    try {
      values = await uploadForm.validateFields();
    } catch {
      return;
    }
    if (!iconFile) {
      message.error("请先选择 PNG/JPG/WebP/GIF 图标文件");
      return;
    }

    const formData = new FormData();
    formData.append("iconFile", iconFile);
    Object.entries(values).forEach(([key, value]) => {
      if (value === undefined || value === null || value === "") return;
      if (typeof value === "boolean") {
        formData.append(key, value ? "1" : "0");
      } else {
        formData.append(key, String(value));
      }
    });

    setUploading(true);
    try {
      const data = await adminRequest<TokenIconUploadResponse>(
        "/token-icons/upload",
        {
          method: "POST",
          body: formData,
        },
      );
      setLastUploaded(data);
      message.success(
        data.savedToRegistry
          ? "图标已上传，并已写入自定义代币图标库"
          : "图标已上传",
      );
      if (data.savedToRegistry) onRegistrySaved();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "上传失败");
    } finally {
      setUploading(false);
    }
  };

  const testMetadata = async () => {
    const values = await metadataForm.validateFields();
    const query = buildQuery({
      contractAddress: values.contractAddress,
      symbol: values.symbol,
    });
    if (!query) {
      message.error("请输入合约地址或 symbol");
      return;
    }

    setMetadataLoading(true);
    try {
      const data = await adminRequest<TokenMetadataResponse>(
        `/wallet/token-metadata${query}`,
      );
      setMetadata(data);
      if (data.iconUrl || Number(data.priceUsd || 0) > 0) {
        message.success("已匹配到代币资料");
      } else {
        message.warning("暂未匹配到图标或行情价格，客户端会显示默认信息");
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : "查询失败");
    } finally {
      setMetadataLoading(false);
    }
  };

  const uploadRules = useMemo(
    () => ({
      contractRequiredWhenSave: ({
        getFieldValue,
      }: {
        getFieldValue: (name: string) => unknown;
      }) => ({
        validator(_: unknown, value: string) {
          if (getFieldValue("saveToRegistry") && !String(value || "").trim()) {
            return Promise.reject(
              new Error("勾选自动写入图标库时必须填写合约地址"),
            );
          }
          return Promise.resolve();
        },
      }),
    }),
    [],
  );

  return (
    <Card title="代币图标工具" className="token-icon-tools-card">
      <Alert
        type="info"
        showIcon
        message="后台图标管理逻辑"
        description="用户添加自定义代币时，客户端会调用服务端 token-metadata 接口。服务端会同时返回 iconUrl 和 priceUsd：图标来自 tokenIconRegistry/公开图库；行情价格来自 tokenPriceRegistry/DefiLlama/DEX Screener。这里配置后，用户下次添加或刷新该合约地址就能看到图标和价格。"
        style={{ marginBottom: 16 }}
      />

      <Typography.Title level={5}>
        上传图标并写入自定义代币图标库
      </Typography.Title>
      <Form<TokenIconUploadFormValues>
        form={uploadForm}
        layout="vertical"
        initialValues={{ decimals: 18, priceUsd: "0", saveToRegistry: true }}
      >
        <Row gutter={16}>
          <Col xs={24} md={12} lg={8}>
            <Form.Item
              name="contractAddress"
              label="代币合约地址"
              rules={[uploadRules.contractRequiredWhenSave]}
              tooltip="强烈建议填写。服务端会按合约地址精确匹配图标，避免同名代币误配。"
            >
              <Input placeholder="0x..." />
            </Form.Item>
          </Col>
          <Col xs={24} md={12} lg={6}>
            <Form.Item name="symbol" label="Symbol">
              <Input placeholder="例如 ETZ" maxLength={32} />
            </Form.Item>
          </Col>
          <Col xs={12} md={8} lg={4}>
            <Form.Item name="decimals" label="Decimals">
              <InputNumber
                min={0}
                max={36}
                precision={0}
                style={{ width: "100%" }}
              />
            </Form.Item>
          </Col>
          <Col xs={12} md={8} lg={6}>
            <Form.Item name="priceUsd" label="价格 USD">
              <Input placeholder="0" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12} lg={8}>
            <Form.Item
              name="filename"
              label="保存文件名（可选）"
              tooltip="不填则服务端自动生成；可以填 etz.png，也可以填合约地址.png。"
            >
              <Input placeholder="etz.png" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12} lg={8}>
            <Form.Item label="图标文件">
              <Upload
                accept="image/png,image/jpeg,image/webp,image/gif"
                maxCount={1}
                beforeUpload={(file) => {
                  setIconFile(file as File);
                  return false;
                }}
                onRemove={() => {
                  setIconFile(null);
                  return true;
                }}
              >
                <Button icon={<UploadOutlined />}>选择 PNG/JPG/WebP/GIF</Button>
              </Upload>
            </Form.Item>
          </Col>
          <Col xs={24} md={12} lg={8}>
            <Form.Item name="saveToRegistry" valuePropName="checked" label=" ">
              <Checkbox>上传后自动写入 tokenIconRegistry</Checkbox>
            </Form.Item>
          </Col>
        </Row>
        <Button type="primary" loading={uploading} onClick={uploadIcon}>
          上传图标
        </Button>
      </Form>

      {lastUploaded ? (
        <Descriptions
          bordered
          size="small"
          column={1}
          className="token-icon-result"
        >
          <Descriptions.Item label="文件名">
            <Typography.Text copyable>{lastUploaded.filename}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="图标 URL">
            <Space direction="vertical" size={8} style={{ width: "100%" }}>
              <Typography.Text copyable className="wrap-anywhere">
                {lastUploaded.iconUrl}
              </Typography.Text>
              <Image
                src={lastUploaded.iconUrl}
                width={56}
                height={56}
                className="token-icon-preview"
              />
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="已写入图标库">
            {lastUploaded.savedToRegistry ? "是" : "否"}
          </Descriptions.Item>
        </Descriptions>
      ) : null}

      <Divider />

      <Typography.Title level={5}>测试客户端能否匹配图标</Typography.Title>
      <Form form={metadataForm} layout="vertical">
        <Row gutter={16}>
          <Col xs={24} md={12} lg={10}>
            <Form.Item name="contractAddress" label="合约地址">
              <Input placeholder="0x..." />
            </Form.Item>
          </Col>
          <Col xs={24} md={8} lg={6}>
            <Form.Item name="symbol" label="Symbol（可选）">
              <Input placeholder="BNB / ETZ" maxLength={32} />
            </Form.Item>
          </Col>
          <Col xs={24} md={4} lg={4}>
            <Form.Item label=" ">
              <Button loading={metadataLoading} onClick={testMetadata}>
                查询图标/价格
              </Button>
            </Form.Item>
          </Col>
        </Row>
      </Form>

      {metadata ? (
        <Descriptions
          bordered
          size="small"
          column={1}
          className="token-icon-result"
        >
          <Descriptions.Item label="匹配来源">
            {metadata.source || "-"}
          </Descriptions.Item>
          <Descriptions.Item label="价格 USD">
            {Number(metadata.priceUsd || 0) > 0 ? `$${metadata.priceUsd}` : "未获取到"}
          </Descriptions.Item>
          <Descriptions.Item label="图标">
            {metadata.iconUrl ? (
              <Space direction="vertical" size={8} style={{ width: "100%" }}>
                <Typography.Text copyable className="wrap-anywhere">
                  {metadata.iconUrl}
                </Typography.Text>
                <Image
                  src={metadata.iconUrl}
                  width={56}
                  height={56}
                  className="token-icon-preview"
                />
              </Space>
            ) : (
              "未匹配到图标"
            )}
          </Descriptions.Item>
          <Descriptions.Item label="完整返回">
            <pre className="json-preview">
              {JSON.stringify(metadata, null, 2)}
            </pre>
          </Descriptions.Item>
        </Descriptions>
      ) : null}
    </Card>
  );
}

function TokenPriceTools() {
  const [form] = Form.useForm<{
    contractAddress?: string;
    symbol?: string;
  }>();
  const [loading, setLoading] = useState(false);
  const [metadata, setMetadata] = useState<TokenMetadataResponse | null>(null);

  const testPrice = async () => {
    const values = await form.validateFields();
    const query = buildQuery({
      contractAddress: values.contractAddress,
      symbol: values.symbol,
      force: 1,
    });
    if (!query) {
      message.error("请输入合约地址或 symbol");
      return;
    }

    setLoading(true);
    try {
      const data = await adminRequest<TokenMetadataResponse>(
        `/wallet/token-price${query}`,
      );
      setMetadata(data);
      if (Number(data.priceUsd || 0) > 0) {
        message.success("已获取到行情价格");
      } else {
        message.warning("暂未获取到价格，请检查是否有流动性，或在自定义代币价格库里配置固定价格");
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : "查询失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card title="代币行情价格工具" className="token-price-tools-card">
      <Alert
        type="info"
        showIcon
        message="客户端行情价格逻辑"
        description="用户添加自定义代币时，客户端会调用 token-metadata；钱包首页刷新时还会批量调用 token-prices。服务端会先查后台配置的固定价格，再按合约地址从公开行情源获取价格。获取不到时客户端显示“价格 --”，避免把未知价格误显示成 $0.00。"
        style={{ marginBottom: 16 }}
      />
      <Form form={form} layout="vertical">
        <Row gutter={16}>
          <Col xs={24} md={12} lg={10}>
            <Form.Item name="contractAddress" label="合约地址">
              <Input placeholder="0x..." />
            </Form.Item>
          </Col>
          <Col xs={24} md={8} lg={6}>
            <Form.Item name="symbol" label="Symbol（可选）">
              <Input placeholder="BNB / ETZ" maxLength={32} />
            </Form.Item>
          </Col>
          <Col xs={24} md={4} lg={4}>
            <Form.Item label=" ">
              <Button loading={loading} onClick={testPrice}>
                查询行情
              </Button>
            </Form.Item>
          </Col>
        </Row>
      </Form>

      {metadata ? (
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="价格 USD">
            {Number(metadata.priceUsd || 0) > 0 ? `$${metadata.priceUsd}` : "未获取到"}
          </Descriptions.Item>
          <Descriptions.Item label="来源">
            {metadata.source || "-"}
          </Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {metadata.updatedAt ? formatTime(metadata.updatedAt) : "-"}
          </Descriptions.Item>
          <Descriptions.Item label="完整返回">
            <pre className="json-preview">
              {JSON.stringify(metadata, null, 2)}
            </pre>
          </Descriptions.Item>
        </Descriptions>
      ) : null}
    </Card>
  );
}

export default function SystemPage() {
  const [form] = Form.useForm<RuntimeSettingsFormValues>();
  const [loading, setLoading] = useState(false);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [info, setInfo] = useState<SystemInfo | null>(null);
  const [settingsPayload, setSettingsPayload] =
    useState<RuntimeSettingsPayload | null>(null);

  const applySettingsToForm = useCallback(
    (values: RuntimeSettingsValues) => {
      const {
        fallbackReleaseDate,
        walletTokens,
        rpcUrls,
        tokenIconRegistry,
        tokenPriceProviderOrder,
        tokenPriceRegistry,
        ...rest
      } = values;
      form.setFieldsValue({
        ...rest,
        fallbackReleaseDate: fallbackReleaseDate
          ? dayjs(Number(fallbackReleaseDate) * 1000)
          : null,
        walletTokensText: JSON.stringify(walletTokens || [], null, 2),
        tokenIconRegistryText: JSON.stringify(tokenIconRegistry || [], null, 2),
        tokenPriceProviderOrderText: JSON.stringify(
          tokenPriceProviderOrder || ["dexscreener", "defillama", "coingecko"],
          null,
          2,
        ),
        tokenPriceRegistryText: JSON.stringify(tokenPriceRegistry || [], null, 2),
        rpcUrlsText: stringifyRpcUrls(rpcUrls),
      });
    },
    [form],
  );

  const loadSystem = useCallback(async () => {
    setLoading(true);
    try {
      const data = await adminRequest<SystemInfo>("/system");
      setInfo(data);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadSettings = useCallback(async () => {
    setSettingsLoading(true);
    try {
      const data = await adminRequest<RuntimeSettingsPayload>("/settings");
      setSettingsPayload(data);
      applySettingsToForm(data.values);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "加载参数失败");
    } finally {
      setSettingsLoading(false);
    }
  }, [applySettingsToForm]);

  const reloadAfterRegistrySaved = useCallback(() => {
    loadSettings();
    loadSystem();
  }, [loadSettings, loadSystem]);

  useEffect(() => {
    loadSystem();
    loadSettings();
  }, [loadSystem, loadSettings]);

  const saveSettings = async (values: RuntimeSettingsFormValues) => {
    let walletTokens: WalletToken[];
    let tokenIconRegistry:
      | TokenIconRegistryItem[]
      | Record<string, string | Partial<TokenIconRegistryItem>>;
    let tokenPriceProviderOrder: string[];
    let tokenPriceRegistry:
      | TokenPriceRegistryItem[]
      | Record<string, string | Partial<TokenPriceRegistryItem>>;
    let rpcUrls: string[] | RpcEndpoint[];
    try {
      walletTokens = parseWalletTokens(values.walletTokensText);
      tokenIconRegistry = parseTokenIconRegistry(values.tokenIconRegistryText);
      tokenPriceProviderOrder = parseTokenPriceProviderOrder(
        values.tokenPriceProviderOrderText,
      );
      tokenPriceRegistry = parseTokenPriceRegistry(values.tokenPriceRegistryText);
      rpcUrls = parseRpcUrlsText(values.rpcUrlsText);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "JSON 格式错误");
      return;
    }

    const body = {
      publicHost: values.publicHost,
      rpcUrls,
      redPacketContract: values.redPacketContract,
      maxExpiresInSeconds: values.maxExpiresInSeconds,
      appUploadPublicPath: values.appUploadPublicPath,
      appUploadDir: values.appUploadDir,
      appUploadUrlBase: values.appUploadUrlBase,
      maxApkUploadMB: values.maxApkUploadMB,
      fallbackVersionCode: values.fallbackVersionCode,
      fallbackVersionName: values.fallbackVersionName,
      fallbackDownloadUrl: values.fallbackDownloadUrl,
      fallbackVersionMessage: values.fallbackVersionMessage,
      fallbackReleaseDate: values.fallbackReleaseDate
        ? values.fallbackReleaseDate.unix()
        : 0,
      fallbackApkSizeBytes: values.fallbackApkSizeBytes,
      proxyAddress: values.proxyAddress,
      proxyPort: values.proxyPort,
      proxyUsername: values.proxyUsername,
      proxyPassword: values.proxyPassword,
      proxySecret: values.proxySecret,
      bnbIconUrl: values.bnbIconUrl,
      tokenIconPublicPath: values.tokenIconPublicPath,
      tokenIconDir: values.tokenIconDir,
      tokenIconRegistry,
      tokenPriceAutoEnabled: values.tokenPriceAutoEnabled,
      tokenPriceExternalTtlSeconds: values.tokenPriceExternalTtlSeconds,
      tokenPriceProviderOrder,
      tokenPriceRegistry,
      walletTokens,
    };

    setSaving(true);
    try {
      const data = await adminRequest<RuntimeSettingsPayload>("/settings", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setSettingsPayload(data);
      applySettingsToForm(data.values);
      message.success("系统参数已保存");
      loadSystem();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: "100%" }}>
      <Card title="系统状态" loading={loading}>
        <Descriptions bordered column={2} size="small">
          <Descriptions.Item label="当前时间">
            {formatTime(info?.now)}
          </Descriptions.Item>
          <Descriptions.Item label="服务启动时间">
            {formatTime(info?.serverStartedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="RPC">
            <BoolTag value={Boolean(info?.health.rpcOk)} />
          </Descriptions.Item>
          <Descriptions.Item label="当前区块">
            {info?.health.blockNumber ?? "-"}
          </Descriptions.Item>
          <Descriptions.Item label="数据库">
            <BoolTag value={Boolean(info?.health.dbOk)} />
          </Descriptions.Item>
          <Descriptions.Item label="MySQL 版本">
            {info?.database.version || "-"}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="当前生效配置" loading={loading}>
        <Descriptions bordered column={1} size="small">
          {Object.entries(info?.config || {}).map(([key, value]) => (
            <Descriptions.Item key={key} label={key}>
              {stringifyConfigValue(value)}
            </Descriptions.Item>
          ))}
        </Descriptions>
      </Card>

      <Card
        title="RPC 连接状态（服务端检测）"
        loading={loading}
        extra={<Button onClick={loadSystem}>重新检测</Button>}
      >
        <Table<RpcStatusRow>
          rowKey={(row) => row.url}
          dataSource={info?.rpcStatus || []}
          pagination={false}
          columns={[
            {
              title: "名称",
              dataIndex: "name",
              render: (value) => value || "-",
            },
            { title: "RPC URL", dataIndex: "url", ellipsis: true },
            {
              title: "状态",
              dataIndex: "ok",
              render: (value: boolean) => <BoolTag value={value} />,
            },
            {
              title: "链 ID",
              dataIndex: "chainId",
              render: (value) => value ?? "-",
            },
            {
              title: "区块",
              dataIndex: "blockNumber",
              render: (value) => value ?? "-",
            },
            {
              title: "延迟",
              dataIndex: "latencyMs",
              render: (value) =>
                value === null || value === undefined ? "-" : `${value} ms`,
            },
            {
              title: "错误",
              dataIndex: "error",
              ellipsis: true,
              render: (value) => value || "-",
            },
          ]}
        />
      </Card>

      <TokenIconTools onRegistrySaved={reloadAfterRegistrySaved} />

      <TokenPriceTools />

      <Card
        title="运行参数配置"
        loading={settingsLoading}
        extra={
          <Space>
            <Typography.Text type="secondary">保存后立即生效</Typography.Text>
            <Button onClick={loadSettings}>重置</Button>
            <Button
              type="primary"
              loading={saving}
              onClick={() => form.submit()}
            >
              保存参数
            </Button>
          </Space>
        }
      >
        <Form<RuntimeSettingsFormValues>
          form={form}
          layout="vertical"
          onFinish={saveSettings}
          initialValues={{
            maxApkUploadMB: 150,
            maxExpiresInSeconds: 2592000,
            proxyPort: 443,
            tokenIconPublicPath: "/uploads/token-icons",
            tokenIconDir: "./uploads/token-icons",
            tokenIconRegistryText: "[]",
            tokenPriceAutoEnabled: 1,
            tokenPriceExternalTtlSeconds: 300,
            tokenPriceProviderOrderText: '["dexscreener","defillama","coingecko"]',
            tokenPriceRegistryText: "[]",
          }}
        >
          <Typography.Title level={5}>基础、链与红包参数</Typography.Title>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="publicHost"
                label="服务公网地址"
                rules={[{ required: true, message: "请输入服务公网地址" }]}
              >
                <Input placeholder="https://api.example.com" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="redPacketContract"
                label="红包合约地址"
                rules={[{ required: true, message: "请输入红包合约地址" }]}
              >
                <Input placeholder="0x..." />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="maxExpiresInSeconds"
                label="红包最大有效期（秒）"
                rules={[{ required: true, message: "请输入红包最大有效期" }]}
              >
                <InputNumber
                  min={1}
                  max={2592000}
                  precision={0}
                  style={{ width: "100%" }}
                />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="rpcUrlsText"
                label="BSC RPC URL 列表（客户端节点页读取）"
                tooltip="每行一个 RPC URL；也支持 JSON 数组 [{ name, url, enabled }]。服务端会检测所有地址，优先使用连接正常、区块最新、延迟最低的 RPC；客户端节点页面会读取这个列表，并在用户手机上再次本地测速后自动选择最佳节点。"
                rules={[{ required: true, message: "请至少配置一个 RPC URL" }]}
              >
                <Input.TextArea
                  rows={6}
                  spellCheck={false}
                  placeholder={
                    "https://data-seed-prebsc-1-s1.bnbchain.org:8545\nhttps://data-seed-prebsc-2-s1.bnbchain.org:8545"
                  }
                />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>客户端更新参数</Typography.Title>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="appUploadPublicPath"
                label="APK 公开下载路径"
                rules={[{ required: true, message: "请输入 APK 公开下载路径" }]}
              >
                <Input placeholder="/uploads/apks" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="appUploadDir"
                label="APK 保存目录"
                rules={[{ required: true, message: "请输入 APK 保存目录" }]}
              >
                <Input placeholder="./uploads/apks" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="appUploadUrlBase" label="APK 下载 URL Base">
                <Input placeholder="留空则使用服务公网地址 + APK 公开下载路径" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="maxApkUploadMB"
                label="APK 最大上传大小（MB）"
                rules={[{ required: true, message: "请输入 APK 最大上传大小" }]}
              >
                <InputNumber
                  min={1}
                  max={2048}
                  precision={0}
                  style={{ width: "100%" }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="fallbackVersionCode"
                label="兜底版本号 versionCode"
                rules={[{ required: true, message: "请输入兜底版本号" }]}
              >
                <InputNumber min={1} precision={0} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="fallbackVersionName"
                label="兜底版本名称 versionName"
                rules={[{ required: true, message: "请输入兜底版本名称" }]}
              >
                <Input maxLength={64} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="fallbackDownloadUrl" label="兜底 APK 下载地址">
                <Input placeholder="没有发布版本时使用" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="fallbackReleaseDate" label="兜底发布日期">
                <DatePicker showTime style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="fallbackApkSizeBytes"
                label="兜底 APK 大小（字节）"
              >
                <InputNumber min={0} precision={0} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="fallbackVersionMessage" label="兜底更新内容">
                <Input.TextArea rows={4} maxLength={5000} showCount />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>客户端代理参数</Typography.Title>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="proxyAddress"
                label="代理地址"
                rules={[{ required: true, message: "请输入代理地址" }]}
              >
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="proxyPort"
                label="代理端口"
                rules={[{ required: true, message: "请输入代理端口" }]}
              >
                <InputNumber
                  min={1}
                  max={65535}
                  precision={0}
                  style={{ width: "100%" }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="proxyUsername" label="代理用户名">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="proxyPassword" label="代理密码">
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="proxySecret" label="代理 Secret">
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>钱包代币、图标与行情价格参数</Typography.Title>
          <Alert
            type="warning"
            showIcon
            message="代币图标和行情价格都必须按合约地址精确匹配"
            description="walletTokens 用于客户端默认显示的代币；tokenIconRegistry 用于用户手动添加自定义代币时按合约地址补图标；tokenPriceRegistry 用于给没有公开行情的小众币手动配置 USD 行情价格。服务端会自动尝试公开行情源，仍获取不到时客户端显示“价格 --”。"
            style={{ marginBottom: 16 }}
          />
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="bnbIconUrl" label="BNB 图标地址">
                <Input placeholder="https://.../logo.png，或 bnb.png" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="tokenIconPublicPath"
                label="代币图标公开路径"
                rules={[{ required: true, message: "请输入代币图标公开路径" }]}
              >
                <Input placeholder="/uploads/token-icons" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="tokenIconDir"
                label="代币图标保存目录"
                rules={[{ required: true, message: "请输入代币图标保存目录" }]}
              >
                <Input placeholder="./uploads/token-icons" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item
                name="tokenPriceAutoEnabled"
                label="自动获取代币行情价格"
                tooltip="1=开启，0=关闭。开启后服务端会按合约地址查询公开行情源。"
              >
                <InputNumber min={0} max={1} precision={0} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="tokenPriceExternalTtlSeconds"
                label="行情价格缓存时间（秒）"
                tooltip="建议 120-900 秒。缓存太短会增加公开行情源请求压力。"
              >
                <InputNumber min={30} max={86400} precision={0} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="tokenPriceProviderOrderText"
            label="行情价格来源顺序 JSON"
            tooltip='数组格式，例如 ["dexscreener", "defillama", "coingecko"]。服务端会按顺序查询，先拿到有效价格就返回。'
          >
            <Input.TextArea
              rows={3}
              spellCheck={false}
              placeholder='["dexscreener", "defillama", "coingecko"]'
            />
          </Form.Item>
          <Form.Item
            name="tokenPriceRegistryText"
            label="自定义代币价格库 JSON"
            tooltip="用户添加自定义代币时，服务端会优先按合约地址查这里的固定 USD 行情价格。适合测试网代币、项目币、小众币。"
          >
            <Input.TextArea
              rows={8}
              spellCheck={false}
              placeholder={
                '{\n  "0x1111111111111111111111111111111111111111": "0.0123",\n  "0x2222222222222222222222222222222222222222": {\n    "symbol": "XYZ",\n    "priceUsd": "1.25"\n  }\n}'
              }
            />
          </Form.Item>
          <Form.Item
            name="walletTokensText"
            label="默认钱包代币 JSON"
            tooltip="格式：[{ symbol, contractAddress, decimals, priceUsd, iconUrl }]。priceUsd 是固定行情价格；如果填 0，服务端会尝试公开行情源。iconUrl 可填完整 URL，也可填文件名。"
          >
            <Input.TextArea
              rows={8}
              spellCheck={false}
              placeholder={
                '[\n  {\n    "symbol": "ETZ",\n    "contractAddress": "0xc78dabf21594c76ad98a0b3ed103fcfcd9499999",\n    "decimals": 18,\n    "priceUsd": "0",\n    "iconUrl": "etz.png"\n  }\n]'
              }
            />
          </Form.Item>
          <Form.Item
            name="tokenIconRegistryText"
            label="自定义代币图标库 JSON"
            tooltip="用户添加自定义代币时，服务端按合约地址查这里。支持数组，也支持对象映射；这里的 priceUsd 也会参与行情价格兜底。"
          >
            <Input.TextArea
              rows={8}
              spellCheck={false}
              placeholder={
                '{\n  "0x1111111111111111111111111111111111111111": "abc.png",\n  "0x2222222222222222222222222222222222222222": {\n    "symbol": "XYZ",\n    "decimals": 18,\n    "iconUrl": "xyz.png"\n  }\n}'
              }
            />
          </Form.Item>

          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            当前参数定义数：{settingsPayload?.definitions?.length || 0}
            。数据库连接、管理后台登录凭据、端口和链 ID 仍由部署环境管理；RPC
            URL、红包合约地址、默认代币和代币图标已经改为后台运行参数管理。
          </Typography.Paragraph>
        </Form>
      </Card>

      <Card title="数据表" loading={loading}>
        <Table
          rowKey="tableName"
          dataSource={info?.database.tables || []}
          pagination={false}
          columns={[
            { title: "表名", dataIndex: "tableName" },
            { title: "估算行数", dataIndex: "estimatedRows" },
          ]}
        />
      </Card>
    </Space>
  );
}
