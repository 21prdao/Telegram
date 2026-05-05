import { DownloadOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { adminApiUrl, adminRequest, buildQuery } from '../api';
import { formatAmountWei, formatTime, shortAddress } from '../utils';

type RefundRow = Record<string, any>;

export default function RefundsPage() {
  const columns: ProColumns<RefundRow>[] = [
    { title: '搜索', dataIndex: 'search', hideInTable: true, fieldProps: { placeholder: 'Packet ID / 钱包 / tx' } },
    { title: 'Packet ID', dataIndex: 'packetId', hideInTable: true },
    { title: '钱包', dataIndex: 'wallet', hideInTable: true },
    { title: '代币', dataIndex: 'tokenSymbol', hideInTable: true },
    {
      title: 'Packet ID',
      dataIndex: 'packet_id',
      search: false,
      render: (value) => <Link className="mono" to={`/packets/${value}`}>{String(value)}</Link>,
    },
    { title: '创建者', dataIndex: 'creator_address', search: false, render: (value) => <span className="mono">{shortAddress(String(value || ''))}</span> },
    { title: '代币', dataIndex: 'token_symbol', search: false, render: (value) => <Tag>{String(value || '-')}</Tag> },
    {
      title: '退款金额',
      dataIndex: 'amount_wei',
      search: false,
      render: (_, row) => formatAmountWei(row.amount_wei, Number(row.token_decimals || 18), row.token_symbol),
    },
    { title: '交易', dataIndex: 'tx_hash', search: false, ellipsis: true, render: (value) => <span className="mono">{String(value || '-')}</span> },
    { title: '时间', dataIndex: 'created_at', search: false, render: (value) => formatTime(String(value)) },
  ];

  return (
    <ProTable<RefundRow>
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 90 }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      request={async (params) => {
        const query = buildQuery({
          page: params.current,
          pageSize: params.pageSize,
          search: params.search,
          packetId: params.packetId,
          wallet: params.wallet,
          tokenSymbol: params.tokenSymbol,
        });
        const result = await adminRequest<{ rows: RefundRow[]; total: number }>(`/refunds${query}`);
        return { data: result.rows, total: result.total, success: true };
      }}
      toolBarRender={() => [
        <Button key="export" icon={<DownloadOutlined />} onClick={() => window.open(adminApiUrl('/export/refunds.csv'), '_blank')}>
          导出 CSV
        </Button>,
      ]}
    />
  );
}
