import { useEffect, useState } from 'react';
import { Card, Descriptions, Space, Table, Tag, message } from 'antd';
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

function BoolTag({ value }: { value: boolean }) {
  return <Tag color={value ? 'green' : 'red'}>{value ? 'OK' : '异常'}</Tag>;
}

export default function SystemPage() {
  const [loading, setLoading] = useState(false);
  const [info, setInfo] = useState<SystemInfo | null>(null);

  useEffect(() => {
    setLoading(true);
    adminRequest<SystemInfo>('/system')
      .then(setInfo)
      .catch((error) => message.error(error instanceof Error ? error.message : '加载失败'))
      .finally(() => setLoading(false));
  }, []);

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

      <Card title="配置" loading={loading}>
        <Descriptions bordered column={1} size="small">
          {Object.entries(info?.config || {}).map(([key, value]) => (
            <Descriptions.Item key={key} label={key}>{String(value ?? '')}</Descriptions.Item>
          ))}
        </Descriptions>
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
