/**
 * Red-packet service for Telegram wallet integration.
 * Local run:
 *   npm i
 *   npm run dev
 */
require('dotenv').config();

const crypto = require('crypto');
const express = require('express');
const fs = require('fs');
const path = require('path');
const mysql = require('mysql2/promise');
const { JsonRpcProvider, Interface, getAddress, isAddress } = require('ethers');

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', normalizeTrustProxy(process.env.TRUST_PROXY));
app.use(express.json({ limit: '256kb' }));
app.use((req, _res, next) => {
  // eslint-disable-next-line no-console
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.originalUrl}`, sanitizeForLog(req.body || {}));
  next();
});

const CHAIN_ID = Number(process.env.CHAIN_ID || 97);
const CONTRACT_ADDRESS = (process.env.RED_PACKET_CONTRACT || '0x5a6361A5Af1c56eDF7E6e9e0B191a92BBf957fC3').trim();
const HOST = process.env.PUBLIC_HOST || 'http://127.0.0.1:8787';
const MAX_PACKET_COUNT = 500;
const RPC_URL = process.env.RPC_URL || 'https://data-seed-prebsc-1-s1.bnbchain.org:8545';
const DEFAULT_PROXY_ADDRESS = (process.env.DEFAULT_PROXY_ADDRESS || '139.180.223.206').trim();
const DEFAULT_PROXY_PORT = Number(process.env.DEFAULT_PROXY_PORT || 443);
const DEFAULT_PROXY_USERNAME = process.env.DEFAULT_PROXY_USERNAME || '';
const DEFAULT_PROXY_PASSWORD = process.env.DEFAULT_PROXY_PASSWORD || '';
const DEFAULT_PROXY_SECRET = process.env.DEFAULT_PROXY_SECRET || 'aff4456037ec453cde85935760a840f0';
const APP_VERSION_CODE = Number(process.env.APP_VERSION_CODE || 1);
const APP_VERSION_NAME = process.env.APP_VERSION_NAME || '1.0.0';
const APP_DOWNLOAD_URL = process.env.APP_DOWNLOAD_URL || '';
const APP_VERSION_MESSAGE = process.env.APP_VERSION_MESSAGE || '';
const APP_RELEASE_DATE = Number(process.env.APP_RELEASE_DATE || 0);
const APP_APK_SIZE_BYTES = Number(process.env.APP_APK_SIZE_BYTES || 0);
const DEFAULT_WALLET_TOKENS = [
  { symbol: 'ETZ', contractAddress: '0xc78dabf21594c76ad98a0b3ed103fcfcd9499999', decimals: 18 },
  { symbol: 'Piao', contractAddress: '0x68973e906a64b283ac90eb88cd561ba6c6681103', decimals: 18 },
  { symbol: 'Tea', contractAddress: '0x3142Db225d0262973715606c85B2B50a66f9b00C', decimals: 18 },
  { symbol: 'Dimei', contractAddress: '0xb299d5bdf3c17d14aafb305f97b16c5aa0999921', decimals: 18 },
  { symbol: 'Mu', contractAddress: '0x7677421f49776addcfc18cb851df0c24d02d8888', decimals: 18 },
  { symbol: 'Goods', contractAddress: '0x80B75C9c6773D255c32ADA8E971c0C4ba03088d0', decimals: 18 },
];

const MYSQL_HOST = process.env.MYSQL_HOST || '127.0.0.1';
const MYSQL_PORT = Number(process.env.MYSQL_PORT || 3306);
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PASSWORD = process.env.MYSQL_PASSWORD || '';
const MYSQL_DATABASE = process.env.MYSQL_DATABASE || 'telegram_red_packet';


const SERVER_STARTED_AT = nowSeconds();
const ADMIN_BASE_PATH = normalizeAdminBasePath(process.env.ADMIN_API_BASE_PATH || '/api/admin');
const ADMIN_WEB_BASE_PATH = normalizeAdminBasePath(process.env.ADMIN_WEB_BASE_PATH || '/admin');
const ADMIN_WEB_DIST = process.env.ADMIN_WEB_DIST
  ? path.resolve(process.env.ADMIN_WEB_DIST)
  : path.join(__dirname, 'admin-web', 'dist');
const ADMIN_CORS_ORIGIN = String(process.env.ADMIN_CORS_ORIGIN || '').trim();
const ADMIN_COOKIE_NAME = process.env.ADMIN_COOKIE_NAME || 'rp_admin_session';
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin';
const ADMIN_PASSWORD = (process.env.ADMIN_PASSWORD || process.env.ADMIN_TOKEN || '').trim();
const ADMIN_BEARER_TOKEN = (process.env.ADMIN_TOKEN || '').trim();
const ADMIN_SESSION_TTL_SECONDS = Math.max(Number(process.env.ADMIN_SESSION_TTL_SECONDS || (24 * 60 * 60)), 300);
const ADMIN_SESSION_SECRET = process.env.ADMIN_SESSION_SECRET
  || process.env.ADMIN_TOKEN
  || crypto.createHash('sha256')
    .update(`${ADMIN_USERNAME}:${ADMIN_PASSWORD}:${MYSQL_HOST}:${MYSQL_DATABASE}`)
    .digest('hex');

const provider = new JsonRpcProvider(RPC_URL, CHAIN_ID);
const contractAddressNorm = normalizeAddress(CONTRACT_ADDRESS);

const contractInterface = new Interface([
  'event PacketCreated(bytes32 indexed packetId, address indexed creator, uint256 total, uint32 count, uint64 expiresAt)',
  'event PacketCreated(bytes32 indexed packetId, address indexed creator, address indexed token, uint256 total, uint32 count, uint64 expiresAt)',
  'event Claimed(bytes32 indexed packetId, address indexed claimer, uint256 amount)',
  'event Claimed(bytes32 indexed packetId, address indexed claimer, address indexed token, uint256 amount)',
  'event Refunded(bytes32 indexed packetId, address indexed creator, uint256 amount)',
  'event Refunded(bytes32 indexed packetId, address indexed creator, address indexed token, uint256 amount)',
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
    .replaceAll('\"', '&quot;')
    .replaceAll("'", '&#39;');
}

function normalizeTrustProxy(value) {
  const raw = String(value ?? '').trim().toLowerCase();
  if (!raw || raw === 'true') return 1;
  if (raw === 'false' || raw === '0') return false;
  const numeric = Number(raw);
  if (Number.isInteger(numeric) && numeric >= 0) return numeric;
  return value;
}

function sanitizeForLog(value) {
  const secretKeys = new Set([
    'password', 'passwd', 'pwd', 'secret', 'token', 'authorization', 'auth',
    'adminpassword', 'admintoken', 'privatekey', 'mnemonic',
  ]);

  if (Array.isArray(value)) return value.map((item) => sanitizeForLog(item));
  if (!value || typeof value !== 'object') return value;

  return Object.entries(value).reduce((acc, [key, item]) => {
    const normalizedKey = String(key).replace(/[-_]/g, '').toLowerCase();
    acc[key] = secretKeys.has(normalizedKey) ? '[redacted]' : sanitizeForLog(item);
    return acc;
  }, {});
}

function normalizeAdminBasePath(value) {
  const raw = String(value || '/admin-console').trim();
  const withSlash = raw.startsWith('/') ? raw : `/${raw}`;
  const normalized = withSlash.replace(/\/+$/, '');
  return normalized || '/admin-console';
}

function isAdminAuthConfigured() {
  return ADMIN_PASSWORD.length > 0 || ADMIN_BEARER_TOKEN.length > 0;
}

function parseCookies(header) {
  return String(header || '')
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .reduce((acc, part) => {
      const index = part.indexOf('=');
      if (index <= 0) return acc;
      const key = decodeURIComponent(part.slice(0, index));
      const value = decodeURIComponent(part.slice(index + 1));
      acc[key] = value;
      return acc;
    }, {});
}

function timingSafeEqualString(a, b) {
  const left = Buffer.from(String(a || ''));
  const right = Buffer.from(String(b || ''));
  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

function signAdminSession(payload) {
  const data = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signature = crypto.createHmac('sha256', ADMIN_SESSION_SECRET).update(data).digest('base64url');
  return `${data}.${signature}`;
}

function verifyAdminSession(value) {
  const token = String(value || '');
  const [data, signature] = token.split('.');
  if (!data || !signature) return null;
  const expected = crypto.createHmac('sha256', ADMIN_SESSION_SECRET).update(data).digest('base64url');
  if (!timingSafeEqualString(signature, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(data, 'base64url').toString('utf8'));
    if (!payload || payload.user !== ADMIN_USERNAME) return null;
    if (!payload.exp || Number(payload.exp) < nowSeconds()) return null;
    return payload;
  } catch (_) {
    return null;
  }
}

function getAdminBearerToken(req) {
  const authorization = String(req.get('authorization') || '').trim();
  if (!authorization.toLowerCase().startsWith('bearer ')) return '';
  return authorization.slice(7).trim();
}

function isSecureRequest(req) {
  return Boolean(req.secure || String(req.get('x-forwarded-proto') || '').split(',')[0].trim() === 'https');
}

function setAdminSessionCookie(req, res) {
  const expiresAt = nowSeconds() + ADMIN_SESSION_TTL_SECONDS;
  const session = signAdminSession({ user: ADMIN_USERNAME, exp: expiresAt, iat: nowSeconds() });
  const secure = isSecureRequest(req) ? '; Secure' : '';
  res.setHeader(
    'Set-Cookie',
    `${ADMIN_COOKIE_NAME}=${encodeURIComponent(session)}; Path=${ADMIN_BASE_PATH}; Max-Age=${ADMIN_SESSION_TTL_SECONDS}; HttpOnly; SameSite=Lax${secure}`,
  );
}

function clearAdminSessionCookie(req, res) {
  const secure = isSecureRequest(req) ? '; Secure' : '';
  res.setHeader(
    'Set-Cookie',
    `${ADMIN_COOKIE_NAME}=; Path=${ADMIN_BASE_PATH}; Max-Age=0; HttpOnly; SameSite=Lax${secure}`,
  );
}

function adminRequireAuth(req, res, next) {
  res.set('Cache-Control', 'no-store');

  if (!isAdminAuthConfigured()) {
    return res.status(503).json({ ok: false, message: 'admin auth is not configured; set ADMIN_PASSWORD or ADMIN_TOKEN' });
  }

  const bearer = getAdminBearerToken(req);
  if (ADMIN_BEARER_TOKEN && timingSafeEqualString(bearer, ADMIN_BEARER_TOKEN)) {
    req.adminUser = ADMIN_USERNAME;
    return next();
  }

  const cookies = parseCookies(req.get('cookie'));
  const session = verifyAdminSession(cookies[ADMIN_COOKIE_NAME]);
  if (session) {
    req.adminUser = session.user;
    return next();
  }

  return res.status(401).json({ ok: false, message: 'admin auth required' });
}

function parseAdminPageParams(query, maxPageSize = 200) {
  const page = Math.max(parsePositiveInt(query.page) || 1, 1);
  const pageSizeRaw = parsePositiveInt(query.pageSize) || 20;
  const pageSize = Math.min(Math.max(pageSizeRaw, 1), maxPageSize);
  return { page, pageSize, offset: (page - 1) * pageSize };
}

function parseAdminDateSeconds(value, endOfDay = false) {
  const raw = String(value || '').trim();
  if (!raw) return null;
  if (/^\d{10}$/.test(raw)) return Number(raw);
  if (/^\d{13}$/.test(raw)) return Math.floor(Number(raw) / 1000);
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(raw);
  const date = new Date(dateOnly ? `${raw}T${endOfDay ? '23:59:59' : '00:00:00'}Z` : raw);
  const seconds = Math.floor(date.getTime() / 1000);
  return Number.isFinite(seconds) ? seconds : null;
}

function normalizeAdminStatus(value) {
  const status = String(value || '').trim().toLowerCase();
  if (['active', 'pending_create_confirm', 'empty', 'expired', 'refunded'].includes(status)) return status;
  return '';
}

function csvEscape(value) {
  const text = String(value ?? '');
  if (/[",\n\r]/.test(text)) return `"${text.replace(/"/g, '""')}"`;
  return text;
}

function sendCsv(res, filename, headers, rows) {
  const lines = [headers.map(csvEscape).join(',')];
  for (const row of rows) {
    lines.push(headers.map((header) => csvEscape(row[header])).join(','));
  }
  res.setHeader('Content-Type', 'text/csv; charset=utf-8');
  res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
  return res.send(`\uFEFF${lines.join('\n')}`);
}

function maskSecret(value, visible = 4) {
  const text = String(value || '');
  if (!text) return '';
  if (text.length <= visible * 2) return '***';
  return `${text.slice(0, visible)}...${text.slice(-visible)}`;
}

function maskSensitiveUrl(value) {
  const text = String(value || '');
  try {
    const url = new URL(text);
    if (url.username) url.username = '***';
    if (url.password) url.password = '***';
    if (url.search) url.search = '?***';
    return url.toString();
  } catch (_) {
    return maskSecret(text, 10);
  }
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


  async ensureSchema() {
    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS red_packets (
        packet_id VARCHAR(128) NOT NULL,
        packet_id_hex VARCHAR(80) NOT NULL,
        dialog_id VARCHAR(128) NOT NULL DEFAULT '',
        creator_wallet VARCHAR(64) NOT NULL,
        total_amount_wei VARCHAR(120) NOT NULL,
        amount_per_claim_wei VARCHAR(120) NOT NULL,
        count_total INT UNSIGNED NOT NULL,
        remaining_count INT UNSIGNED NOT NULL,
        expires_at BIGINT NOT NULL,
        status VARCHAR(32) NOT NULL,
        onchain_created TINYINT(1) NOT NULL DEFAULT 0,
        create_tx_hash VARCHAR(100) NULL,
        token_address VARCHAR(64) NOT NULL,
        token_symbol VARCHAR(32) NOT NULL,
        token_decimals INT UNSIGNED NOT NULL DEFAULT 18,
        greeting VARCHAR(512) NOT NULL DEFAULT '',
        packet_type VARCHAR(64) NOT NULL DEFAULT '',
        chain_id INT NOT NULL,
        contract_address VARCHAR(64) NOT NULL,
        claim_url VARCHAR(1024) NOT NULL DEFAULT '',
        legacy_claim_url VARCHAR(1024) NOT NULL DEFAULT '',
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (packet_id),
        KEY idx_creator_created (creator_wallet, created_at),
        KEY idx_status_created (status, created_at),
        KEY idx_token_created (token_symbol, created_at),
        KEY idx_expires_at (expires_at),
        KEY idx_create_tx_hash (create_tx_hash)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS red_packet_claims (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        packet_id VARCHAR(128) NOT NULL,
        claimer_address VARCHAR(64) NOT NULL,
        claimer_name VARCHAR(255) NOT NULL DEFAULT '',
        tx_hash VARCHAR(100) NOT NULL,
        amount_wei VARCHAR(120) NOT NULL,
        created_at BIGINT NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uniq_packet_claimer (packet_id, claimer_address),
        UNIQUE KEY uniq_packet_claim_tx (packet_id, tx_hash),
        KEY idx_packet_created (packet_id, created_at),
        KEY idx_claimer_created (claimer_address, created_at),
        KEY idx_claim_tx_hash (tx_hash)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
    await this.pool.query(`
      ALTER TABLE red_packet_claims
      ADD COLUMN IF NOT EXISTS claimer_name VARCHAR(255) NOT NULL DEFAULT '' AFTER claimer_address
    `);

    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS red_packet_refunds (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        packet_id VARCHAR(128) NOT NULL,
        creator_address VARCHAR(64) NOT NULL,
        tx_hash VARCHAR(100) NOT NULL,
        amount_wei VARCHAR(120) NOT NULL,
        created_at BIGINT NOT NULL,
        PRIMARY KEY (id),
        KEY idx_packet_created (packet_id, created_at),
        KEY idx_creator_created (creator_address, created_at),
        KEY idx_refund_tx_hash (tx_hash),
        UNIQUE KEY uniq_packet_tx (packet_id, tx_hash)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
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

  async confirmClaim(packet, claimerAddress, txHash, claimerName = '') {
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
        `INSERT INTO red_packet_claims (packet_id, claimer_address, claimer_name, tx_hash, amount_wei, created_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
        [packet.packetId, claimerAddress, claimerName, txHash, packet.amountPerClaimWei, nowSeconds()],
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


  async confirmRefund(packet, creatorAddress, txHash, amountWei) {
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();

      const [packetRows] = await conn.query('SELECT * FROM red_packets WHERE packet_id = ? FOR UPDATE', [packet.packetId]);
      if (!packetRows.length) throw new Error('packet not found');
      const packetRow = packetRows[0];

      if (String(packetRow.status) === 'refunded') {
        throw new Error('already refunded');
      }

      await conn.query(
        `INSERT INTO red_packet_refunds (packet_id, creator_address, tx_hash, amount_wei, created_at)
         VALUES (?, ?, ?, ?, ?)`,
        [packet.packetId, creatorAddress, txHash, amountWei, nowSeconds()],
      );

      await conn.query(
        `UPDATE red_packets
         SET status = 'refunded', remaining_count = 0, updated_at = ?
         WHERE packet_id = ?`,
        [nowSeconds(), packet.packetId],
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
      `SELECT packet_id, creator_wallet, token_symbol, total_amount_wei, count_total, remaining_count, greeting,
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

  async getSendRecordsByCreator(creatorWallet, limit = 100) {
    const normalized = normalizeAddress(creatorWallet);
    if (!normalized) {
      return [];
    }
    const safeLimit = Math.min(Math.max(Number(limit) || 20, 1), 200);
    const [rows] = await this.pool.query(
      `SELECT packet_id, token_symbol, total_amount_wei, count_total, status, created_at, create_tx_hash, greeting
       FROM red_packets
       WHERE creator_wallet = ? AND status <> 'pending_create_confirm'
       ORDER BY created_at DESC
       LIMIT ?`,
      [normalized, safeLimit],
    );
    return rows.map((row) => ({
      packetId: row.packet_id,
      tokenSymbol: row.token_symbol || 'BNB',
      totalAmount: row.total_amount_wei,
      count: Number(row.count_total || 0),
      status: String(row.status || '').toUpperCase(),
      createdAt: Number(row.created_at || 0) * 1000,
      txHash: row.create_tx_hash || '',
      greeting: row.greeting || '',
    }));
  }

  async getSendRecordDetail(packetId) {
    const [rows] = await this.pool.query(
      `SELECT packet_id, packet_id_hex, token_symbol, total_amount_wei, count_total, remaining_count, amount_per_claim_wei, status, created_at, create_tx_hash, greeting, expires_at, contract_address, creator_wallet
       FROM red_packets WHERE packet_id = ? OR packet_id_hex = ? LIMIT 1`,
      [packetId, packetId],
    );
    if (!rows.length) return null;
    const row = rows[0];
    const [claims] = await this.pool.query(
      `SELECT claimer_address, claimer_name, tx_hash, amount_wei, created_at FROM red_packet_claims
       WHERE packet_id = ? ORDER BY id ASC`,
      [row.packet_id],
    );
    const [refunds] = await this.pool.query(
      `SELECT id, creator_address, tx_hash, amount_wei, created_at FROM red_packet_refunds
       WHERE packet_id = ? ORDER BY id ASC`,
      [row.packet_id],
    );
    const remainingCount = Number(row.remaining_count || 0);
    const amountPerClaimWei = BigInt(String(row.amount_per_claim_wei || '0'));
    const remainingAmountWei = (amountPerClaimWei * BigInt(Math.max(remainingCount, 0))).toString();
    return {
      packetId: row.packet_id,
      tokenSymbol: row.token_symbol || 'BNB',
      totalAmount: row.total_amount_wei,
      count: Number(row.count_total || 0),
      status: String(row.status || '').toUpperCase(),
      packetIdHex: row.packet_id_hex || '',
      contractAddress: row.contract_address || '',
      creatorWallet: row.creator_wallet || '',
      expiresAt: Number(row.expires_at || 0),
      refunded: String(row.status || '') === 'refunded',
      canRefund: String(row.status || '') !== 'refunded' && remainingCount > 0 && nowSeconds() > Number(row.expires_at || 0),
      remainingAmountWei,
      createdAt: Number(row.created_at || 0) * 1000,
      txHash: row.create_tx_hash || '',
      greeting: row.greeting || '',
      claimRecords: claims.map((c) => ({
        claimerName: c.claimer_name || c.claimer_address,
        claimerAddress: c.claimer_address,
        claimedAt: Number(c.created_at || 0) * 1000,
        amountWei: String(c.amount_wei || '0'),
        txHash: c.tx_hash || '',
      })),
      refundRecords: refunds.map((r) => ({
        refundId: `refund-${r.id}`,
        amountWei: String(r.amount_wei || '0'),
        amountDisplay: '',
        canRefund: false,
        refunded: true,
        packetIdHex: row.packet_id_hex || '',
        contractAddress: row.contract_address || '',
        status: 'REFUNDED',
        txHash: r.tx_hash || '',
        creatorAddress: r.creator_address || '',
        createdAt: Number(r.created_at || 0) * 1000,
      })),
    };
  }


  buildAdminPacketWhere(options = {}) {
    const where = [];
    const params = [];
    const current = nowSeconds();

    const search = String(options.search || '').trim();
    if (search) {
      const term = `%${search}%`;
      where.push(`(
        p.packet_id LIKE ? OR p.creator_wallet LIKE ? OR p.token_symbol LIKE ? OR
        p.greeting LIKE ? OR p.create_tx_hash LIKE ? OR p.token_address LIKE ?
      )`);
      params.push(term, term, term, term, term, term);
    }

    const creatorWallet = normalizeAddress(options.creatorWallet || '');
    if (creatorWallet) {
      where.push('p.creator_wallet = ?');
      params.push(creatorWallet);
    }

    const tokenSymbol = String(options.tokenSymbol || '').trim();
    if (tokenSymbol) {
      where.push('p.token_symbol = ?');
      params.push(tokenSymbol);
    }

    if (Number.isInteger(options.createdFrom)) {
      where.push('p.created_at >= ?');
      params.push(options.createdFrom);
    }
    if (Number.isInteger(options.createdTo)) {
      where.push('p.created_at <= ?');
      params.push(options.createdTo);
    }

    const status = normalizeAdminStatus(options.status);
    if (status === 'refunded') {
      where.push("p.status = 'refunded'");
    } else if (status === 'empty') {
      where.push("p.status <> 'refunded' AND p.remaining_count <= 0");
    } else if (status === 'expired') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at <= ?");
      params.push(current);
    } else if (status === 'pending_create_confirm') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at > ? AND p.onchain_created = 0");
      params.push(current);
    } else if (status === 'active') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at > ? AND p.onchain_created = 1");
      params.push(current);
    }

    return {
      whereSql: where.length ? `WHERE ${where.join(' AND ')}` : '',
      params,
    };
  }

  async getAdminDashboardStats() {
    const current = nowSeconds();
    const [statsRows] = await this.pool.query(
      `SELECT
        COUNT(*) AS totalPackets,
        SUM(CASE WHEN status = 'refunded' THEN 1 ELSE 0 END) AS refundedPackets,
        SUM(CASE WHEN status <> 'refunded' AND remaining_count <= 0 THEN 1 ELSE 0 END) AS emptyPackets,
        SUM(CASE WHEN status <> 'refunded' AND remaining_count > 0 AND expires_at <= ? THEN 1 ELSE 0 END) AS expiredPackets,
        SUM(CASE WHEN status <> 'refunded' AND remaining_count > 0 AND expires_at > ? AND onchain_created = 0 THEN 1 ELSE 0 END) AS pendingPackets,
        SUM(CASE WHEN status <> 'refunded' AND remaining_count > 0 AND expires_at > ? AND onchain_created = 1 THEN 1 ELSE 0 END) AS activePackets,
        COALESCE(SUM(count_total), 0) AS totalSlots,
        COALESCE(SUM(count_total - remaining_count), 0) AS claimedSlots,
        COALESCE(SUM(CAST(total_amount_wei AS DECIMAL(65, 0))), 0) AS totalAmountWei
       FROM red_packets`,
      [current, current, current],
    );

    const [claimRows] = await this.pool.query(
      `SELECT
        COUNT(*) AS totalClaims,
        COALESCE(SUM(CAST(amount_wei AS DECIMAL(65, 0))), 0) AS totalClaimAmountWei
       FROM red_packet_claims`,
    );

    const [refundRows] = await this.pool.query(
      `SELECT
        COUNT(*) AS totalRefunds,
        COALESCE(SUM(CAST(amount_wei AS DECIMAL(65, 0))), 0) AS totalRefundAmountWei
       FROM red_packet_refunds`,
    );

    const [tokenRows] = await this.pool.query(
      `SELECT
        token_symbol AS tokenSymbol,
        token_address AS tokenAddress,
        token_decimals AS tokenDecimals,
        COUNT(*) AS packets,
        COALESCE(SUM(CAST(total_amount_wei AS DECIMAL(65, 0))), 0) AS totalAmountWei,
        COALESCE(SUM(count_total - remaining_count), 0) AS claimedSlots
       FROM red_packets
       GROUP BY token_symbol, token_address, token_decimals
       ORDER BY packets DESC
       LIMIT 10`,
    );

    const since = current - (13 * 24 * 60 * 60);
    const [dailyRows] = await this.pool.query(
      `SELECT day, SUM(packets) AS packets, SUM(claims) AS claims
       FROM (
        SELECT FROM_UNIXTIME(created_at, '%Y-%m-%d') AS day, COUNT(*) AS packets, 0 AS claims
        FROM red_packets
        WHERE created_at >= ?
        GROUP BY day
        UNION ALL
        SELECT FROM_UNIXTIME(created_at, '%Y-%m-%d') AS day, 0 AS packets, COUNT(*) AS claims
        FROM red_packet_claims
        WHERE created_at >= ?
        GROUP BY day
       ) activity
       GROUP BY day
       ORDER BY day ASC`,
      [since, since],
    );

    const [topCreatorRows] = await this.pool.query(
      `SELECT
        creator_wallet AS creatorWallet,
        COUNT(*) AS packets,
        COALESCE(SUM(count_total - remaining_count), 0) AS claimedSlots,
        MAX(created_at) AS lastCreatedAt
       FROM red_packets
       GROUP BY creator_wallet
       ORDER BY packets DESC, lastCreatedAt DESC
       LIMIT 10`,
    );

    return {
      stats: statsRows[0] || {},
      claims: claimRows[0] || {},
      refunds: refundRows[0] || {},
      tokens: tokenRows,
      daily: dailyRows,
      topCreators: topCreatorRows,
    };
  }

  async getAdminPacketPage(options = {}) {
    const page = Math.max(Number(options.page) || 1, 1);
    const safeLimit = Math.min(Math.max(Number(options.pageSize) || 20, 1), 500);
    const safeOffset = Math.max(Number(options.offset) || ((page - 1) * safeLimit), 0);
    const current = nowSeconds();
    const { whereSql, params } = this.buildAdminPacketWhere(options);

    const [countRows] = await this.pool.query(
      `SELECT COUNT(*) AS total
       FROM red_packets p
       ${whereSql}`,
      params,
    );

    const [rows] = await this.pool.query(
      `SELECT
        p.packet_id, p.packet_id_hex, p.dialog_id, p.creator_wallet, p.total_amount_wei,
        p.amount_per_claim_wei, p.count_total, p.remaining_count, p.expires_at, p.status,
        CASE
          WHEN p.status = 'refunded' THEN 'refunded'
          WHEN p.remaining_count <= 0 THEN 'empty'
          WHEN p.expires_at <= ? THEN 'expired'
          WHEN p.onchain_created = 0 THEN 'pending_create_confirm'
          ELSE 'active'
        END AS runtime_status,
        p.onchain_created, p.create_tx_hash, p.token_address, p.token_symbol, p.token_decimals,
        p.greeting, p.packet_type, p.chain_id, p.contract_address, p.claim_url, p.legacy_claim_url,
        p.created_at, p.updated_at,
        COALESCE(c.claim_count, 0) AS claim_count,
        COALESCE(r.refund_count, 0) AS refund_count
       FROM red_packets p
       LEFT JOIN (
        SELECT packet_id, COUNT(*) AS claim_count
        FROM red_packet_claims
        GROUP BY packet_id
       ) c ON c.packet_id = p.packet_id
       LEFT JOIN (
        SELECT packet_id, COUNT(*) AS refund_count
        FROM red_packet_refunds
        GROUP BY packet_id
       ) r ON r.packet_id = p.packet_id
       ${whereSql}
       ORDER BY p.created_at DESC, p.packet_id DESC
       LIMIT ? OFFSET ?`,
      [current, ...params, safeLimit, safeOffset],
    );

    return {
      rows,
      page,
      pageSize: safeLimit,
      total: Number(countRows[0]?.total || 0),
    };
  }

  async getAdminPacketDetail(packetId) {
    const current = nowSeconds();
    const [rows] = await this.pool.query(
      `SELECT
        p.*,
        CASE
          WHEN p.status = 'refunded' THEN 'refunded'
          WHEN p.remaining_count <= 0 THEN 'empty'
          WHEN p.expires_at <= ? THEN 'expired'
          WHEN p.onchain_created = 0 THEN 'pending_create_confirm'
          ELSE 'active'
        END AS runtime_status,
        COALESCE(c.claim_count, 0) AS claim_count,
        COALESCE(r.refund_count, 0) AS refund_count
       FROM red_packets p
       LEFT JOIN (
        SELECT packet_id, COUNT(*) AS claim_count
        FROM red_packet_claims
        GROUP BY packet_id
       ) c ON c.packet_id = p.packet_id
       LEFT JOIN (
        SELECT packet_id, COUNT(*) AS refund_count
        FROM red_packet_refunds
        GROUP BY packet_id
       ) r ON r.packet_id = p.packet_id
       WHERE p.packet_id = ?
       LIMIT 1`,
      [current, packetId],
    );
    if (!rows.length) return null;

    const [claims] = await this.pool.query(
      `SELECT id, packet_id, claimer_address, tx_hash, amount_wei, created_at
       FROM red_packet_claims
       WHERE packet_id = ?
       ORDER BY id ASC`,
      [packetId],
    );
    const [refunds] = await this.pool.query(
      `SELECT id, packet_id, creator_address, tx_hash, amount_wei, created_at
       FROM red_packet_refunds
       WHERE packet_id = ?
       ORDER BY id ASC`,
      [packetId],
    );
    return { packet: rows[0], claims, refunds };
  }

  async getAdminClaimPage(options = {}) {
    const page = Math.max(Number(options.page) || 1, 1);
    const safeLimit = Math.min(Math.max(Number(options.pageSize) || 20, 1), 500);
    const safeOffset = Math.max(Number(options.offset) || ((page - 1) * safeLimit), 0);
    const where = [];
    const params = [];

    const search = String(options.search || '').trim();
    if (search) {
      const term = `%${search}%`;
      where.push(`(
        c.packet_id LIKE ? OR c.claimer_address LIKE ? OR c.tx_hash LIKE ? OR
        p.creator_wallet LIKE ? OR p.greeting LIKE ?
      )`);
      params.push(term, term, term, term, term);
    }

    const packetId = String(options.packetId || '').trim();
    if (packetId) {
      where.push('c.packet_id = ?');
      params.push(packetId);
    }

    const wallet = normalizeAddress(options.wallet || '');
    if (wallet) {
      where.push('c.claimer_address = ?');
      params.push(wallet);
    }

    const tokenSymbol = String(options.tokenSymbol || '').trim();
    if (tokenSymbol) {
      where.push('p.token_symbol = ?');
      params.push(tokenSymbol);
    }

    if (Number.isInteger(options.createdFrom)) {
      where.push('c.created_at >= ?');
      params.push(options.createdFrom);
    }
    if (Number.isInteger(options.createdTo)) {
      where.push('c.created_at <= ?');
      params.push(options.createdTo);
    }

    const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
    const [countRows] = await this.pool.query(
      `SELECT COUNT(*) AS total
       FROM red_packet_claims c
       LEFT JOIN red_packets p ON p.packet_id = c.packet_id
       ${whereSql}`,
      params,
    );
    const [rows] = await this.pool.query(
      `SELECT
        c.id, c.packet_id, c.claimer_address, c.tx_hash, c.amount_wei, c.created_at,
        p.creator_wallet, p.token_symbol, p.token_decimals, p.greeting, p.packet_type
       FROM red_packet_claims c
       LEFT JOIN red_packets p ON p.packet_id = c.packet_id
       ${whereSql}
       ORDER BY c.created_at DESC, c.id DESC
       LIMIT ? OFFSET ?`,
      [...params, safeLimit, safeOffset],
    );

    return {
      rows,
      page,
      pageSize: safeLimit,
      total: Number(countRows[0]?.total || 0),
    };
  }

  async getAdminRefundPage(options = {}) {
    const page = Math.max(Number(options.page) || 1, 1);
    const safeLimit = Math.min(Math.max(Number(options.pageSize) || 20, 1), 500);
    const safeOffset = Math.max(Number(options.offset) || ((page - 1) * safeLimit), 0);
    const where = [];
    const params = [];

    const search = String(options.search || '').trim();
    if (search) {
      const term = `%${search}%`;
      where.push(`(
        r.packet_id LIKE ? OR r.creator_address LIKE ? OR r.tx_hash LIKE ? OR
        p.greeting LIKE ? OR p.token_symbol LIKE ?
      )`);
      params.push(term, term, term, term, term);
    }

    const packetId = String(options.packetId || '').trim();
    if (packetId) {
      where.push('r.packet_id = ?');
      params.push(packetId);
    }

    const wallet = normalizeAddress(options.wallet || '');
    if (wallet) {
      where.push('r.creator_address = ?');
      params.push(wallet);
    }

    const tokenSymbol = String(options.tokenSymbol || '').trim();
    if (tokenSymbol) {
      where.push('p.token_symbol = ?');
      params.push(tokenSymbol);
    }

    if (Number.isInteger(options.createdFrom)) {
      where.push('r.created_at >= ?');
      params.push(options.createdFrom);
    }
    if (Number.isInteger(options.createdTo)) {
      where.push('r.created_at <= ?');
      params.push(options.createdTo);
    }

    const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
    const [countRows] = await this.pool.query(
      `SELECT COUNT(*) AS total
       FROM red_packet_refunds r
       LEFT JOIN red_packets p ON p.packet_id = r.packet_id
       ${whereSql}`,
      params,
    );
    const [rows] = await this.pool.query(
      `SELECT
        r.id, r.packet_id, r.creator_address, r.tx_hash, r.amount_wei, r.created_at,
        p.token_symbol, p.token_decimals, p.greeting, p.packet_type
       FROM red_packet_refunds r
       LEFT JOIN red_packets p ON p.packet_id = r.packet_id
       ${whereSql}
       ORDER BY r.created_at DESC, r.id DESC
       LIMIT ? OFFSET ?`,
      [...params, safeLimit, safeOffset],
    );

    return {
      rows,
      page,
      pageSize: safeLimit,
      total: Number(countRows[0]?.total || 0),
    };
  }

  async getAdminWalletPage(options = {}) {
    const page = Math.max(Number(options.page) || 1, 1);
    const safeLimit = Math.min(Math.max(Number(options.pageSize) || 20, 1), 500);
    const safeOffset = Math.max(Number(options.offset) || ((page - 1) * safeLimit), 0);
    const search = String(options.search || '').trim();

    const aggregateSql = `
      SELECT
        wallet,
        SUM(sent_packets) AS sentPackets,
        CAST(SUM(sent_amount_wei) AS CHAR) AS sentAmountWei,
        SUM(claim_count) AS claimCount,
        CAST(SUM(claimed_amount_wei) AS CHAR) AS claimedAmountWei,
        MAX(last_activity) AS lastActivity
      FROM (
        SELECT
          creator_wallet AS wallet,
          COUNT(*) AS sent_packets,
          COALESCE(SUM(CAST(total_amount_wei AS DECIMAL(65, 0))), 0) AS sent_amount_wei,
          0 AS claim_count,
          0 AS claimed_amount_wei,
          MAX(created_at) AS last_activity
        FROM red_packets
        GROUP BY creator_wallet
        UNION ALL
        SELECT
          claimer_address AS wallet,
          0 AS sent_packets,
          0 AS sent_amount_wei,
          COUNT(*) AS claim_count,
          COALESCE(SUM(CAST(amount_wei AS DECIMAL(65, 0))), 0) AS claimed_amount_wei,
          MAX(created_at) AS last_activity
        FROM red_packet_claims
        GROUP BY claimer_address
      ) wallet_activity
      WHERE wallet <> ''
      GROUP BY wallet`;

    const params = [];
    let filteredSql = `SELECT * FROM (${aggregateSql}) wallet_stats`;
    if (search) {
      filteredSql += ' WHERE wallet LIKE ?';
      params.push(`%${search}%`);
    }

    const [countRows] = await this.pool.query(
      `SELECT COUNT(*) AS total FROM (${filteredSql}) counted_wallets`,
      params,
    );
    const [rows] = await this.pool.query(
      `${filteredSql}
       ORDER BY lastActivity DESC
       LIMIT ? OFFSET ?`,
      [...params, safeLimit, safeOffset],
    );

    return {
      rows,
      page,
      pageSize: safeLimit,
      total: Number(countRows[0]?.total || 0),
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

app.get('/api/v1/wallet/default-tokens', (_req, res) => {
  return res.json({ ok: true, data: { tokens: DEFAULT_WALLET_TOKENS } });
});

function adminAsync(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
}

function buildAdminPacketOptions(req, maxPageSize = 200) {
  const paging = parseAdminPageParams(req.query, maxPageSize);
  const createdFrom = parseAdminDateSeconds(req.query.createdFrom, false);
  const createdTo = parseAdminDateSeconds(req.query.createdTo, true);
  return {
    ...paging,
    search: String(req.query.search || '').trim(),
    status: normalizeAdminStatus(req.query.status),
    tokenSymbol: String(req.query.tokenSymbol || '').trim(),
    creatorWallet: String(req.query.creatorWallet || '').trim(),
    createdFrom: createdFrom === null ? undefined : createdFrom,
    createdTo: createdTo === null ? undefined : createdTo,
  };
}

function buildAdminActivityOptions(req, maxPageSize = 200) {
  const paging = parseAdminPageParams(req.query, maxPageSize);
  const createdFrom = parseAdminDateSeconds(req.query.createdFrom, false);
  const createdTo = parseAdminDateSeconds(req.query.createdTo, true);
  return {
    ...paging,
    search: String(req.query.search || '').trim(),
    packetId: String(req.query.packetId || '').trim(),
    wallet: String(req.query.wallet || '').trim(),
    tokenSymbol: String(req.query.tokenSymbol || '').trim(),
    createdFrom: createdFrom === null ? undefined : createdFrom,
    createdTo: createdTo === null ? undefined : createdTo,
  };
}

function buildAdminWalletOptions(req, maxPageSize = 200) {
  const paging = parseAdminPageParams(req.query, maxPageSize);
  return {
    ...paging,
    search: String(req.query.search || '').trim(),
  };
}

async function collectExportRows(fetchPage, limit = 2000) {
  const safeLimit = Math.min(Math.max(Number(limit) || 2000, 1), 5000);
  const rows = [];
  let page = 1;
  while (rows.length < safeLimit) {
    const pageSize = Math.min(500, safeLimit - rows.length);
    const result = await fetchPage(page, pageSize, rows.length);
    const batch = result.rows || [];
    rows.push(...batch);
    if (!batch.length || rows.length >= Number(result.total || 0)) break;
    page += 1;
  }
  return rows.slice(0, safeLimit);
}


const adminUrlencodedParser = express.urlencoded({ extended: false, limit: '32kb' });

if (ADMIN_CORS_ORIGIN) {
  app.use(ADMIN_BASE_PATH, (req, res, next) => {
    res.setHeader('Access-Control-Allow-Origin', ADMIN_CORS_ORIGIN);
    res.setHeader('Access-Control-Allow-Credentials', 'true');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    return next();
  });
}

function adminPublicConfig() {
  return {
    enabled: isAdminAuthConfigured(),
    username: ADMIN_USERNAME,
    apiBasePath: ADMIN_BASE_PATH,
    webBasePath: ADMIN_WEB_BASE_PATH,
  };
}

app.get(`${ADMIN_BASE_PATH}/auth/config`, (_req, res) => {
  res.set('Cache-Control', 'no-store');
  return res.json({ ok: true, data: adminPublicConfig() });
});

app.post(`${ADMIN_BASE_PATH}/auth/login`, adminUrlencodedParser, adminAsync(async (req, res) => {
  res.set('Cache-Control', 'no-store');
  if (!isAdminAuthConfigured()) {
    return res.status(503).json({ ok: false, message: '后台登录未启用：请先配置 ADMIN_PASSWORD 或 ADMIN_TOKEN' });
  }

  const username = String(req.body?.username || '').trim();
  const password = String(req.body?.password || '');
  const passwordOk = ADMIN_PASSWORD && timingSafeEqualString(password, ADMIN_PASSWORD);
  const tokenOk = ADMIN_BEARER_TOKEN && timingSafeEqualString(password, ADMIN_BEARER_TOKEN);
  if (username !== ADMIN_USERNAME || (!passwordOk && !tokenOk)) {
    return res.status(401).json({ ok: false, message: '用户名或密码错误' });
  }

  setAdminSessionCookie(req, res);
  return res.json({
    ok: true,
    data: {
      username: ADMIN_USERNAME,
      expiresIn: ADMIN_SESSION_TTL_SECONDS,
    },
  });
}));

app.get(`${ADMIN_BASE_PATH}/auth/me`, adminRequireAuth, (req, res) => {
  return res.json({ ok: true, data: { username: req.adminUser || ADMIN_USERNAME } });
});

app.post(`${ADMIN_BASE_PATH}/auth/logout`, (req, res) => {
  clearAdminSessionCookie(req, res);
  return res.json({ ok: true, data: { loggedOut: true } });
});

app.get(`${ADMIN_BASE_PATH}/auth/logout`, (req, res) => {
  clearAdminSessionCookie(req, res);
  return res.json({ ok: true, data: { loggedOut: true } });
});

app.get(`${ADMIN_BASE_PATH}/stats`, adminRequireAuth, adminAsync(async (_req, res) => {
  const [dashboard, recent] = await Promise.all([
    db.getAdminDashboardStats(),
    db.getAdminPacketPage({ page: 1, pageSize: 8, offset: 0 }),
  ]);
  return res.json({ ok: true, data: { ...dashboard, recentPackets: recent.rows } });
}));

app.get(`${ADMIN_BASE_PATH}/packets`, adminRequireAuth, adminAsync(async (req, res) => {
  const result = await db.getAdminPacketPage(buildAdminPacketOptions(req, 200));
  return res.json({ ok: true, data: result });
}));

app.get(`${ADMIN_BASE_PATH}/packets/:packetId`, adminRequireAuth, adminAsync(async (req, res) => {
  const detail = await db.getAdminPacketDetail(String(req.params.packetId || '').trim());
  if (!detail) return res.status(404).json({ ok: false, message: 'not found' });
  return res.json({ ok: true, data: detail });
}));

app.get(`${ADMIN_BASE_PATH}/claims`, adminRequireAuth, adminAsync(async (req, res) => {
  const result = await db.getAdminClaimPage(buildAdminActivityOptions(req, 200));
  return res.json({ ok: true, data: result });
}));

app.get(`${ADMIN_BASE_PATH}/refunds`, adminRequireAuth, adminAsync(async (req, res) => {
  const result = await db.getAdminRefundPage(buildAdminActivityOptions(req, 200));
  return res.json({ ok: true, data: result });
}));

app.get(`${ADMIN_BASE_PATH}/wallets`, adminRequireAuth, adminAsync(async (req, res) => {
  const result = await db.getAdminWalletPage(buildAdminWalletOptions(req, 200));
  return res.json({ ok: true, data: result });
}));

app.get(`${ADMIN_BASE_PATH}/system`, adminRequireAuth, adminAsync(async (_req, res) => {
  let rpcOk = true;
  let blockNumber = null;
  try {
    blockNumber = await provider.getBlockNumber();
  } catch (_) {
    rpcOk = false;
  }

  let dbOk = true;
  let dbVersion = '';
  let tableRows = [];
  try {
    const [versionRows] = await db.pool.query('SELECT VERSION() AS version');
    dbVersion = versionRows[0]?.version || '';
    const [tables] = await db.pool.query(
      `SELECT TABLE_NAME AS tableName, TABLE_ROWS AS estimatedRows
       FROM information_schema.TABLES
       WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN ('red_packets', 'red_packet_claims', 'red_packet_refunds')
       ORDER BY TABLE_NAME ASC`,
      [MYSQL_DATABASE],
    );
    tableRows = tables;
  } catch (_) {
    dbOk = false;
  }

  return res.json({
    ok: true,
    data: {
      now: nowSeconds(),
      serverStartedAt: SERVER_STARTED_AT,
      health: { rpcOk, blockNumber, dbOk },
      database: { version: dbVersion, tables: tableRows },
      config: {
        chainId: CHAIN_ID,
        contractAddress: CONTRACT_ADDRESS,
        rpcUrl: maskSensitiveUrl(RPC_URL),
        mysql: `${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}`,
        appVersion: `${APP_VERSION_NAME} (${APP_VERSION_CODE})`,
        proxy: `${DEFAULT_PROXY_ADDRESS}:${DEFAULT_PROXY_PORT}${DEFAULT_PROXY_USERNAME ? ` user=${DEFAULT_PROXY_USERNAME}` : ''}`,
        adminApiBasePath: ADMIN_BASE_PATH,
        adminWebBasePath: ADMIN_WEB_BASE_PATH,
      },
    },
  });
}));

app.get(`${ADMIN_BASE_PATH}/export/packets.csv`, adminRequireAuth, adminAsync(async (req, res) => {
  const baseOptions = buildAdminPacketOptions(req, 500);
  const rows = await collectExportRows((page, pageSize, offset) => db.getAdminPacketPage({
    ...baseOptions,
    page,
    pageSize,
    offset,
  }), req.query.limit);
  const headers = ['packet_id', 'runtime_status', 'creator_wallet', 'token_symbol', 'total_amount_wei', 'count_total', 'remaining_count', 'claim_count', 'greeting', 'create_tx_hash', 'created_at', 'expires_at'];
  return sendCsv(res, `red-packets-${Date.now()}.csv`, headers, rows);
}));

app.get(`${ADMIN_BASE_PATH}/export/claims.csv`, adminRequireAuth, adminAsync(async (req, res) => {
  const baseOptions = buildAdminActivityOptions(req, 500);
  const rows = await collectExportRows((page, pageSize, offset) => db.getAdminClaimPage({
    ...baseOptions,
    page,
    pageSize,
    offset,
  }), req.query.limit);
  const headers = ['id', 'packet_id', 'claimer_address', 'amount_wei', 'tx_hash', 'token_symbol', 'creator_wallet', 'created_at'];
  return sendCsv(res, `red-packet-claims-${Date.now()}.csv`, headers, rows);
}));

app.get(`${ADMIN_BASE_PATH}/export/refunds.csv`, adminRequireAuth, adminAsync(async (req, res) => {
  const baseOptions = buildAdminActivityOptions(req, 500);
  const rows = await collectExportRows((page, pageSize, offset) => db.getAdminRefundPage({
    ...baseOptions,
    page,
    pageSize,
    offset,
  }), req.query.limit);
  const headers = ['id', 'packet_id', 'creator_address', 'amount_wei', 'tx_hash', 'token_symbol', 'created_at'];
  return sendCsv(res, `red-packet-refunds-${Date.now()}.csv`, headers, rows);
}));

function setupAdminWebStatic() {
  const indexPath = path.join(ADMIN_WEB_DIST, 'index.html');
  const hasBuiltAdmin = fs.existsSync(indexPath);

  if (hasBuiltAdmin) {
    app.use(ADMIN_WEB_BASE_PATH, express.static(ADMIN_WEB_DIST, {
      index: false,
      maxAge: process.env.NODE_ENV === 'production' ? '1h' : 0,
    }));
  }

  app.use(ADMIN_WEB_BASE_PATH, (req, res, next) => {
    if (req.method !== 'GET') return next();
    if (hasBuiltAdmin) return res.sendFile(indexPath);
    return res.status(404).json({
      ok: false,
      message: 'admin frontend is separated. Run `npm --prefix admin-web install && npm --prefix admin-web run build`, or serve admin-web with Vite/Nginx.',
      adminApiBasePath: ADMIN_BASE_PATH,
      expectedDist: ADMIN_WEB_DIST,
    });
  });

  if (ADMIN_WEB_BASE_PATH !== '/admin') {
    app.get('/admin', (_req, res) => res.redirect(ADMIN_WEB_BASE_PATH));
  }
}

setupAdminWebStatic();

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

app.get('/api/v1/red-packets/send-records', async (req, res) => {
  const creatorWallet = String(req.query.creatorWallet || '').trim();
  const limit = Number(req.query.limit || 50);
  if (!normalizeAddress(creatorWallet)) {
    return badRequest(res, 'creatorWallet invalid');
  }
  const records = await db.getSendRecordsByCreator(creatorWallet, limit);
  return res.json({ ok: true, data: records });
});

app.get('/api/v1/red-packets/send-records/:packetId', async (req, res) => {
  const detail = await db.getSendRecordDetail(String(req.params.packetId || '').trim());
  if (!detail) {
    return res.status(404).json({ ok: false, message: 'not found' });
  }
  return res.json({ ok: true, data: detail });
});

app.get('/api/v1/red-packets/:packetId', async (req, res) => {
  const packet = await ensurePacket(req.params.packetId, res);
  if (!packet) return;
  return res.json({ ok: true, data: buildPacketResponse(packet, req.query.wallet) });
});

app.get('/api/v1/client/proxy', async (_req, res) => {
  return res.json({
    ok: true,
    data: {
      address: DEFAULT_PROXY_ADDRESS,
      port: DEFAULT_PROXY_PORT,
      username: DEFAULT_PROXY_USERNAME,
      password: DEFAULT_PROXY_PASSWORD,
      secret: DEFAULT_PROXY_SECRET,
      updatedAt: nowSeconds(),
    },
  });
});

app.get('/api/v1/client/version/check', async (req, res) => {
  const clientVersionCode = Number(req.query.versionCode || 0);
  const clientVersionName = String(req.query.versionName || '').trim();
  const hasUpdate = clientVersionCode > 0
    ? clientVersionCode < APP_VERSION_CODE
    : true;
  const checkedAt = nowSeconds();
  const releaseDate = APP_RELEASE_DATE > 0 ? APP_RELEASE_DATE : checkedAt;

  return res.json({
    ok: true,
    data: {
      hasUpdate,
      currentVersionCode: clientVersionCode,
      currentVersionName: clientVersionName,
      versionCode: APP_VERSION_CODE,
      versionName: APP_VERSION_NAME,
      releaseDate,
      apkSizeBytes: APP_APK_SIZE_BYTES > 0 ? APP_APK_SIZE_BYTES : null,
      downloadUrl: hasUpdate ? APP_DOWNLOAD_URL : '',
      message: APP_VERSION_MESSAGE,
      checkedAt,
    },
  });
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
  const claimerName = String(req.body?.claimerName || req.body?.telegramName || req.body?.telegramId || '')
    .trim()
    .slice(0, 255);
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
    updated = await db.confirmClaim(packet, claimerAddress, txHash, claimerName);
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


app.post('/api/v1/red-packets/:packetId/refund-confirm', async (req, res) => {
  const packet = await ensurePacket(req.params.packetId, res);
  if (!packet) return;

  const creatorAddress = normalizeAddress(req.body?.creatorAddress);
  const txHash = String(req.body?.txHash || '').trim();
  if (!creatorAddress) return badRequest(res, 'creatorAddress invalid');
  if (!/^0x[0-9a-fA-F]{64}$/.test(txHash)) return badRequest(res, 'txHash invalid');
  if (creatorAddress !== packet.creatorWallet) return badRequest(res, 'creator mismatch');
  if (!packet.onchainCreated) return badRequest(res, 'packet not confirmed on chain');

  const receipt = await getTransactionReceipt(txHash);
  if (!receipt || receipt.status !== 1) return badRequest(res, 'transaction not confirmed');

  const event = parseExpectedLog(receipt, 'Refunded');
  if (!event) return badRequest(res, 'Refunded event not found');

  const eventPacketIdHex = String(event.args.packetId).toLowerCase();
  const eventCreator = normalizeAddress(String(event.args.creator));
  const eventAmountRaw = event.args.amount ?? event.args[3];
  const eventAmount = BigInt(eventAmountRaw).toString();

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'Refunded packetId mismatch');
  if (eventCreator !== creatorAddress) return badRequest(res, 'Refunded creator mismatch');

  let updated;
  try {
    updated = await db.confirmRefund(packet, creatorAddress, txHash, eventAmount);
  } catch (error) {
    if (String(error.message).includes('already refunded')) {
      return badRequest(res, 'already refunded');
    }
    throw error;
  }

  return res.json({
    ok: true,
    data: {
      packetId: updated.packetId,
      txHash,
      refunded: true,
      status: getPacketStatus(updated),
      remainingCount: updated.remainingCount,
      refundAmountWei: eventAmount,
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
  await db.ensureSchema();
  app.listen(port, () => {
    // eslint-disable-next-line no-console
    console.log(`red-packet service listening on http://127.0.0.1:${port}`);
    // eslint-disable-next-line no-console
    console.log(`admin API available at http://127.0.0.1:${port}${ADMIN_BASE_PATH}`);
    // eslint-disable-next-line no-console
    console.log(`admin web path: http://127.0.0.1:${port}${ADMIN_WEB_BASE_PATH}`);
  });
})();
