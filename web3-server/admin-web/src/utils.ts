import dayjs from 'dayjs';

export const statusValueEnum = {
  active: { text: '进行中', status: 'Success' },
  pending_create_confirm: { text: '待链上确认', status: 'Warning' },
  empty: { text: '已领完', status: 'Default' },
  expired: { text: '已过期', status: 'Error' },
  refunded: { text: '已退款', status: 'Processing' },
};

export function formatTime(seconds?: number | string | null): string {
  const value = Number(seconds || 0);
  if (!value) return '-';
  return dayjs(value * 1000).format('YYYY-MM-DD HH:mm:ss');
}

export function shortAddress(value?: string | null, left = 8, right = 6): string {
  const text = String(value || '');
  if (!text || text.length <= left + right + 3) return text || '-';
  return `${text.slice(0, left)}...${text.slice(-right)}`;
}

export function formatAmountWei(value?: string | number | null, decimals = 18, symbol = ''): string {
  const raw = String(value ?? '0');
  const d = Math.max(Number(decimals || 18), 0);
  try {
    const amount = BigInt(raw);
    let scale = 1n;
    for (let i = 0; i < d; i += 1) scale *= 10n;
    if (scale === 1n) return `${amount.toString()}${symbol ? ` ${symbol}` : ''}`;
    const whole = amount / scale;
    const frac = (amount % scale).toString().padStart(d, '0').slice(0, 6).replace(/0+$/, '');
    return `${whole.toString()}${frac ? `.${frac}` : ''}${symbol ? ` ${symbol}` : ''}`;
  } catch {
    return `${raw}${symbol ? ` ${symbol}` : ''}`;
  }
}

export function numberText(value?: number | string | null): string {
  return Number(value || 0).toLocaleString('zh-CN');
}
