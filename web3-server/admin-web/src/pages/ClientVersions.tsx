import { useRef, useState } from 'react';
import { CloudUploadOutlined, UploadOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, DatePicker, Form, Input, InputNumber, Modal, Space, Switch, Tag, Typography, Upload, message } from 'antd';
import type { UploadFile } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { adminRequest, buildQuery } from '../api';
import { formatTime, numberText } from '../utils';

type ClientVersionRow = {
  id: number;
  version_code: number;
  version_name: string;
  release_notes: string;
  download_url: string;
  apk_original_name: string;
  apk_size_bytes: number | string;
  apk_sha256: string;
  force_update: number | boolean;
  enabled: number | boolean;
  release_date: number;
  created_by: string;
  created_at: number;
  updated_at: number;
};

type ClientVersionForm = {
  versionCode: number;
  versionName: string;
  releaseNotes: string;
  releaseDate?: Dayjs;
  forceUpdate?: boolean;
  enabled?: boolean;
  apkFile?: UploadFile[];
};

function boolValue(value: unknown): boolean {
  return value === true || value === 1 || value === '1';
}

function formatBytes(value: unknown): string {
  const bytes = Number(value || 0);
  if (!bytes) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function normalizeUploadFileList(event: { fileList?: UploadFile[] } | UploadFile[]): UploadFile[] {
  if (Array.isArray(event)) return event.slice(-1);
  return event?.fileList?.slice(-1) || [];
}

export default function ClientVersionsPage() {
  const actionRef = useRef<ActionType>();
  const [form] = Form.useForm<ClientVersionForm>();
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [editingRow, setEditingRow] = useState<ClientVersionRow | null>(null);

  const openPublishModal = () => {
    setEditingRow(null);
    form.resetFields();
    form.setFieldsValue({
      enabled: true,
      forceUpdate: false,
      releaseDate: dayjs(),
    });
    setModalOpen(true);
  };

  const openEditModal = (row: ClientVersionRow) => {
    setEditingRow(row);
    form.resetFields();
    form.setFieldsValue({
      versionCode: Number(row.version_code),
      versionName: row.version_name,
      releaseNotes: row.release_notes,
      releaseDate: row.release_date ? dayjs(Number(row.release_date) * 1000) : dayjs(),
      forceUpdate: boolValue(row.force_update),
      enabled: boolValue(row.enabled),
      apkFile: [],
    });
    setModalOpen(true);
  };

  const submitVersion = async (values: ClientVersionForm) => {
    const uploadItem = values.apkFile?.[0];
    const apkFile = uploadItem?.originFileObj as File | undefined;
    if (!editingRow && !apkFile) {
      message.error('请上传 APK 文件');
      return;
    }

    const body = new FormData();
    body.append('versionCode', String(values.versionCode));
    body.append('versionName', values.versionName.trim());
    body.append('releaseNotes', values.releaseNotes.trim());
    body.append('releaseDate', String((values.releaseDate || dayjs()).unix()));
    body.append('forceUpdate', values.forceUpdate ? '1' : '0');
    body.append('enabled', values.enabled === false ? '0' : '1');
    if (apkFile) {
      body.append('apkFile', apkFile, apkFile.name);
    }

    setSubmitting(true);
    try {
      await adminRequest<ClientVersionRow>('/client-versions', { method: 'POST', body });
      message.success(editingRow ? '客户端版本已更新' : '客户端版本已发布');
      setModalOpen(false);
      setEditingRow(null);
      actionRef.current?.reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '发布失败');
    } finally {
      setSubmitting(false);
    }
  };

  const toggleEnabled = async (row: ClientVersionRow) => {
    const nextEnabled = !boolValue(row.enabled);
    try {
      await adminRequest<ClientVersionRow>(`/client-versions/${row.id}/enabled`, {
        method: 'POST',
        body: JSON.stringify({ enabled: nextEnabled }),
      });
      message.success(nextEnabled ? '版本已启用' : '版本已停用');
      actionRef.current?.reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '操作失败');
    }
  };

  const columns: ProColumns<ClientVersionRow>[] = [
    { title: '搜索', dataIndex: 'search', hideInTable: true, fieldProps: { placeholder: '版本名称 / 版本号 / 更新内容 / SHA256' } },
    {
      title: '状态',
      dataIndex: 'enabled',
      valueType: 'select',
      hideInTable: true,
      valueEnum: {
        '1': { text: '已启用', status: 'Success' },
        '0': { text: '已停用', status: 'Default' },
      },
    },
    { title: '版本号', dataIndex: 'version_code', search: false, render: (value) => numberText(String(value || 0)) },
    { title: '版本名称', dataIndex: 'version_name', search: false },
    {
      title: '更新内容',
      dataIndex: 'release_notes',
      search: false,
      width: 320,
      render: (value) => (
        <Typography.Paragraph ellipsis={{ rows: 2, expandable: true }} style={{ marginBottom: 0 }}>
          {String(value || '-')}
        </Typography.Paragraph>
      ),
    },
    {
      title: 'APK',
      dataIndex: 'apk_original_name',
      search: false,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          {row.download_url ? (
            <Typography.Link href={row.download_url} target="_blank" rel="noreferrer">
              {row.apk_original_name || '下载 APK'}
            </Typography.Link>
          ) : '-'}
          <Typography.Text type="secondary">{formatBytes(row.apk_size_bytes)}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '强制更新',
      dataIndex: 'force_update',
      search: false,
      render: (value) => (boolValue(value) ? <Tag color="red">强制</Tag> : <Tag>普通</Tag>),
    },
    {
      title: '启用状态',
      dataIndex: 'enabled',
      search: false,
      render: (value) => (boolValue(value) ? <Tag color="green">已启用</Tag> : <Tag>已停用</Tag>),
    },
    { title: '发布日期', dataIndex: 'release_date', search: false, render: (value) => formatTime(String(value)) },
    { title: '上传时间', dataIndex: 'created_at', search: false, render: (value) => formatTime(String(value)) },
    {
      title: '操作',
      valueType: 'option',
      render: (_, row) => [
        <Button key="edit" type="link" onClick={() => openEditModal(row)}>
          更新
        </Button>,
        <Button key="toggle" type="link" onClick={() => toggleEnabled(row)}>
          {boolValue(row.enabled) ? '停用' : '启用'}
        </Button>,
      ],
    },
  ];

  return (
    <>
      <ProTable<ClientVersionRow>
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 90 }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        request={async (params) => {
          const query = buildQuery({
            page: params.current,
            pageSize: params.pageSize,
            search: params.search,
            enabled: params.enabled,
          });
          const result = await adminRequest<{ rows: ClientVersionRow[]; total: number }>(`/client-versions${query}`);
          return { data: result.rows, total: result.total, success: true };
        }}
        toolBarRender={() => [
          <Button key="publish" type="primary" icon={<CloudUploadOutlined />} onClick={openPublishModal}>
            发布新版本
          </Button>,
        ]}
      />

      <Modal
        title={editingRow ? '更新客户端版本' : '发布客户端新版本'}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); setEditingRow(null); }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnClose
        okText={editingRow ? '更新' : '发布'}
      >
        <Form<ClientVersionForm>
          form={form}
          layout="vertical"
          onFinish={submitVersion}
          preserve={false}
        >
          <Form.Item
            name="apkFile"
            label="APK 文件"
            valuePropName="fileList"
            getValueFromEvent={normalizeUploadFileList}
            rules={editingRow ? [] : [{ required: true, message: '请上传 APK 文件' }]}
          >
            <Upload.Dragger accept=".apk,application/vnd.android.package-archive" beforeUpload={() => false} maxCount={1}>
              <p className="ant-upload-drag-icon"><UploadOutlined /></p>
              <p className="ant-upload-text">点击或拖拽 APK 到这里上传</p>
              <p className="ant-upload-hint">发布或更新后，客户端版本检测接口会返回最新启用版本；更新已有版本时不上传 APK 将保留原文件。</p>
            </Upload.Dragger>
          </Form.Item>

          <Form.Item
            name="versionCode"
            label="更新版本号 versionCode"
            rules={[{ required: true, message: '请输入更新版本号' }]}
          >
            <InputNumber min={1} precision={0} disabled={Boolean(editingRow)} style={{ width: '100%' }} placeholder="例如：102" />
          </Form.Item>

          <Form.Item
            name="versionName"
            label="更新版本名称 versionName"
            rules={[{ required: true, message: '请输入更新版本名称' }]}
          >
            <Input maxLength={64} placeholder="例如：1.0.2" />
          </Form.Item>

          <Form.Item
            name="releaseNotes"
            label="更新内容"
            rules={[{ required: true, message: '请输入更新内容' }]}
          >
            <Input.TextArea rows={5} maxLength={5000} showCount placeholder="本次更新内容、修复项、兼容说明等" />
          </Form.Item>

          <Form.Item name="releaseDate" label="发布日期">
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>

          <Space size={32}>
            <Form.Item name="forceUpdate" label="强制更新" valuePropName="checked">
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
            <Form.Item name="enabled" label="立即启用" valuePropName="checked">
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </>
  );
}
