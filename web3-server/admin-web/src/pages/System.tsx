import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Col, DatePicker, Descriptions, Form, Input, InputNumber, Row, Space, Table, Tag, Typography, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { adminRequest } from '../api';
import { formatTime } from '../utils';

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
  config: Record<string, any>;
};

type WalletToken = {
  symbol: string;
  contractAddress: string;
  decimals: number;
};

type RuntimeSettingsValues = {
  publicHost: string;
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

type RuntimeSettingsFormValues = Omit<RuntimeSettingsValues, 'fallbackReleaseDate' | 'walletTokens'> & {
  fallbackReleaseDate?: Dayjs | null;
  walletTokensText?: string;
};

function BoolTag({ value }: { value: boolean }) {
  return <Tag color={value ? 'green' : 'red'}>{value ? 'OK' : '异常'}</Tag>;
}

function parseWalletTokens(text?: string): WalletToken[] {
  const raw = String(text || '').trim();
  if (!raw) return [];
  const parsed = JSON.parse(raw) as WalletToken[];
  if (!Array.isArray(parsed)) {
    throw new Error('默认钱包代币列表必须是 JSON 数组');
  }
  return parsed;
}

export default function SystemPage() {
  const [form] = Form.useForm<RuntimeSettingsFormValues>();
  const [loading, setLoading] = useState(false);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [info, setInfo] = useState<SystemInfo | null>(null);
  const [settingsPayload, setSettingsPayload] = useState<RuntimeSettingsPayload | null>(null);

  const applySettingsToForm = useCallback((values: RuntimeSettingsValues) => {
    const { fallbackReleaseDate, walletTokens, ...rest } = values;
    form.setFieldsValue({
      ...rest,
      fallbackReleaseDate: fallbackReleaseDate ? dayjs(Number(fallbackReleaseDate) * 1000) : null,
      walletTokensText: JSON.stringify(walletTokens || [], null, 2),
    });
  }, [form]);

  const loadSystem = useCallback(async () => {
    setLoading(true);
    try {
      const data = await adminRequest<SystemInfo>('/system');
      setInfo(data);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadSettings = useCallback(async () => {
    setSettingsLoading(true);
    try {
      const data = await adminRequest<RuntimeSettingsPayload>('/settings');
      setSettingsPayload(data);
      applySettingsToForm(data.values);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载参数失败');
    } finally {
      setSettingsLoading(false);
    }
  }, [applySettingsToForm]);

  useEffect(() => {
    loadSystem();
    loadSettings();
  }, [loadSystem, loadSettings]);

  const saveSettings = async (values: RuntimeSettingsFormValues) => {
    let walletTokens: WalletToken[];
    try {
      walletTokens = parseWalletTokens(values.walletTokensText);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '默认钱包代币列表 JSON 格式错误');
      return;
    }

    const body = {
      publicHost: values.publicHost,
      maxExpiresInSeconds: values.maxExpiresInSeconds,
      appUploadPublicPath: values.appUploadPublicPath,
      appUploadDir: values.appUploadDir,
      appUploadUrlBase: values.appUploadUrlBase,
      maxApkUploadMB: values.maxApkUploadMB,
      fallbackVersionCode: values.fallbackVersionCode,
      fallbackVersionName: values.fallbackVersionName,
      fallbackDownloadUrl: values.fallbackDownloadUrl,
      fallbackVersionMessage: values.fallbackVersionMessage,
      fallbackReleaseDate: values.fallbackReleaseDate ? values.fallbackReleaseDate.unix() : 0,
      fallbackApkSizeBytes: values.fallbackApkSizeBytes,
      proxyAddress: values.proxyAddress,
      proxyPort: values.proxyPort,
      proxyUsername: values.proxyUsername,
      proxyPassword: values.proxyPassword,
      proxySecret: values.proxySecret,
      walletTokens,
    };

    setSaving(true);
    try {
      const data = await adminRequest<RuntimeSettingsPayload>('/settings', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setSettingsPayload(data);
      applySettingsToForm(data.values);
      message.success('系统参数已保存');
      loadSystem();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title="系统状态" loading={loading}>
        <Descriptions bordered column={2} size="small">
          <Descriptions.Item label="当前时间">{formatTime(info?.now)}</Descriptions.Item>
          <Descriptions.Item label="服务启动时间">{formatTime(info?.serverStartedAt)}</Descriptions.Item>
          <Descriptions.Item label="RPC"><BoolTag value={Boolean(info?.health.rpcOk)} /></Descriptions.Item>
          <Descriptions.Item label="当前区块">{info?.health.blockNumber ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="数据库"><BoolTag value={Boolean(info?.health.dbOk)} /></Descriptions.Item>
          <Descriptions.Item label="MySQL 版本">{info?.database.version || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="当前生效配置" loading={loading}>
        <Descriptions bordered column={1} size="small">
          {Object.entries(info?.config || {}).map(([key, value]) => (
            <Descriptions.Item key={key} label={key}>{String(value ?? '')}</Descriptions.Item>
          ))}
        </Descriptions>
      </Card>

      <Card
        title="运行参数配置"
        loading={settingsLoading}
        extra={(
          <Space>
            <Typography.Text type="secondary">保存后立即生效</Typography.Text>
            <Button onClick={loadSettings}>重置</Button>
            <Button type="primary" loading={saving} onClick={() => form.submit()}>保存参数</Button>
          </Space>
        )}
      >
        <Form<RuntimeSettingsFormValues>
          form={form}
          layout="vertical"
          onFinish={saveSettings}
          initialValues={{ maxApkUploadMB: 150, maxExpiresInSeconds: 2592000, proxyPort: 443 }}
        >
          <Typography.Title level={5}>基础与红包参数</Typography.Title>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="publicHost" label="服务公网地址" rules={[{ required: true, message: '请输入服务公网地址' }]}>
                <Input placeholder="https://api.example.com" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="maxExpiresInSeconds" label="红包最大有效期（秒）" rules={[{ required: true, message: '请输入红包最大有效期' }]}>
                <InputNumber min={1} max={2592000} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Title level={5}>客户端更新参数</Typography.Title>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="appUploadPublicPath" label="APK 公开下载路径" rules={[{ required: true, message: '请输入 APK 公开下载路径' }]}>
                <Input placeholder="/uploads/apks" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="appUploadDir" label="APK 保存目录" rules={[{ required: true, message: '请输入 APK 保存目录' }]}>
                <Input placeholder="./uploads/apks" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="appUploadUrlBase" label="APK 下载 URL Base">
                <Input placeholder="留空则使用服务公网地址 + APK 公开下载路径" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="maxApkUploadMB" label="APK 最大上传大小（MB）" rules={[{ required: true, message: '请输入 APK 最大上传大小' }]}>
                <InputNumber min={1} max={2048} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="fallbackVersionCode" label="兜底版本号 versionCode" rules={[{ required: true, message: '请输入兜底版本号' }]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="fallbackVersionName" label="兜底版本名称 versionName" rules={[{ required: true, message: '请输入兜底版本名称' }]}>
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
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="fallbackApkSizeBytes" label="兜底 APK 大小（字节）">
                <InputNumber min={0} precision={0} style={{ width: '100%' }} />
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
              <Form.Item name="proxyAddress" label="代理地址" rules={[{ required: true, message: '请输入代理地址' }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="proxyPort" label="代理端口" rules={[{ required: true, message: '请输入代理端口' }]}>
                <InputNumber min={1} max={65535} precision={0} style={{ width: '100%' }} />
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

          <Typography.Title level={5}>默认钱包代币</Typography.Title>
          <Form.Item
            name="walletTokensText"
            label="默认钱包代币 JSON"
            tooltip="格式：[{ symbol, contractAddress, decimals }]"
          >
            <Input.TextArea rows={8} spellCheck={false} />
          </Form.Item>

          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            当前参数定义数：{settingsPayload?.definitions?.length || 0}。数据库连接、管理后台登录凭据、端口、链 ID、RPC 和合约地址仍然属于启动级或敏感参数，需要继续通过部署环境配置。
          </Typography.Paragraph>
        </Form>
      </Card>

      <Card title="数据表" loading={loading}>
        <Table
          rowKey="tableName"
          dataSource={info?.database.tables || []}
          pagination={false}
          columns={[
            { title: '表名', dataIndex: 'tableName' },
            { title: '估算行数', dataIndex: 'estimatedRows' },
          ]}
        />
      </Card>
    </Space>
  );
}
