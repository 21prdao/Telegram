import { useEffect, useState } from 'react';
import { Card, Col, Progress, Row, Table, Tag, Typography, message } from 'antd';
import { StatisticCard } from '@ant-design/pro-components';
import { Link } from 'react-router-dom';
import { adminRequest } from '../api';
import { formatAmountWei, formatTime, numberText, shortAddress } from '../utils';

type DashboardData = {
  stats: Record<string, number | string | null>;
  claims: Record<string, number | string | null>;
  refunds: Record<string, number | string | null>;
  tokens: Array<Record<string, any>>;
  daily: Array<Record<string, any>>;
  topCreators: Array<Record<string, any>>;
  recentPackets: Array<Record<string, any>>;
};

export default function DashboardPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<DashboardData | null>(null);

  useEffect(() => {
    setLoading(true);
    adminRequest<DashboardData>('/stats')
      .then(setData)
      .catch((error) => message.error(error instanceof Error ? error.message : '加载失败'))
      .finally(() => setLoading(false));
  }, []);

  const stats = data?.stats || {};
  const claims = data?.claims || {};
  const refunds = data?.refunds || {};
  const claimedSlots = Number(stats.claimedSlots || 0);
  const totalSlots = Number(stats.totalSlots || 0);
  const claimPercent = totalSlots > 0 ? Math.round((claimedSlots / totalSlots) * 100) : 0;

  return (
    <div>
      <StatisticCard.Group direction="row">
        <StatisticCard statistic={{ title: '总红包', value: Number(stats.totalPackets || 0) }} />
        <StatisticCard statistic={{ title: '进行中', value: Number(stats.activePackets || 0) }} />
        <StatisticCard statistic={{ title: '待确认', value: Number(stats.pendingPackets || 0) }} />
        <StatisticCard statistic={{ title: '总领取次数', value: Number(claims.totalClaims || 0) }} />
        <StatisticCard statistic={{ title: '退款次数', value: Number(refunds.totalRefunds || 0) }} />
      </StatisticCard.Group>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={8}>
          <Card title="领取进度" loading={loading}>
            <Progress type="dashboard" percent={claimPercent} />
            <Typography.Paragraph>
              已领取 {numberText(claimedSlots)} / {numberText(totalSlots)} 个名额
            </Typography.Paragraph>
          </Card>
        </Col>
        <Col xs={24} lg={16}>
          <Card title="代币分布" loading={loading}>
            <Table
              rowKey={(row) => `${row.tokenSymbol}-${row.tokenAddress}`}
              dataSource={data?.tokens || []}
              pagination={false}
              size="small"
              columns={[
                { title: '代币', dataIndex: 'tokenSymbol' },
                { title: '红包数', dataIndex: 'packets', render: numberText },
                { title: '已领取名额', dataIndex: 'claimedSlots', render: numberText },
                {
                  title: '总额',
                  dataIndex: 'totalAmountWei',
                  render: (_, row) => formatAmountWei(row.totalAmountWei, Number(row.tokenDecimals || 18), row.tokenSymbol),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={14}>
          <Card title="最近红包" loading={loading}>
            <Table
              rowKey="packet_id"
              dataSource={data?.recentPackets || []}
              pagination={false}
              size="small"
              columns={[
                {
                  title: 'Packet ID',
                  dataIndex: 'packet_id',
                  render: (value) => <Link className="mono" to={`/packets/${value}`}>{String(value)}</Link>,
                },
                { title: '创建者', dataIndex: 'creator_wallet', render: (value) => <span className="mono">{shortAddress(String(value || ''))}</span> },
                { title: '代币', dataIndex: 'token_symbol', render: (value) => <Tag>{String(value || '-')}</Tag> },
                { title: '状态', dataIndex: 'runtime_status' },
                { title: '创建时间', dataIndex: 'created_at', render: (value) => formatTime(String(value)) },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          <Card title="Top 创建者" loading={loading}>
            <Table
              rowKey="creatorWallet"
              dataSource={data?.topCreators || []}
              pagination={false}
              size="small"
              columns={[
                { title: '钱包', dataIndex: 'creatorWallet', render: (value) => <span className="mono">{shortAddress(String(value || ''))}</span> },
                { title: '红包数', dataIndex: 'packets', render: numberText },
                { title: '已领取名额', dataIndex: 'claimedSlots', render: numberText },
                { title: '最近创建', dataIndex: 'lastCreatedAt', render: (value) => formatTime(String(value)) },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
