/**
 * Red-packet service for Telegram wallet integration.
 * Local run:
 *   npm i
 *   npm run dev
 */
require('dotenv').config();

const crypto = require('crypto');
const express = require('express');
const mysql = require('mysql2/promise');
const { JsonRpcProvider, Interface, getAddress, isAddress } = require('ethers');

const app = express();
app.use(express.json({ limit: '256kb' }));
app.use((req, _res, next) => {
  // eslint-disable-next-line no-console
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.originalUrl}`, req.body || {});
  next();
});

const CHAIN_ID = Number(process.env.CHAIN_ID || 97);
const CONTRACT_ADDRESS = (process.env.RED_PACKET_CONTRACT || '0x5a6361A5Af1c56eDF7E6e9e0B191a92BBf957fC3').trim();
const HOST = process.env.PUBLIC_HOST || 'http://127.0.0.1:8787';
const MAX_PACKET_COUNT = 500;
const RPC_URL = process.env.RPC_URL || 'https://data-seed-prebsc-1-s1.bnbchain.org:8545';

const MYSQL_HOST = process.env.MYSQL_HOST || '127.0.0.1';
const MYSQL_PORT = Number(process.env.MYSQL_PORT || 3306);
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PASSWORD = process.env.MYSQL_PASSWORD || '';
const MYSQL_DATABASE = process.env.MYSQL_DATABASE || 'telegram_red_packet';

const provider = new JsonRpcProvider(RPC_URL, CHAIN_ID);
const contractAddressNorm = normalizeAddress(CONTRACT_ADDRESS);

const contractInterface = new Interface([
  'event PacketCreated(bytes32 indexed packetId, address indexed creator, uint256 total, uint32 count, uint64 expiresAt)',
  'event PacketCreated(bytes32 indexed packetId, address indexed creator, address indexed token, uint256 total, uint32 count, uint64 expiresAt)',
  'event Claimed(bytes32 indexed packetId, address indexed claimer, uint256 amount)',
  'event Claimed(bytes32 indexed packetId, address indexed claimer, address indexed token, uint256 amount)',
]);

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function normalizeAddress(value) {
  if (typeof value !== 'string') return '';
  const v = value.trim();
  if (!isAddress(v)) return '';
  return getAddress(v).toLowerCase();
}

function parsePositiveInt(value) {
  const n = Number(value);
  if (!Number.isInteger(n) || n <= 0) return null;
  return n;
}

function parsePositiveBigInt(value) {
  try {
    const v = BigInt(String(value));
    return v > 0n ? v : null;
  } catch (_) {
    return null;
  }
}

function packetIdToHex(packetId) {
  const bytes = Buffer.from(packetId, 'utf8');
  const hex = bytes.toString('hex').slice(0, 64).padEnd(64, '0');
  return `0x${hex}`;
}

function badRequest(res, message) {
  // eslint-disable-next-line no-console
  console.log('[badRequest]', message);
  return res.status(400).json({ ok: false, message });
}

function getPacketStatus(packet) {
  if (packet.status === 'refunded') return 'refunded';
  if (packet.remainingCount <= 0) return 'empty';
  if (nowSeconds() > Number(packet.expiresAt)) return 'expired';
  if (!packet.onchainCreated) return 'pending_create_confirm';
  return 'active';
}

function buildPacketResponse(packet, wallet) {
  const walletNorm = normalizeAddress(wallet);
  const status = getPacketStatus(packet);
  const ended = ['refunded', 'empty', 'expired'].includes(status);
  const hasClaimed = walletNorm ? packet.claimedWallets.includes(walletNorm) : false;

  return {
    ...packet,
    status,
    ended,
    hasClaimed,
    canClaim: !!walletNorm && !ended && packet.onchainCreated && !hasClaimed && packet.remainingCount > 0,
    canRefund: !!walletNorm
      && walletNorm === packet.creatorWallet
      && packet.onchainCreated
      && packet.remainingCount > 0
      && ['empty', 'expired'].includes(status),
    token: {
      tokenAddress: packet.tokenAddress,
      tokenSymbol: packet.tokenSymbol,
      tokenDecimals: packet.tokenDecimals,
    },
  };
}

function escapeHtml(input) {
  return String(input || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

class MySqlDB {
  constructor() {
    this.pool = mysql.createPool({
      host: MYSQL_HOST,
      port: MYSQL_PORT,
      user: MYSQL_USER,
      password: MYSQL_PASSWORD,
      database: MYSQL_DATABASE,
      waitForConnections: true,
      connectionLimit: 10,
      decimalNumbers: false,
      charset: 'utf8mb4',
    });
  }

  async getPacket(packetId) {
    const [rows] = await this.pool.query('SELECT * FROM red_packets WHERE packet_id = ? LIMIT 1', [packetId]);
    if (!rows.length) return null;
    const row = rows[0];
    const [claims] = await this.pool.query(
      'SELECT claimer_address FROM red_packet_claims WHERE packet_id = ? ORDER BY id ASC',
      [packetId],
    );
    return this.mapPacket(row, claims);
  }

  async upsertPacket(packet) {
    await this.pool.query(
      `INSERT INTO red_packets (
        packet_id, packet_id_hex, dialog_id, creator_wallet, total_amount_wei,
        amount_per_claim_wei, count_total, remaining_count, expires_at, status,
        onchain_created, create_tx_hash, token_address, token_symbol, token_decimals,
        greeting, packet_type, chain_id, contract_address, claim_url,
        legacy_claim_url, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        packet_id_hex = VALUES(packet_id_hex),
        dialog_id = VALUES(dialog_id),
        creator_wallet = VALUES(creator_wallet),
        total_amount_wei = VALUES(total_amount_wei),
        amount_per_claim_wei = VALUES(amount_per_claim_wei),
        count_total = VALUES(count_total),
        remaining_count = VALUES(remaining_count),
        expires_at = VALUES(expires_at),
        status = VALUES(status),
        onchain_created = VALUES(onchain_created),
        create_tx_hash = VALUES(create_tx_hash),
        token_address = VALUES(token_address),
        token_symbol = VALUES(token_symbol),
        token_decimals = VALUES(token_decimals),
        greeting = VALUES(greeting),
        packet_type = VALUES(packet_type),
        chain_id = VALUES(chain_id),
        contract_address = VALUES(contract_address),
        claim_url = VALUES(claim_url),
        legacy_claim_url = VALUES(legacy_claim_url),
        created_at = VALUES(created_at),
        updated_at = VALUES(updated_at)`,
      [
        packet.packetId,
        packet.packetIdHex,
        packet.dialogId,
        packet.creatorWallet,
        packet.totalAmountWei,
        packet.amountPerClaimWei,
        packet.count,
        packet.remainingCount,
        packet.expiresAt,
        packet.status,
        packet.onchainCreated ? 1 : 0,
        packet.createTxHash || null,
        packet.tokenAddress,
        packet.tokenSymbol,
        packet.tokenDecimals,
        packet.greeting,
        packet.packetType,
        packet.chainId,
        packet.contractAddress,
        packet.claimUrl,
        packet.legacyClaimUrl,
        packet.createdAt,
        packet.updatedAt,
      ],
    );

    // eslint-disable-next-line no-console
    console.log('[packet-upsert]', {
      packetId: packet.packetId,
      status: packet.status,
      remainingCount: packet.remainingCount,
      tokenSymbol: packet.tokenSymbol,
      tokenAddress: packet.tokenAddress,
    });
    return packet;
  }

  async confirmClaim(packet, claimerAddress, txHash) {
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();

      const [packetRows] = await conn.query('SELECT * FROM red_packets WHERE packet_id = ? FOR UPDATE', [packet.packetId]);
      if (!packetRows.length) throw new Error('packet not found');
      const packetRow = packetRows[0];

      const [existClaims] = await conn.query(
        'SELECT id FROM red_packet_claims WHERE packet_id = ? AND claimer_address = ? LIMIT 1',
        [packet.packetId, claimerAddress],
      );
      if (existClaims.length) throw new Error('already claimed');

      await conn.query(
        `INSERT INTO red_packet_claims (packet_id, claimer_address, tx_hash, amount_wei, created_at)
         VALUES (?, ?, ?, ?, ?)`,
        [packet.packetId, claimerAddress, txHash, packet.amountPerClaimWei, nowSeconds()],
      );

      const remainingCount = Number(packetRow.remaining_count) - 1;
      const newStatus = remainingCount <= 0 ? 'empty' : packetRow.status;
      await conn.query(
        `UPDATE red_packets
         SET remaining_count = ?, status = ?, updated_at = ?
         WHERE packet_id = ?`,
        [remainingCount, newStatus, nowSeconds(), packet.packetId],
      );

      await conn.commit();
    } catch (error) {
      await conn.rollback();
      throw error;
    } finally {
      conn.release();
    }

    return this.getPacket(packet.packetId);
  }

  async getPacketsForAdmin(limit = 100) {
    const [rows] = await this.pool.query(
      `SELECT packet_id, creator_wallet, token_symbol, total_amount_wei, count_total, remaining_count,
              status, onchain_created, expires_at, created_at
       FROM red_packets
       ORDER BY created_at DESC
       LIMIT ?`,
      [Math.min(Number(limit) || 100, 500)],
    );
    return rows;
  }

  async getAdminStats() {
    const [statsRows] = await this.pool.query(
      `SELECT
        COUNT(*) AS totalPackets,
        SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) AS activePackets,
        SUM(CASE WHEN status = 'empty' THEN 1 ELSE 0 END) AS emptyPackets,
        SUM(CASE WHEN status = 'pending_create_confirm' THEN 1 ELSE 0 END) AS pendingPackets
       FROM red_packets`,
    );
    const [claimRows] = await this.pool.query('SELECT COUNT(*) AS totalClaims FROM red_packet_claims');
    return {
      ...(statsRows[0] || {}),
      totalClaims: claimRows[0]?.totalClaims || 0,
    };
  }

  mapPacket(row, claims = []) {
    return {
      packetId: row.packet_id,
      packetIdHex: row.packet_id_hex,
      dialogId: row.dialog_id,
      creatorWallet: row.creator_wallet,
      totalAmountWei: row.total_amount_wei,
      amountPerClaimWei: row.amount_per_claim_wei,
      count: Number(row.count_total),
      remainingCount: Number(row.remaining_count),
      claimedWallets: claims.map((c) => c.claimer_address),
      expiresAt: Number(row.expires_at),
      status: row.status,
      onchainCreated: Boolean(row.onchain_created),
      createTxHash: row.create_tx_hash || '',
      tokenAddress: row.token_address,
      tokenSymbol: row.token_symbol,
      tokenDecimals: Number(row.token_decimals),
      greeting: row.greeting,
      packetType: row.packet_type,
      chainId: Number(row.chain_id),
      contractAddress: row.contract_address,
      claimUrl: row.claim_url,
      legacyClaimUrl: row.legacy_claim_url,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at),
    };
  }
}

const db = new MySqlDB();

async function ensurePacket(packetId, res) {
  const packet = await db.getPacket(packetId);
  if (!packet) {
    res.status(404).json({ ok: false, message: 'not found' });
    return null;
  }
  return packet;
}

async function getTransactionReceipt(txHash) {
  try {
    return await provider.getTransactionReceipt(txHash);
  } catch (_) {
    return null;
  }
}

function parseExpectedLog(receipt, eventName) {
  if (!receipt || !Array.isArray(receipt.logs)) return null;
  for (const log of receipt.logs) {
    if (!log || !log.address) continue;
    if (normalizeAddress(log.address) !== contractAddressNorm) continue;
    try {
      const parsed = contractInterface.parseLog(log);
      if (parsed?.name === eventName) {
        return parsed;
      }
    } catch (_) {
      // ignore non-matching logs
    }
  }
  return null;
}

app.get('/healthz', async (_, res) => {
  let rpcOk = true;
  let dbOk = true;
  try {
    await provider.getBlockNumber();
  } catch (_) {
    rpcOk = false;
  }

  try {
    await db.pool.query('SELECT 1');
  } catch (_) {
    dbOk = false;
  }

  res.json({
    ok: true,
    service: 'web3-red-packet',
    chainId: CHAIN_ID,
    contractAddress: CONTRACT_ADDRESS,
    rpcUrl: RPC_URL,
    rpcOk,
    dbOk,
    ts: nowSeconds(),
  });
});

app.get('/admin', async (_req, res) => {
  const [stats, packets] = await Promise.all([db.getAdminStats(), db.getPacketsForAdmin(200)]);

  const rows = packets.map((packet) => `<tr>
    <td><a href="/api/v1/red-packets/${escapeHtml(packet.packet_id)}">${escapeHtml(packet.packet_id)}</a></td>
    <td>${escapeHtml(packet.creator_wallet)}</td>
    <td>${escapeHtml(packet.token_symbol)}</td>
    <td>${escapeHtml(packet.total_amount_wei)}</td>
    <td>${Number(packet.count_total) - Number(packet.remaining_count)}/${packet.count_total}</td>
    <td>${escapeHtml(packet.status)}${packet.onchain_created ? '' : ' (unconfirmed)'}</td>
    <td>${new Date(Number(packet.created_at) * 1000).toISOString()}</td>
  </tr>`).join('\n');

  res.type('html').send(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>红包管理后台</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 20px; }
    .card { background:#f6f7f9; border-radius: 10px; padding: 16px; margin-bottom: 18px; }
    .stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
    .stat { background: white; border-radius: 8px; padding: 12px; border: 1px solid #eee; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border-bottom: 1px solid #eee; text-align: left; padding: 10px 8px; font-size: 14px; }
    th { background: #fafafa; }
    a { color: #0b65d8; text-decoration: none; }
  </style>
</head>
<body>
  <h1>Telegram 红包管理后台</h1>
  <div class="card stats">
    <div class="stat"><b>总红包</b><div>${stats.totalPackets || 0}</div></div>
    <div class="stat"><b>进行中</b><div>${stats.activePackets || 0}</div></div>
    <div class="stat"><b>待确认</b><div>${stats.pendingPackets || 0}</div></div>
    <div class="stat"><b>已领完</b><div>${stats.emptyPackets || 0}</div></div>
    <div class="stat"><b>总领取次数</b><div>${stats.totalClaims || 0}</div></div>
  </div>
  <div class="card">
    <h3>最近红包</h3>
    <table>
      <thead>
        <tr>
          <th>Packet ID</th><th>创建者</th><th>代币</th><th>总额(wei)</th><th>领取进度</th><th>状态</th><th>创建时间</th>
        </tr>
      </thead>
      <tbody>${rows || '<tr><td colspan="7">暂无数据</td></tr>'}</tbody>
    </table>
  </div>
</body>
</html>`);
});

app.post('/api/v1/red-packets/prepare-create', async (req, res) => {
  const {
    dialogId,
    creatorWallet,
    totalAmountWei,
    count,
    expiresAt,
    tokenAddress,
    tokenSymbol,
    tokenDecimals,
    greeting,
    packetType,
  } = req.body || {};

  const creator = normalizeAddress(creatorWallet);
  const countNum = parsePositiveInt(count);
  const totalWei = parsePositiveBigInt(totalAmountWei);
  const expiresAtNum = parsePositiveInt(expiresAt);
  const tokenSymbolClean = typeof tokenSymbol === 'string' ? tokenSymbol.trim() : '';
  const isNativeBnb = tokenSymbolClean.toUpperCase() === 'BNB';
  const tokenAddr = normalizeAddress(tokenAddress);
  const tokenDecimalsNum = Number.isInteger(Number(tokenDecimals)) && Number(tokenDecimals) >= 0
    ? Number(tokenDecimals)
    : (isNativeBnb ? 18 : null);

  if (!creator) return badRequest(res, 'creatorWallet invalid');
  if (!countNum || countNum > MAX_PACKET_COUNT) return badRequest(res, `count must be 1-${MAX_PACKET_COUNT}`);
  if (!totalWei) return badRequest(res, 'totalAmountWei invalid');
  if (!expiresAtNum || expiresAtNum <= nowSeconds()) return badRequest(res, 'expiresAt must be in the future');
  if (totalWei % BigInt(countNum) !== 0n) return badRequest(res, 'totalAmountWei must be divisible by count');
  if (!isNativeBnb && !tokenAddr) return badRequest(res, 'tokenAddress invalid');
  if (!tokenSymbolClean) return badRequest(res, 'tokenSymbol invalid');
  if (tokenDecimalsNum === null) return badRequest(res, 'tokenDecimals invalid');

  const packetId = `tg-${Date.now()}-${crypto.randomBytes(3).toString('hex')}`;
  const amountPerClaimWei = totalWei / BigInt(countNum);
  const createdAt = nowSeconds();

  const packet = {
    packetId,
    packetIdHex: packetIdToHex(packetId),
    dialogId: dialogId || '',
    creatorWallet: creator,
    totalAmountWei: totalWei.toString(),
    amountPerClaimWei: amountPerClaimWei.toString(),
    count: countNum,
    remainingCount: countNum,
    claimedWallets: [],
    expiresAt: expiresAtNum,
    status: 'pending_create_confirm',
    onchainCreated: false,
    tokenAddress: tokenAddr || '0x0000000000000000000000000000000000000000',
    tokenSymbol: tokenSymbolClean,
    tokenDecimals: tokenDecimalsNum,
    greeting: typeof greeting === 'string' ? greeting : '',
    packetType: typeof packetType === 'string' ? packetType : '',
    chainId: CHAIN_ID,
    contractAddress: CONTRACT_ADDRESS,
    claimUrl: `${HOST}/claim/${packetId}`,
    legacyClaimUrl: `${HOST}/claim/${packetId}`,
    createdAt,
    updatedAt: createdAt,
  };

  await db.upsertPacket(packet);

  return res.json({
    ok: true,
    data: {
      packetId: packet.packetId,
      packetIdHex: packet.packetIdHex,
      contractAddress: packet.contractAddress,
      chainId: packet.chainId,
      expiresAt: packet.expiresAt,
      totalAmountWei: packet.totalAmountWei,
      tokenAddress: packet.tokenAddress,
      tokenSymbol: packet.tokenSymbol,
      tokenDecimals: packet.tokenDecimals,
      count: packet.count,
      greeting: packet.greeting,
      packetType: packet.packetType,
      claimUrl: packet.legacyClaimUrl,
      openClaimInBrowser: false,
    },
  });
});

app.post('/api/v1/red-packets/:packetId/create-confirm', async (req, res) => {
  const packet = await ensurePacket(req.params.packetId, res);
  if (!packet) return;

  const txHash = String(req.body?.txHash || '').trim();
  if (!/^0x[0-9a-fA-F]{64}$/.test(txHash)) return badRequest(res, 'txHash invalid');

  const receipt = await getTransactionReceipt(txHash);
  if (!receipt || receipt.status !== 1) return badRequest(res, 'transaction not confirmed');

  const event = parseExpectedLog(receipt, 'PacketCreated');
  if (!event) return badRequest(res, 'PacketCreated event not found');

  const eventPacketIdHex = String(event.args.packetId).toLowerCase();
  const eventCreator = normalizeAddress(String(event.args.creator));
  const eventTotalRaw = event.args.total ?? event.args[3];
  const eventCountRaw = event.args.count ?? event.args[4];
  const eventExpiresAtRaw = event.args.expiresAt ?? event.args[5];
  const eventTotal = BigInt(eventTotalRaw).toString();
  const eventCount = Number(eventCountRaw);
  const eventExpiresAt = Number(eventExpiresAtRaw);

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'PacketCreated packetId mismatch');
  if (eventCreator !== packet.creatorWallet) return badRequest(res, 'PacketCreated creator mismatch');
  if (eventTotal !== packet.totalAmountWei) return badRequest(res, 'PacketCreated total mismatch');
  if (eventCount !== packet.count) return badRequest(res, 'PacketCreated count mismatch');
  if (eventExpiresAt !== Number(packet.expiresAt)) return badRequest(res, 'PacketCreated expiresAt mismatch');

  packet.onchainCreated = true;
  packet.status = 'active';
  packet.createTxHash = txHash;
  packet.updatedAt = nowSeconds();
  await db.upsertPacket(packet);
  // eslint-disable-next-line no-console
  console.log('[create-confirmed]', { packetId: packet.packetId, txHash });

  return res.json({
    ok: true,
    data: {
      packetId: packet.packetId,
      txHash,
      status: packet.status,
      onchainCreated: packet.onchainCreated,
    },
  });
});

app.get('/api/v1/red-packets/:packetId', async (req, res) => {
  const packet = await ensurePacket(req.params.packetId, res);
  if (!packet) return;
  return res.json({ ok: true, data: buildPacketResponse(packet, req.query.wallet) });
});

app.post('/api/v1/red-packets/:packetId/claim/prepare', async (req, res) => {
  const packet = await ensurePacket(req.params.packetId, res);
  if (!packet) return;

  const claimerAddress = normalizeAddress(req.body?.claimerAddress);
  if (!claimerAddress) return badRequest(res, 'claimerAddress invalid');

  const status = getPacketStatus(packet);
  if (status === 'expired') return badRequest(res, 'packet expired');
  if (status === 'empty') return badRequest(res, 'packet empty');
  if (!packet.onchainCreated) return badRequest(res, 'packet not confirmed on chain');
  if (packet.claimedWallets.includes(claimerAddress)) return badRequest(res, 'already claimed');

  packet.updatedAt = nowSeconds();
  await db.upsertPacket(packet);
  // eslint-disable-next-line no-console
  console.log('[claim-prepare]', {
    packetId: packet.packetId,
    claimerAddress,
    remainingCount: packet.remainingCount,
  });

  return res.json({
    ok: true,
    data: {
      packetIdHex: packet.packetIdHex,
      contractAddress: packet.contractAddress,
      chainId: packet.chainId,
      claimerAddress,
      amountPerClaimWei: packet.amountPerClaimWei,
    },
  });
});

app.post('/api/v1/red-packets/:packetId/claim-confirm', async (req, res) => {
  const packet = await ensurePacket(req.params.packetId, res);
  if (!packet) return;

  const claimerAddress = normalizeAddress(req.body?.claimerAddress);
  const txHash = String(req.body?.txHash || '').trim();
  if (!claimerAddress) return badRequest(res, 'claimerAddress invalid');
  if (!/^0x[0-9a-fA-F]{64}$/.test(txHash)) return badRequest(res, 'txHash invalid');

  if (packet.claimedWallets.includes(claimerAddress)) return badRequest(res, 'already claimed');
  if (!packet.onchainCreated) return badRequest(res, 'packet not confirmed on chain');

  const receipt = await getTransactionReceipt(txHash);
  if (!receipt || receipt.status !== 1) return badRequest(res, 'transaction not confirmed');

  const event = parseExpectedLog(receipt, 'Claimed');
  if (!event) return badRequest(res, 'Claimed event not found');

  const eventPacketIdHex = String(event.args.packetId).toLowerCase();
  const eventClaimer = normalizeAddress(String(event.args.claimer));
  const eventAmountRaw = event.args.amount ?? event.args[3];
  const eventAmount = BigInt(eventAmountRaw).toString();

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'Claimed packetId mismatch');
  if (eventClaimer !== claimerAddress) return badRequest(res, 'Claimed claimer mismatch');
  if (eventAmount !== packet.amountPerClaimWei) return badRequest(res, 'Claimed amount mismatch');

  let updated;
  try {
    updated = await db.confirmClaim(packet, claimerAddress, txHash);
  } catch (error) {
    if (String(error.message).includes('already claimed')) {
      return badRequest(res, 'already claimed');
    }
    throw error;
  }

  // eslint-disable-next-line no-console
  console.log('[claim-confirmed]', {
    packetId: packet.packetId,
    claimerAddress,
    txHash,
    remainingCount: updated.remainingCount,
  });

  return res.json({
    ok: true,
    data: {
      packetId: updated.packetId,
      txHash,
      remainingCount: updated.remainingCount,
      status: getPacketStatus(updated),
    },
  });
});

app.use((err, _req, res, _next) => {
  // eslint-disable-next-line no-console
  console.error('[server-error]', err);
  res.status(500).json({ ok: false, message: 'internal error' });
});

const port = Number(process.env.PORT || 8787);

(async () => {
  app.listen(port, () => {
    // eslint-disable-next-line no-console
    console.log(`red-packet service listening on http://127.0.0.1:${port}`);
    // eslint-disable-next-line no-console
    console.log(`admin console available at http://127.0.0.1:${port}/admin`);
  });
})();
