import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Typography } from 'antd';
import { adminRequest, buildQuery } from '../api';
import { formatAmountWei, formatTime, numberText, shortAddress } from '../utils';

type WalletRow = Record<string, any>;

export default function WalletsPage() {
  const columns: ProColumns<WalletRow>[] = [
    { title: '搜索钱包', dataIndex: 'search', hideInTable: true },
    {
      title: '钱包地址',
      dataIndex: 'wallet',
      search: false,
      render: (value) => <Typography.Text className="mono" copyable={{ text: String(value || '') }}>{shortAddress(String(value || ''), 12, 10)}</Typography.Text>,
    },
    { title: '创建红包数', dataIndex: 'sentPackets', search: false, render: numberText },
    { title: '领取次数', dataIndex: 'claimCount', search: false, render: numberText },
    { title: '发送总额 Wei', dataIndex: 'sentAmountWei', search: false, render: (value) => formatAmountWei(String(value || '0'), 18) },
    { title: '领取总额 Wei', dataIndex: 'claimedAmountWei', search: false, render: (value) => formatAmountWei(String(value || '0'), 18) },
    { title: '最近活动', dataIndex: 'lastActivity', search: false, render: (value) => formatTime(String(value)) },
  ];

  return (
    <ProTable<WalletRow>
      rowKey="wallet"
      columns={columns}
      search={{ labelWidth: 90 }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      request={async (params) => {
        const query = buildQuery({
          page: params.current,
          pageSize: params.pageSize,
          search: params.search,
        });
        const result = await adminRequest<{ rows: WalletRow[]; total: number }>(`/wallets${query}`);
        return { data: result.rows, total: result.total, success: true };
      }}
    />
  );
}
