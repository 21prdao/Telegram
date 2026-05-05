import { useEffect, useState } from 'react';
import { Card, Descriptions, Space, Spin, Table, Tag, Typography, message } from 'antd';
import { useParams } from 'react-router-dom';
import { adminRequest } from '../api';
import { formatAmountWei, formatTime, shortAddress, statusValueEnum } from '../utils';

type PacketDetail = {
  packet: Record<string, any>;
  claims: Array<Record<string, any>>;
  refunds: Array<Record<string, any>>;
};

function statusText(status: string) {
  const item = (statusValueEnum as Record<string, any>)[status];
  return item?.text || status || '-';
}

export default function PacketDetailPage() {
  const { packetId } = useParams();
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<PacketDetail | null>(null);

  useEffect(() => {
    if (!packetId) return;
    setLoading(true);
    adminRequest<PacketDetail>(`/packets/${encodeURIComponent(packetId)}`)
      .then(setDetail)
      .catch((error) => message.error(error instanceof Error ? error.message : '加载失败'))
      .finally(() => setLoading(false));
  }, [packetId]);

  if (loading || !detail) {
    return (
      <Card>
        <Spin />
      </Card>
    );
  }

  const packet = detail.packet;
  const claimed = Number(packet.claim_count || 0);
  const total = Number(packet.count_total || 0);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title="红包详情">
        <Descriptions bordered column={2} size="small">
          <Descriptions.Item label="Packet ID">
            <Typography.Text className="mono wrap-anywhere">{packet.packet_id}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Packet ID Hex">
            <Typography.Text className="mono wrap-anywhere">{packet.packet_id_hex}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag>{statusText(packet.runtime_status)}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="创建者">
            <Typography.Text className="mono wrap-anywhere">{packet.creator_wallet}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="代币">{packet.token_symbol}</Descriptions.Item>
          <Descriptions.Item label="总额">
            {formatAmountWei(packet.total_amount_wei, Number(packet.token_decimals || 18), packet.token_symbol)}
          </Descriptions.Item>
          <Descriptions.Item label="单份金额">
            {formatAmountWei(packet.amount_per_claim_wei, Number(packet.token_decimals || 18), packet.token_symbol)}
          </Descriptions.Item>
          <Descriptions.Item label="领取进度">
            {claimed}/{total}，剩余 {Number(packet.remaining_count || 0)}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatTime(packet.created_at)}</Descriptions.Item>
          <Descriptions.Item label="过期时间">{formatTime(packet.expires_at)}</Descriptions.Item>
          <Descriptions.Item label="创建交易" span={2}>
            <Typography.Text className="mono wrap-anywhere">{packet.create_tx_hash || '-'}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="祝福语" span={2}>{packet.greeting || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="领取记录">
        <Table
          rowKey="id"
          dataSource={detail.claims}
          pagination={false}
          columns={[
            { title: '领取者', dataIndex: 'claimer_address', render: (value) => <span className="mono">{shortAddress(String(value || ''))}</span> },
            {
              title: '金额',
              dataIndex: 'amount_wei',
              render: (_, row) => formatAmountWei(row.amount_wei, Number(packet.token_decimals || 18), packet.token_symbol),
            },
            { title: '交易', dataIndex: 'tx_hash', render: (value) => <Typography.Text className="mono wrap-anywhere">{String(value || '-')}</Typography.Text> },
            { title: '时间', dataIndex: 'created_at', render: (value) => formatTime(String(value)) },
          ]}
        />
      </Card>

      <Card title="退款记录">
        <Table
          rowKey="id"
          dataSource={detail.refunds}
          pagination={false}
          columns={[
            { title: '创建者', dataIndex: 'creator_address', render: (value) => <span className="mono">{shortAddress(String(value || ''))}</span> },
            {
              title: '金额',
              dataIndex: 'amount_wei',
              render: (_, row) => formatAmountWei(row.amount_wei, Number(packet.token_decimals || 18), packet.token_symbol),
            },
            { title: '交易', dataIndex: 'tx_hash', render: (value) => <Typography.Text className="mono wrap-anywhere">{String(value || '-')}</Typography.Text> },
            { title: '时间', dataIndex: 'created_at', render: (value) => formatTime(String(value)) },
          ]}
        />
      </Card>
    </Space>
  );
}
