import { DownloadOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Progress, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { adminApiUrl, adminRequest, buildQuery } from '../api';
import { formatAmountWei, formatTime, shortAddress, statusValueEnum } from '../utils';

type PacketRow = Record<string, any>;

export default function PacketsPage() {
  const columns: ProColumns<PacketRow>[] = [
    {
      title: '搜索',
      dataIndex: 'search',
      hideInTable: true,
      fieldProps: { placeholder: 'Packet ID / 钱包 / tx / 祝福语' },
    },
    {
      title: '状态',
      dataIndex: 'status',
      valueType: 'select',
      valueEnum: statusValueEnum,
      hideInTable: true,
    },
    {
      title: '代币',
      dataIndex: 'tokenSymbol',
      hideInTable: true,
    },
    {
      title: '创建者',
      dataIndex: 'creatorWallet',
      hideInTable: true,
    },
    {
      title: 'Packet ID',
      dataIndex: 'packet_id',
      search: false,
      render: (_, row) => <Link className="mono" to={`/packets/${row.packet_id}`}>{row.packet_id}</Link>,
    },
    {
      title: '创建者',
      dataIndex: 'creator_wallet',
      search: false,
      render: (value) => <span className="mono">{shortAddress(String(value || ''))}</span>,
    },
    {
      title: '代币',
      dataIndex: 'token_symbol',
      search: false,
      render: (value) => <Tag>{String(value || '-')}</Tag>,
    },
    {
      title: '总额',
      dataIndex: 'total_amount_wei',
      search: false,
      render: (_, row) => formatAmountWei(row.total_amount_wei, Number(row.token_decimals || 18), row.token_symbol),
    },
    {
      title: '领取进度',
      search: false,
      render: (_, row) => {
        const total = Number(row.count_total || 0);
        const claimed = Number(row.claim_count || 0);
        const percent = total > 0 ? Math.round((claimed / total) * 100) : 0;
        return <Progress percent={percent} size="small" format={() => `${claimed}/${total}`} />;
      },
    },
    {
      title: '状态',
      dataIndex: 'runtime_status',
      search: false,
      valueEnum: statusValueEnum,
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      search: false,
      render: (value) => formatTime(String(value)),
    },
    {
      title: '过期时间',
      dataIndex: 'expires_at',
      search: false,
      render: (value) => formatTime(String(value)),
    },
  ];

  return (
    <ProTable<PacketRow>
      rowKey="packet_id"
      columns={columns}
      search={{ labelWidth: 90 }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      request={async (params) => {
        const query = buildQuery({
          page: params.current,
          pageSize: params.pageSize,
          search: params.search,
          status: params.status,
          tokenSymbol: params.tokenSymbol,
          creatorWallet: params.creatorWallet,
        });
        const result = await adminRequest<{ rows: PacketRow[]; total: number }>(`/packets${query}`);
        return { data: result.rows, total: result.total, success: true };
      }}
      toolBarRender={() => [
        <Button key="export" icon={<DownloadOutlined />} onClick={() => window.open(adminApiUrl('/export/packets.csv'), '_blank')}>
          导出 CSV
        </Button>,
      ]}
    />
  );
}
