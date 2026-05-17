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
const https = require('https');
const mysql = require('mysql2/promise');
const { JsonRpcProvider, Interface, Wallet, getAddress, getBytes, isAddress, solidityPackedKeccak256 } = require('ethers');

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
const DEFAULT_RED_PACKET_CONTRACT = '0x5a6361A5Af1c56eDF7E6e9e0B191a92BBf957fC3';
const DEFAULT_RPC_URL = 'https://data-seed-prebsc-1-s1.bnbchain.org:8545';
const MAX_PACKET_COUNT = 500;
const CONTRACT_MAX_EXPIRES_IN_SECONDS = 30 * 24 * 60 * 60;
const ZERO_ADDRESS = '0x0000000000000000000000000000000000000000';
const RED_PACKET_AUTH_SIGNER_PRIVATE_KEY = String(
  process.env.RED_PACKET_AUTH_SIGNER_PRIVATE_KEY || process.env.RED_PACKET_CLAIM_SIGNER_PRIVATE_KEY || '',
).trim();
let claimSignerWallet = null;
try {
  if (RED_PACKET_AUTH_SIGNER_PRIVATE_KEY) {
    claimSignerWallet = new Wallet(normalizePrivateKey(RED_PACKET_AUTH_SIGNER_PRIVATE_KEY));
  }
} catch (error) {
  // eslint-disable-next-line no-console
  console.error('[red-packet-auth-signer-config-error]', error.message);
}

const BOOTSTRAP_RED_PACKET_CONTRACT = normalizeAddress(process.env.RED_PACKET_CONTRACT || DEFAULT_RED_PACKET_CONTRACT)
  || normalizeAddress(DEFAULT_RED_PACKET_CONTRACT);
const BOOTSTRAP_RPC_URLS = normalizeRpcUrlsForBootstrap(process.env.RPC_URLS || process.env.RPC_URL || DEFAULT_RPC_URL);

// Backward-compatible RPC name used only as startup/status fallback.
const RPC_URL = BOOTSTRAP_RPC_URLS[0]?.url || DEFAULT_RPC_URL;

// Bootstrap values are used to seed the database on first start.
// After that, these runtime values are read from the admin-managed system_settings table.
const BOOTSTRAP_PUBLIC_HOST = process.env.PUBLIC_HOST || 'http://127.0.0.1:8787';
const BOOTSTRAP_MAX_EXPIRES_IN_SECONDS = Math.min(
  Math.max(numberFromEnv(process.env.MAX_EXPIRES_IN_SECONDS, CONTRACT_MAX_EXPIRES_IN_SECONDS), 1),
  CONTRACT_MAX_EXPIRES_IN_SECONDS,
);
const BOOTSTRAP_DEFAULT_PROXY_ADDRESS = (process.env.DEFAULT_PROXY_ADDRESS || '139.180.223.206').trim();
const BOOTSTRAP_DEFAULT_PROXY_PORT = Number(process.env.DEFAULT_PROXY_PORT || 443);
const BOOTSTRAP_DEFAULT_PROXY_USERNAME = process.env.DEFAULT_PROXY_USERNAME || '';
const BOOTSTRAP_DEFAULT_PROXY_PASSWORD = process.env.DEFAULT_PROXY_PASSWORD || '';
const BOOTSTRAP_DEFAULT_PROXY_SECRET = process.env.DEFAULT_PROXY_SECRET || 'aff4456037ec453cde85935760a840f0';
const BOOTSTRAP_APP_VERSION_CODE = Number(process.env.APP_VERSION_CODE || 1);
const BOOTSTRAP_APP_VERSION_NAME = process.env.APP_VERSION_NAME || '1.0.0';
const BOOTSTRAP_APP_DOWNLOAD_URL = process.env.APP_DOWNLOAD_URL || '';
const BOOTSTRAP_APP_VERSION_MESSAGE = process.env.APP_VERSION_MESSAGE || '';
const BOOTSTRAP_APP_RELEASE_DATE = Number(process.env.APP_RELEASE_DATE || 0);
const BOOTSTRAP_APP_APK_SIZE_BYTES = Number(process.env.APP_APK_SIZE_BYTES || 0);
const BOOTSTRAP_APP_UPLOAD_PUBLIC_PATH = normalizePublicPath(process.env.APP_UPLOAD_PUBLIC_PATH || '/uploads/apks');
const BOOTSTRAP_APP_UPLOAD_DIR = path.resolve(process.env.APP_UPLOAD_DIR || path.join(__dirname, 'uploads', 'apks'));
const BOOTSTRAP_APP_UPLOAD_URL_BASE = String(process.env.APP_UPLOAD_URL_BASE || '').trim().replace(/\/+$/, '');
const BOOTSTRAP_MAX_APK_UPLOAD_BYTES = Math.max(numberFromEnv(process.env.MAX_APK_UPLOAD_BYTES, 150 * 1024 * 1024), 1024 * 1024);
const BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH = normalizePublicPath(process.env.TOKEN_ICON_PUBLIC_PATH || '/uploads/token-icons');
const BOOTSTRAP_TOKEN_ICON_DIR = path.resolve(process.env.TOKEN_ICON_DIR || path.join(__dirname, 'uploads', 'token-icons'));
const BOOTSTRAP_MAX_TOKEN_ICON_UPLOAD_BYTES = Math.max(numberFromEnv(process.env.MAX_TOKEN_ICON_UPLOAD_BYTES, 2 * 1024 * 1024), 64 * 1024);
const DEFAULT_BNB_ICON_URL = 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/info/logo.png';
const BOOTSTRAP_BNB_ICON_URL = String(process.env.BNB_ICON_URL || DEFAULT_BNB_ICON_URL).trim();
const BOOTSTRAP_TOKEN_ICON_REGISTRY = parseTokenIconRegistryForBootstrap(process.env.TOKEN_ICON_REGISTRY || process.env.TOKEN_ICON_MAP || '[]');
const BOOTSTRAP_TOKEN_PRICE_AUTO_ENABLED = parseBooleanFlag(process.env.TOKEN_PRICE_AUTO_ENABLED, true);
const BOOTSTRAP_TOKEN_PRICE_EXTERNAL_TTL_SECONDS = Math.max(numberFromEnv(process.env.TOKEN_PRICE_EXTERNAL_TTL_SECONDS, 60), 30);
const BOOTSTRAP_TOKEN_PRICE_PROVIDER_ORDER = normalizeTokenPriceProviderOrderForBootstrap(process.env.TOKEN_PRICE_PROVIDER_ORDER || '["dexscreener","defillama","coingecko"]');
const BOOTSTRAP_TOKEN_PRICE_REGISTRY = parseTokenPriceRegistryForBootstrap(process.env.TOKEN_PRICE_REGISTRY || process.env.TOKEN_PRICE_MAP || '[]');
const BSC_STABLE_PRICE_ADDRESSES = new Set([
  '0x55d398326f99059ff775485246999027b3197955', // USDT on BNB Smart Chain
  '0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d', // USDC on BNB Smart Chain
  '0xe9e7cea3dedca5984780bafc599bd69add087d56', // BUSD on BNB Smart Chain
]);
const BUILTIN_DEFAULT_WALLET_TOKENS = [
  { symbol: 'ETZ', contractAddress: '0xc78dabf21594c76ad98a0b3ed103fcfcd9499999', decimals: 18, priceUsd: '0', iconUrl: '' },
  { symbol: 'Piao', contractAddress: '0x68973e906a64b283ac90eb88cd561ba6c6681103', decimals: 18, priceUsd: '0', iconUrl: '' },
  { symbol: 'Tea', contractAddress: '0x3142Db225d0262973715606c85B2B50a66f9b00C', decimals: 18, priceUsd: '0', iconUrl: '' },
  { symbol: 'Dimei', contractAddress: '0xb299d5bdf3c17d14aafb305f97b16c5aa0999921', decimals: 18, priceUsd: '0', iconUrl: '' },
  { symbol: 'Mu', contractAddress: '0x7677421f49776addcfc18cb851df0c24d02d8888', decimals: 18, priceUsd: '0', iconUrl: '' },
  { symbol: 'Goods', contractAddress: '0x80B75C9c6773D255c32ADA8E971c0C4ba03088d0', decimals: 18, priceUsd: '0', iconUrl: '' },
];


const RUNTIME_SETTING_DEFINITIONS = [
  {
    key: 'publicHost',
    group: 'base',
    label: '服务公网地址',
    type: 'string',
    defaultValue: BOOTSTRAP_PUBLIC_HOST,
    required: true,
    maxLength: 255,
    description: '用于生成红包 claimUrl，以及未配置 APK URL Base 时生成 APK 下载地址。',
  },
  {
    key: 'rpcUrls',
    group: 'chain',
    label: 'BSC RPC URL 列表',
    type: 'json',
    defaultValue: BOOTSTRAP_RPC_URLS,
    description: '客户端和服务端使用的 RPC 列表。支持多个 URL；服务端会探测可用性并优先使用连接最快且区块最新的节点。',
  },
  {
    key: 'redPacketContract',
    group: 'chain',
    label: '红包合约地址',
    type: 'string',
    defaultValue: BOOTSTRAP_RED_PACKET_CONTRACT,
    required: true,
    maxLength: 64,
    description: '当前用于创建新红包的合约地址；历史红包继续使用创建时保存的合约地址。',
  },
  {
    key: 'maxExpiresInSeconds',
    group: 'redPacket',
    label: '红包最大有效期（秒）',
    type: 'number',
    defaultValue: BOOTSTRAP_MAX_EXPIRES_IN_SECONDS,
    min: 1,
    max: CONTRACT_MAX_EXPIRES_IN_SECONDS,
    description: '不能超过合约 MAX_EXPIRES_IN，即 30 天。',
  },
  {
    key: 'appUploadPublicPath',
    group: 'clientUpdate',
    label: 'APK 公开下载路径',
    type: 'string',
    defaultValue: BOOTSTRAP_APP_UPLOAD_PUBLIC_PATH,
    required: true,
    maxLength: 128,
    description: '服务端公开 APK 的 URL path，例如 /uploads/apks。',
  },
  {
    key: 'appUploadDir',
    group: 'clientUpdate',
    label: 'APK 保存目录',
    type: 'string',
    defaultValue: BOOTSTRAP_APP_UPLOAD_DIR,
    required: true,
    maxLength: 512,
    description: '服务端本地保存 APK 文件的目录，可以填相对路径或绝对路径。',
  },
  {
    key: 'appUploadUrlBase',
    group: 'clientUpdate',
    label: 'APK 下载 URL Base',
    type: 'string',
    defaultValue: BOOTSTRAP_APP_UPLOAD_URL_BASE,
    maxLength: 512,
    description: '如果使用 CDN/Nginx 分发 APK，可填完整 URL 前缀；留空则使用服务公网地址 + APK 公开下载路径。',
  },
  {
    key: 'maxApkUploadMB',
    group: 'clientUpdate',
    label: 'APK 最大上传大小（MB）',
    type: 'number',
    defaultValue: Math.max(Math.floor(BOOTSTRAP_MAX_APK_UPLOAD_BYTES / 1024 / 1024), 1),
    min: 1,
    max: 2048,
    description: '管理后台上传 APK 的最大文件大小。',
  },
  {
    key: 'fallbackVersionCode',
    group: 'clientUpdate',
    label: '兜底版本号 versionCode',
    type: 'number',
    defaultValue: Number.isFinite(BOOTSTRAP_APP_VERSION_CODE) ? BOOTSTRAP_APP_VERSION_CODE : 1,
    min: 1,
    max: 2147483647,
    description: '当后台还没有发布客户端版本时，版本检查接口使用此兜底版本号。',
  },
  {
    key: 'fallbackVersionName',
    group: 'clientUpdate',
    label: '兜底版本名称 versionName',
    type: 'string',
    defaultValue: BOOTSTRAP_APP_VERSION_NAME,
    required: true,
    maxLength: 64,
    description: '当后台还没有发布客户端版本时，版本检查接口使用此兜底版本名称。',
  },
  {
    key: 'fallbackDownloadUrl',
    group: 'clientUpdate',
    label: '兜底 APK 下载地址',
    type: 'string',
    defaultValue: BOOTSTRAP_APP_DOWNLOAD_URL,
    maxLength: 1024,
    description: '当后台还没有发布客户端版本时，版本检查接口使用此兜底下载地址。',
  },
  {
    key: 'fallbackVersionMessage',
    group: 'clientUpdate',
    label: '兜底更新内容',
    type: 'text',
    defaultValue: BOOTSTRAP_APP_VERSION_MESSAGE,
    maxLength: 5000,
    description: '当后台还没有发布客户端版本时，版本检查接口使用此兜底更新内容。',
  },
  {
    key: 'fallbackReleaseDate',
    group: 'clientUpdate',
    label: '兜底发布日期',
    type: 'number',
    defaultValue: Number.isFinite(BOOTSTRAP_APP_RELEASE_DATE) ? BOOTSTRAP_APP_RELEASE_DATE : 0,
    min: 0,
    max: 4102444800,
    description: 'Unix 秒时间戳；0 表示接口返回当前检查时间。',
  },
  {
    key: 'fallbackApkSizeBytes',
    group: 'clientUpdate',
    label: '兜底 APK 大小（字节）',
    type: 'number',
    defaultValue: Number.isFinite(BOOTSTRAP_APP_APK_SIZE_BYTES) ? BOOTSTRAP_APP_APK_SIZE_BYTES : 0,
    min: 0,
    max: 10 * 1024 * 1024 * 1024,
    description: '当后台还没有发布客户端版本时，版本检查接口返回的 APK 大小。',
  },
  {
    key: 'proxyAddress',
    group: 'proxy',
    label: '客户端代理地址',
    type: 'string',
    defaultValue: BOOTSTRAP_DEFAULT_PROXY_ADDRESS,
    required: true,
    maxLength: 255,
    description: '客户端 /api/v1/client/proxy 返回的代理 address。',
  },
  {
    key: 'proxyPort',
    group: 'proxy',
    label: '客户端代理端口',
    type: 'number',
    defaultValue: Number.isFinite(BOOTSTRAP_DEFAULT_PROXY_PORT) ? BOOTSTRAP_DEFAULT_PROXY_PORT : 443,
    min: 1,
    max: 65535,
    description: '客户端 /api/v1/client/proxy 返回的代理 port。',
  },
  {
    key: 'proxyUsername',
    group: 'proxy',
    label: '客户端代理用户名',
    type: 'string',
    defaultValue: BOOTSTRAP_DEFAULT_PROXY_USERNAME,
    maxLength: 255,
    description: '客户端 /api/v1/client/proxy 返回的 username。',
  },
  {
    key: 'proxyPassword',
    group: 'proxy',
    label: '客户端代理密码',
    type: 'string',
    defaultValue: BOOTSTRAP_DEFAULT_PROXY_PASSWORD,
    maxLength: 255,
    description: '客户端 /api/v1/client/proxy 返回的 password。',
  },
  {
    key: 'proxySecret',
    group: 'proxy',
    label: '客户端代理 Secret',
    type: 'string',
    defaultValue: BOOTSTRAP_DEFAULT_PROXY_SECRET,
    maxLength: 255,
    description: '客户端 /api/v1/client/proxy 返回的 secret。',
  },
  {
    key: 'bnbIconUrl',
    group: 'wallet',
    label: 'BNB 图标地址',
    type: 'string',
    defaultValue: BOOTSTRAP_BNB_ICON_URL,
    maxLength: 1024,
    description: 'BNB 原生币图标 URL。默认使用公开 BSC 图标；也可填完整 http/https 地址，或填 tokenIconPublicPath 下的文件名。',
  },
  {
    key: 'tokenIconPublicPath',
    group: 'wallet',
    label: '代币图标公开路径',
    type: 'string',
    defaultValue: BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH,
    required: true,
    maxLength: 128,
    description: '服务端公开代币图标的 URL path，例如 /uploads/token-icons。',
  },
  {
    key: 'tokenIconDir',
    group: 'wallet',
    label: '代币图标保存目录',
    type: 'string',
    defaultValue: BOOTSTRAP_TOKEN_ICON_DIR,
    required: true,
    maxLength: 512,
    description: '服务端本地保存代币图标 PNG/JPG/WebP/GIF 文件的目录。',
  },
  {
    key: 'tokenIconRegistry',
    group: 'wallet',
    label: '自定义代币图标库',
    type: 'json',
    defaultValue: BOOTSTRAP_TOKEN_ICON_REGISTRY,
    description: '用户在客户端手动添加代币时，服务端先按合约地址匹配这里配置的图标；未配置时会自动查找 tokenIconDir 下的 合约地址.png，并尝试从公开代币图库解析。支持数组 [{symbol, contractAddress, iconUrl}]，也支持对象 {"0x...":"xxx.png"}。',
  },
  {
    key: 'tokenPriceAutoEnabled',
    group: 'wallet',
    label: '自动获取代币行情价格',
    type: 'number',
    defaultValue: BOOTSTRAP_TOKEN_PRICE_AUTO_ENABLED ? 1 : 0,
    min: 0,
    max: 1,
    description: '1=启用服务端自动按 BSC 合约地址查询行情价格；0=只使用后台手动配置价格。',
  },
  {
    key: 'tokenPriceExternalTtlSeconds',
    group: 'wallet',
    label: '行情价格缓存时间（秒）',
    type: 'number',
    defaultValue: BOOTSTRAP_TOKEN_PRICE_EXTERNAL_TTL_SECONDS,
    min: 30,
    max: 86400,
    description: '服务端从外部行情源获取代币价格后的缓存时间。当前默认 60 秒；如果外部接口限流，可调高到 300-900 秒。',
  },
  {
    key: 'tokenPriceProviderOrder',
    group: 'wallet',
    label: '行情价格来源优先级',
    type: 'json',
    defaultValue: BOOTSTRAP_TOKEN_PRICE_PROVIDER_ORDER,
    description: '数组格式，例如 ["dexscreener","defillama","coingecko"]。服务端会按顺序尝试，找到有效价格即返回。',
  },
  {
    key: 'tokenPriceRegistry',
    group: 'wallet',
    label: '自定义代币价格库',
    type: 'json',
    defaultValue: BOOTSTRAP_TOKEN_PRICE_REGISTRY,
    description: '手动价格兜底库。支持数组 [{symbol, contractAddress, priceUsd}]，也支持对象 {"0x...":"0.123"}。优先级高于外部行情源。',
  },
  {
    key: 'walletTokens',
    group: 'wallet',
    label: '默认钱包代币列表',
    type: 'json',
    defaultValue: BUILTIN_DEFAULT_WALLET_TOKENS,
    description: '客户端 /api/v1/wallet/default-tokens 返回的默认代币数组；每个代币可配置 priceUsd 和 iconUrl。iconUrl 留空时会自动查找 tokenIconDir 下的 合约地址.png。',
  },
];
const RUNTIME_SETTING_DEFINITION_MAP = new Map(RUNTIME_SETTING_DEFINITIONS.map((definition) => [definition.key, definition]));

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

function numberFromEnv(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
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

function normalizePrivateKey(value) {
  const v = String(value || '').trim();
  if (/^0x[0-9a-fA-F]{64}$/.test(v)) return v;
  if (/^[0-9a-fA-F]{64}$/.test(v)) return `0x${v}`;
  throw new Error('RED_PACKET_AUTH_SIGNER_PRIVATE_KEY invalid');
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
      && status === 'expired',
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

function normalizePublicPath(value) {
  const raw = String(value || '/uploads/apks').trim();
  const withSlash = raw.startsWith('/') ? raw : `/${raw}`;
  const normalized = withSlash.replace(/\/+$/, '');
  return normalized || '/uploads/apks';
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

function parseBooleanFlag(value, defaultValue = false) {
  if (value === undefined || value === null || value === '') return defaultValue;
  if (typeof value === 'boolean') return value;
  const raw = String(value).trim().toLowerCase();
  if (['1', 'true', 'yes', 'y', 'on'].includes(raw)) return true;
  if (['0', 'false', 'no', 'n', 'off'].includes(raw)) return false;
  return defaultValue;
}

function normalizeRpcUrl(value) {
  const text = String(value || '').trim();
  if (!text) return '';

  let url;
  try {
    url = new URL(text);
  } catch (_) {
    throw new Error(`RPC URL 无效：${text}`);
  }

  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error(`RPC URL 只支持 http/https：${text}`);
  }

  // URL#hash 不应该出现在 JSON-RPC endpoint 中。
  url.hash = '';
  return url.toString().replace(/\/+$/, '');
}

function splitRpcUrlText(text) {
  return String(text || '')
    .split(/[\n,]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function normalizeRpcUrlEntry(item, index = 0, strict = true) {
  let name = '';
  let urlValue = '';
  let enabled = true;

  if (typeof item === 'string') {
    urlValue = item;
  } else if (item && typeof item === 'object') {
    urlValue = item.url || item.rpcUrl || item.endpoint || '';
    name = String(item.name || item.label || '').trim().slice(0, 64);
    enabled = parseBooleanFlag(item.enabled, true);
  } else if (strict) {
    throw new Error(`第 ${index + 1} 个 RPC 配置无效`);
  }

  let url = '';
  try {
    url = normalizeRpcUrl(urlValue);
  } catch (error) {
    if (strict) throw error;
    return null;
  }

  if (!url) {
    if (strict) throw new Error(`第 ${index + 1} 个 RPC URL 不能为空`);
    return null;
  }

  return {
    name: name || `RPC ${index + 1}`,
    url,
    enabled,
  };
}

function parseRpcUrlInput(value) {
  if (Array.isArray(value)) return value;
  if (value && typeof value === 'object') return [value];

  const raw = String(value || '').trim();
  if (!raw) return [];

  if (raw.startsWith('[') || raw.startsWith('{')) {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [parsed];
  }

  return splitRpcUrlText(raw);
}

function normalizeRpcUrlsForBootstrap(value) {
  const fallback = [{ name: 'RPC 1', url: DEFAULT_RPC_URL, enabled: true }];
  try {
    const input = parseRpcUrlInput(value);
    const result = [];
    const seen = new Set();
    input.forEach((item, index) => {
      const entry = normalizeRpcUrlEntry(item, index, false);
      if (!entry || seen.has(entry.url)) return;
      seen.add(entry.url);
      result.push({ ...entry, name: entry.name || `RPC ${result.length + 1}` });
    });
    return result.length ? result : fallback;
  } catch (_) {
    return fallback;
  }
}

function normalizeRpcUrlsSetting(value) {
  const input = parseRpcUrlInput(value);
  if (!input.length) throw new Error('至少需要配置一个 RPC URL');
  if (input.length > 20) throw new Error('RPC URL 最多配置 20 个');

  const result = [];
  const seen = new Set();
  input.forEach((item, index) => {
    const entry = normalizeRpcUrlEntry(item, index, true);
    if (seen.has(entry.url)) return;
    seen.add(entry.url);
    result.push({ ...entry, name: entry.name || `RPC ${result.length + 1}` });
  });

  if (!result.some((entry) => entry.enabled)) {
    throw new Error('至少需要启用一个 RPC URL');
  }
  return result;
}

function serializeSettingValue(value, type) {
  if (type === 'json') return JSON.stringify(value ?? null);
  if (type === 'number') return String(Number(value) || 0);
  return String(value ?? '');
}

function normalizeSettingString(value, definition) {
  let text = String(value ?? '').trim();
  if (definition.key === 'publicHost' || definition.key === 'appUploadUrlBase') {
    text = text.replace(/\/+$/, '');
  }
  if (definition.key === 'appUploadPublicPath' || definition.key === 'tokenIconPublicPath') {
    text = normalizePublicPath(text || definition.defaultValue);
    if (!text.startsWith('/uploads')) {
      throw new Error(`${definition.label}必须以 /uploads 开头，避免覆盖管理后台或业务接口`);
    }
  }
  if (definition.key === 'appUploadDir' || definition.key === 'tokenIconDir') {
    if (!text) text = definition.defaultValue;
    text = path.resolve(text);
  }
  if (definition.key === 'redPacketContract') {
    const normalized = normalizeAddress(text);
    if (!normalized) throw new Error('红包合约地址无效');
    text = normalized;
  }
  if (definition.required && !text) throw new Error(`${definition.label}不能为空`);
  if (definition.maxLength && text.length > definition.maxLength) {
    throw new Error(`${definition.label}不能超过 ${definition.maxLength} 个字符`);
  }
  return text;
}

function normalizeSettingNumber(value, definition) {
  const raw = value === '' || value === null || value === undefined ? definition.defaultValue : value;
  const number = Number(raw);
  if (!Number.isFinite(number)) throw new Error(`${definition.label}必须是数字`);
  const integer = Math.trunc(number);
  if (definition.min !== undefined && integer < definition.min) {
    throw new Error(`${definition.label}不能小于 ${definition.min}`);
  }
  if (definition.max !== undefined && integer > definition.max) {
    throw new Error(`${definition.label}不能大于 ${definition.max}`);
  }
  return integer;
}

function decimalNumberToPlainString(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) return '';
  if (number === 0) return '0';
  if (number >= 1e-6 && number < 1e21) {
    const text = String(number);
    return text.includes('e') || text.includes('E')
      ? number.toFixed(18).replace(/0+$/, '').replace(/\.$/, '')
      : text;
  }
  return number.toFixed(24).replace(/0+$/, '').replace(/\.$/, '');
}

function normalizeTokenPriceUsd(value, fallback = '0') {
  if (value === undefined || value === null || value === '') return String(fallback);
  const text = String(value).trim().replace(/,/g, '');
  if (!text) return String(fallback);
  const number = Number(text);
  if (!Number.isFinite(number) || number < 0) return String(fallback);

  let normalized = '';
  if (/[eE]/.test(text)) {
    normalized = decimalNumberToPlainString(number);
  } else if (/^(?:\d+|\d*\.\d+)$/.test(text)) {
    normalized = text.replace(/^0+(?=\d)/, '') || '0';
    if (normalized.startsWith('.')) normalized = `0${normalized}`;
  } else {
    normalized = decimalNumberToPlainString(number);
  }
  return normalized || String(fallback);
}

function tokenPriceUsdFromItem(item) {
  if (!item || typeof item !== 'object') return '0';
  return normalizeTokenPriceUsd(item.priceUsd ?? item.usdPrice ?? item.price ?? item.price_usd, '0');
}

function normalizeTokenIconUrlForStorage(value) {
  const text = String(value ?? '').trim();
  if (!text) return '';
  return text.slice(0, 1024);
}

function normalizeTokenIconRegistrySetting(value) {
  let tokens = value;
  if (typeof tokens === 'string') {
    const raw = tokens.trim();
    tokens = raw ? JSON.parse(raw) : [];
  }

  // 支持两种配置格式：
  // 1) [{ symbol, contractAddress, iconUrl, decimals, priceUsd }]
  // 2) { "0x...": "etz.png", "0x...": { "symbol": "ETZ", "iconUrl": "etz.png" } }
  if (tokens && typeof tokens === 'object' && !Array.isArray(tokens)) {
    if (Array.isArray(tokens.tokens)) {
      tokens = tokens.tokens;
    } else {
      tokens = Object.entries(tokens).map(([contractAddress, item]) => {
        if (item && typeof item === 'object') {
          return { contractAddress, ...item };
        }
        return { contractAddress, iconUrl: item };
      });
    }
  }

  if (!Array.isArray(tokens)) throw new Error('自定义代币图标库必须是数组或对象');
  if (tokens.length > 500) throw new Error('自定义代币图标库最多配置 500 个');

  const result = [];
  const seen = new Set();
  tokens.forEach((item, index) => {
    if (!item || typeof item !== 'object') throw new Error(`第 ${index + 1} 个代币图标配置无效`);
    const symbol = String(item.symbol || item.tokenSymbol || '').trim().slice(0, 32);
    const contractAddressRaw = String(item.contractAddress || item.tokenAddress || item.address || '').trim();
    const contractAddress = normalizeAddress(contractAddressRaw);
    if (!contractAddress) throw new Error(`第 ${index + 1} 个代币 contractAddress 无效`);

    const decimalsRaw = item.decimals ?? item.tokenDecimals;
    let decimals = Number(decimalsRaw);
    if (!Number.isInteger(decimals) || decimals < 0 || decimals > 36) decimals = 18;

    const priceUsd = tokenPriceUsdFromItem(item);
    const iconUrl = normalizeTokenIconUrlForStorage(item.iconUrl ?? item.icon_url ?? item.logoUrl ?? item.logo ?? item.imageUrl ?? item.image ?? '');
    if (!iconUrl) throw new Error(`第 ${index + 1} 个代币 iconUrl 不能为空`);

    if (seen.has(contractAddress)) return;
    seen.add(contractAddress);
    result.push({ symbol, contractAddress, decimals, priceUsd, iconUrl });
  });
  return result;
}

function parseTokenIconRegistryForBootstrap(value) {
  try {
    return normalizeTokenIconRegistrySetting(value);
  } catch (_) {
    return [];
  }
}

const TOKEN_PRICE_PROVIDER_ALLOW_LIST = new Set(['dexscreener', 'defillama', 'coingecko']);

function normalizeTokenPriceProviderOrderSetting(value) {
  let providers = value;
  if (typeof providers === 'string') {
    const raw = providers.trim();
    if (!raw) return ['dexscreener', 'defillama', 'coingecko'];
    if (raw.startsWith('[')) providers = JSON.parse(raw);
    else providers = raw.split(/[\n,]+/).map((item) => item.trim()).filter(Boolean);
  }
  if (!Array.isArray(providers)) throw new Error('行情价格来源优先级必须是数组');
  const result = [];
  const seen = new Set();
  providers.forEach((item) => {
    const provider = String(item || '').trim().toLowerCase();
    if (!TOKEN_PRICE_PROVIDER_ALLOW_LIST.has(provider) || seen.has(provider)) return;
    seen.add(provider);
    result.push(provider);
  });
  return result.length ? result : ['dexscreener', 'defillama', 'coingecko'];
}

function normalizeTokenPriceProviderOrderForBootstrap(value) {
  try {
    return normalizeTokenPriceProviderOrderSetting(value);
  } catch (_) {
    return ['dexscreener', 'defillama', 'coingecko'];
  }
}

function normalizeTokenPriceRegistrySetting(value) {
  let tokens = value;
  if (typeof tokens === 'string') {
    const raw = tokens.trim();
    tokens = raw ? JSON.parse(raw) : [];
  }

  // 支持：
  // 1) [{ symbol, contractAddress, priceUsd }]
  // 2) { "0x...": "0.123", "0x...": { "symbol": "ABC", "priceUsd": "0.123" } }
  if (tokens && typeof tokens === 'object' && !Array.isArray(tokens)) {
    if (Array.isArray(tokens.tokens)) {
      tokens = tokens.tokens;
    } else {
      tokens = Object.entries(tokens).map(([contractAddress, item]) => {
        if (item && typeof item === 'object') return { contractAddress, ...item };
        return { contractAddress, priceUsd: item };
      });
    }
  }

  if (!Array.isArray(tokens)) throw new Error('自定义代币价格库必须是数组或对象');
  if (tokens.length > 1000) throw new Error('自定义代币价格库最多配置 1000 个');

  const result = [];
  const seen = new Set();
  tokens.forEach((item, index) => {
    if (!item || typeof item !== 'object') throw new Error(`第 ${index + 1} 个代币价格配置无效`);
    const symbol = String(item.symbol || item.tokenSymbol || '').trim().slice(0, 32);
    const contractAddressRaw = String(item.contractAddress || item.tokenAddress || item.address || '').trim();
    const contractAddress = normalizeAddress(contractAddressRaw);
    if (!contractAddress && !symbol) throw new Error(`第 ${index + 1} 个代币价格配置必须填写 contractAddress 或 symbol`);
    const priceUsd = tokenPriceUsdFromItem(item);
    if (Number(priceUsd) <= 0) throw new Error(`第 ${index + 1} 个代币 priceUsd 必须大于 0`);
    const key = contractAddress || `symbol:${symbol.toUpperCase()}`;
    if (seen.has(key)) return;
    seen.add(key);
    result.push({ symbol, contractAddress, tokenAddress: contractAddress, priceUsd });
  });
  return result;
}

function parseTokenPriceRegistryForBootstrap(value) {
  try {
    return normalizeTokenPriceRegistrySetting(value);
  } catch (_) {
    return [];
  }
}

function normalizeWalletTokensSetting(value) {
  let tokens = value;
  if (typeof tokens === 'string') {
    const raw = tokens.trim();
    tokens = raw ? JSON.parse(raw) : [];
  }
  if (!Array.isArray(tokens)) throw new Error('默认钱包代币列表必须是数组');
  if (tokens.length > 50) throw new Error('默认钱包代币最多配置 50 个');

  return tokens.map((item, index) => {
    if (!item || typeof item !== 'object') throw new Error(`第 ${index + 1} 个代币配置无效`);
    const symbol = String(item.symbol || '').trim().slice(0, 32);
    const contractAddressRaw = String(item.contractAddress || item.tokenAddress || '').trim();
    const contractAddress = normalizeAddress(contractAddressRaw);
    const decimals = Number(item.decimals);
    if (!symbol) throw new Error(`第 ${index + 1} 个代币 symbol 不能为空`);
    if (!contractAddress) throw new Error(`第 ${index + 1} 个代币 contractAddress 无效`);
    if (!Number.isInteger(decimals) || decimals < 0 || decimals > 36) {
      throw new Error(`第 ${index + 1} 个代币 decimals 必须是 0-36 的整数`);
    }
    const priceUsd = tokenPriceUsdFromItem(item);
    const iconUrl = normalizeTokenIconUrlForStorage(item.iconUrl ?? item.icon_url ?? item.logoUrl ?? item.logo ?? item.imageUrl ?? item.image ?? '');
    return { symbol, contractAddress, decimals, priceUsd, iconUrl };
  });
}

function normalizeSettingValueForStorage(key, value) {
  const definition = RUNTIME_SETTING_DEFINITION_MAP.get(key);
  if (!definition) throw new Error(`未知参数：${key}`);

  if (definition.type === 'number') return normalizeSettingNumber(value, definition);
  if (definition.type === 'json') {
    if (key === 'walletTokens') return normalizeWalletTokensSetting(value);
    if (key === 'tokenIconRegistry') return normalizeTokenIconRegistrySetting(value);
    if (key === 'tokenPriceRegistry') return normalizeTokenPriceRegistrySetting(value);
    if (key === 'tokenPriceProviderOrder') return normalizeTokenPriceProviderOrderSetting(value);
    if (key === 'rpcUrls') return normalizeRpcUrlsSetting(value);
    return typeof value === 'string' ? JSON.parse(value) : value;
  }
  return normalizeSettingString(value, definition);
}

function parseSettingStoredValue(value, definition) {
  try {
    if (definition.type === 'number') return normalizeSettingNumber(value, definition);
    if (definition.type === 'json') return normalizeSettingValueForStorage(definition.key, value);
    return normalizeSettingString(value, definition);
  } catch (_) {
    return definition.defaultValue;
  }
}

function buildRuntimeSettingsFromRows(rows = []) {
  const rowMap = new Map(rows.map((row) => [row.setting_key, row.setting_value]));
  const settings = {};

  for (const definition of RUNTIME_SETTING_DEFINITIONS) {
    const rawValue = rowMap.has(definition.key) ? rowMap.get(definition.key) : definition.defaultValue;
    settings[definition.key] = parseSettingStoredValue(rawValue, definition);
  }

  settings.maxExpiresInSeconds = Math.min(
    Math.max(Number(settings.maxExpiresInSeconds || BOOTSTRAP_MAX_EXPIRES_IN_SECONDS), 1),
    CONTRACT_MAX_EXPIRES_IN_SECONDS,
  );
  settings.maxApkUploadMB = Math.min(Math.max(Number(settings.maxApkUploadMB || 1), 1), 2048);
  settings.maxApkUploadBytes = settings.maxApkUploadMB * 1024 * 1024;
  settings.appUploadPublicPath = normalizePublicPath(settings.appUploadPublicPath || BOOTSTRAP_APP_UPLOAD_PUBLIC_PATH);
  settings.appUploadDir = path.resolve(settings.appUploadDir || BOOTSTRAP_APP_UPLOAD_DIR);
  settings.tokenIconPublicPath = normalizePublicPath(settings.tokenIconPublicPath || BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH);
  settings.tokenIconDir = path.resolve(settings.tokenIconDir || BOOTSTRAP_TOKEN_ICON_DIR);
  settings.bnbIconUrl = normalizeTokenIconUrlForStorage(settings.bnbIconUrl || '');
  settings.tokenIconRegistry = Array.isArray(settings.tokenIconRegistry) ? settings.tokenIconRegistry : [];
  settings.tokenPriceRegistry = Array.isArray(settings.tokenPriceRegistry) ? settings.tokenPriceRegistry : [];
  settings.tokenPriceAutoEnabled = Number(settings.tokenPriceAutoEnabled ?? 1) === 1 ? 1 : 0;
  settings.tokenPriceExternalTtlSeconds = Math.min(Math.max(Number(settings.tokenPriceExternalTtlSeconds || BOOTSTRAP_TOKEN_PRICE_EXTERNAL_TTL_SECONDS), 30), 86400);
  try {
    settings.tokenPriceProviderOrder = normalizeTokenPriceProviderOrderSetting(settings.tokenPriceProviderOrder);
  } catch (_) {
    settings.tokenPriceProviderOrder = BOOTSTRAP_TOKEN_PRICE_PROVIDER_ORDER;
  }
  settings.publicHost = String(settings.publicHost || BOOTSTRAP_PUBLIC_HOST).replace(/\/+$/, '');
  settings.appUploadUrlBase = String(settings.appUploadUrlBase || '').trim().replace(/\/+$/, '');
  try {
    settings.rpcUrls = normalizeRpcUrlsSetting(settings.rpcUrls);
  } catch (_) {
    settings.rpcUrls = BOOTSTRAP_RPC_URLS;
  }
  settings.redPacketContract = normalizeAddress(settings.redPacketContract) || BOOTSTRAP_RED_PACKET_CONTRACT;
  settings.walletTokens = Array.isArray(settings.walletTokens) ? settings.walletTokens : BUILTIN_DEFAULT_WALLET_TOKENS;
  return settings;
}

function publicRuntimeSettingsForAdmin(settings) {
  const result = {};
  for (const definition of RUNTIME_SETTING_DEFINITIONS) {
    result[definition.key] = settings[definition.key];
  }
  return result;
}

function runtimeSettingDefinitionsForAdmin() {
  return RUNTIME_SETTING_DEFINITIONS.map((definition) => ({
    key: definition.key,
    group: definition.group,
    label: definition.label,
    type: definition.type,
    required: Boolean(definition.required),
    min: definition.min,
    max: definition.max,
    maxLength: definition.maxLength,
    description: definition.description,
  }));
}

const runtimeSettingsCache = {
  values: null,
  expiresAt: 0,
};

function clearRuntimeSettingsCache() {
  runtimeSettingsCache.values = null;
  runtimeSettingsCache.expiresAt = 0;
}

async function getRuntimeSettings(force = false) {
  const now = Date.now();
  if (!force && runtimeSettingsCache.values && runtimeSettingsCache.expiresAt > now) {
    return runtimeSettingsCache.values;
  }

  let rows = [];
  try {
    rows = await db.getRuntimeSettingsRows();
  } catch (error) {
    // eslint-disable-next-line no-console
    console.error('[runtime-settings-db-error]', error);
  }

  const values = buildRuntimeSettingsFromRows(rows);
  runtimeSettingsCache.values = values;
  runtimeSettingsCache.expiresAt = now + 15_000;
  return values;
}

async function ensureRuntimeDirectories() {
  try {
    const settings = await getRuntimeSettings(true);
    await fs.promises.mkdir(path.resolve(settings.appUploadDir || BOOTSTRAP_APP_UPLOAD_DIR), { recursive: true });
    await fs.promises.mkdir(path.resolve(settings.tokenIconDir || BOOTSTRAP_TOKEN_ICON_DIR), { recursive: true });
  } catch (error) {
    // eslint-disable-next-line no-console
    console.warn('[runtime-directory-ensure-error]', error?.message || error);
  }
}

function sanitizeOriginalFilename(value) {
  const basename = path.basename(String(value || 'app.apk')).trim();
  const safe = basename.replace(/[^a-zA-Z0-9._()\-\u4e00-\u9fa5 ]/g, '_').slice(0, 180);
  return safe || 'app.apk';
}

function buildApkDownloadUrl(filename, settings) {
  const encodedName = encodeURIComponent(filename);
  const uploadUrlBase = String(settings?.appUploadUrlBase || '').trim().replace(/\/+$/, '');
  if (uploadUrlBase) return `${uploadUrlBase}/${encodedName}`;
  const publicHost = String(settings?.publicHost || BOOTSTRAP_PUBLIC_HOST).trim().replace(/\/+$/, '');
  const publicPath = normalizePublicPath(settings?.appUploadPublicPath || BOOTSTRAP_APP_UPLOAD_PUBLIC_PATH);
  return `${publicHost}${publicPath}/${encodedName}`;
}

function createClientUploadError(message, statusCode = 400) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

function formatUploadSizeLimit(bytes) {
  const value = Number(bytes || 0);
  if (value >= 1024 * 1024) return `${Math.floor(value / 1024 / 1024)}MB`;
  if (value >= 1024) return `${Math.floor(value / 1024)}KB`;
  return `${value}B`;
}

function readRequestBody(req, maxBytes, maxFileBytes = maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let settled = false;

    req.on('data', (chunk) => {
      if (settled) return;
      total += chunk.length;
      if (total > maxBytes) {
        settled = true;
        reject(createClientUploadError(`上传文件不能超过 ${formatUploadSizeLimit(maxFileBytes)}`, 413));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on('error', (error) => {
      if (!settled) {
        settled = true;
        reject(error);
      }
    });

    req.on('end', () => {
      if (!settled) {
        settled = true;
        resolve(Buffer.concat(chunks));
      }
    });
  });
}

function parseMultipartHeaders(headerText) {
  return String(headerText || '')
    .split(/\r?\n/)
    .reduce((acc, line) => {
      const index = line.indexOf(':');
      if (index <= 0) return acc;
      acc[line.slice(0, index).trim().toLowerCase()] = line.slice(index + 1).trim();
      return acc;
    }, {});
}

function parseContentDisposition(value) {
  const result = {};
  const text = String(value || '');
  const regex = /([a-zA-Z0-9_-]+)=(?:"((?:\\"|[^"])*)"|([^;]*))/g;
  let match;
  while ((match = regex.exec(text))) {
    result[match[1]] = String(match[2] ?? match[3] ?? '').replace(/\\"/g, '"');
  }
  return result;
}

async function parseMultipartForm(req, maxBytes, maxFileBytes = maxBytes) {
  const contentType = String(req.get('content-type') || '');
  const boundaryMatch = contentType.match(/multipart\/form-data\s*;\s*boundary=(?:"([^"]+)"|([^;]+))/i);
  const boundaryText = (boundaryMatch?.[1] || boundaryMatch?.[2] || '').trim();
  if (!boundaryText) throw createClientUploadError('请使用 multipart/form-data 上传文件');

  const body = await readRequestBody(req, maxBytes, maxFileBytes);
  const boundary = Buffer.from(`--${boundaryText}`);
  const headerSeparator = Buffer.from('\r\n\r\n');
  const fields = {};
  const files = [];
  let cursor = body.indexOf(boundary);

  while (cursor >= 0) {
    cursor += boundary.length;
    if (body[cursor] === 45 && body[cursor + 1] === 45) break;
    if (body[cursor] === 13 && body[cursor + 1] === 10) cursor += 2;

    const headerEnd = body.indexOf(headerSeparator, cursor);
    if (headerEnd < 0) break;
    const contentStart = headerEnd + headerSeparator.length;
    const nextBoundary = body.indexOf(boundary, contentStart);
    if (nextBoundary < 0) break;

    let contentEnd = nextBoundary;
    if (contentEnd >= 2 && body[contentEnd - 2] === 13 && body[contentEnd - 1] === 10) {
      contentEnd -= 2;
    }

    const headers = parseMultipartHeaders(body.slice(cursor, headerEnd).toString('utf8'));
    const disposition = parseContentDisposition(headers['content-disposition']);
    const fieldname = disposition.name;
    const content = body.slice(contentStart, contentEnd);

    if (fieldname) {
      if (Object.prototype.hasOwnProperty.call(disposition, 'filename')) {
        files.push({
          fieldname,
          originalName: sanitizeOriginalFilename(disposition.filename),
          mimeType: headers['content-type'] || 'application/octet-stream',
          size: content.length,
          buffer: content,
        });
      } else {
        fields[fieldname] = content.toString('utf8');
      }
    }

    cursor = nextBoundary;
  }

  return { fields, files };
}

async function saveUploadedApk(file, versionCode, settings) {
  if (!file || !file.buffer || !file.size) return null;
  const originalName = sanitizeOriginalFilename(file.originalName);
  if (!originalName.toLowerCase().endsWith('.apk')) {
    throw createClientUploadError('只支持上传 .apk 文件');
  }
  const maxApkUploadBytes = Number(settings?.maxApkUploadBytes || BOOTSTRAP_MAX_APK_UPLOAD_BYTES);
  if (file.size > maxApkUploadBytes) {
    throw createClientUploadError(`APK 文件不能超过 ${Math.floor(maxApkUploadBytes / 1024 / 1024)}MB`, 413);
  }

  const uploadDir = path.resolve(settings?.appUploadDir || BOOTSTRAP_APP_UPLOAD_DIR);
  await fs.promises.mkdir(uploadDir, { recursive: true });
  const sha256 = crypto.createHash('sha256').update(file.buffer).digest('hex');
  const filename = `app-v${versionCode}-${Date.now()}-${sha256.slice(0, 12)}.apk`;
  const filePath = path.join(uploadDir, filename);
  await fs.promises.writeFile(filePath, file.buffer);

  return {
    downloadUrl: buildApkDownloadUrl(filename, settings),
    apkFilename: filename,
    apkOriginalName: originalName,
    apkSizeBytes: file.size,
    apkSha256: sha256,
  };
}

function detectTokenIconExtension(buffer, originalName = '', mimeType = '') {
  const nameExt = path.extname(String(originalName || '')).toLowerCase().replace(/^\./, '');
  const normalizedNameExt = nameExt === 'jpeg' ? 'jpg' : nameExt;
  const mime = String(mimeType || '').toLowerCase();

  let magicExt = '';
  if (buffer && buffer.length >= 12) {
    if (buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) magicExt = 'png';
    else if (buffer[0] === 0xff && buffer[1] === 0xd8) magicExt = 'jpg';
    else if (buffer.slice(0, 6).toString('ascii') === 'GIF87a' || buffer.slice(0, 6).toString('ascii') === 'GIF89a') magicExt = 'gif';
    else if (buffer.slice(0, 4).toString('ascii') === 'RIFF' && buffer.slice(8, 12).toString('ascii') === 'WEBP') magicExt = 'webp';
  }

  if (magicExt) return magicExt;
  if (['png', 'jpg', 'webp', 'gif'].includes(normalizedNameExt) && mime.startsWith('image/')) return normalizedNameExt;
  return '';
}

async function saveUploadedTokenIcon(file, settings, fields = {}) {
  if (!file || !file.buffer || !file.size) return null;

  const maxBytes = BOOTSTRAP_MAX_TOKEN_ICON_UPLOAD_BYTES;
  if (file.size > maxBytes) {
    throw createClientUploadError(`代币图标不能超过 ${formatUploadSizeLimit(maxBytes)}`, 413);
  }

  const ext = detectTokenIconExtension(file.buffer, file.originalName, file.mimeType);
  if (!ext) {
    throw createClientUploadError('只支持 PNG、JPG、WebP、GIF 图片');
  }

  const uploadDir = path.resolve(settings?.tokenIconDir || BOOTSTRAP_TOKEN_ICON_DIR);
  await fs.promises.mkdir(uploadDir, { recursive: true });

  const sha256 = crypto.createHash('sha256').update(file.buffer).digest('hex');
  const requestedName = String(fields.filename || fields.fileName || fields.name || '').trim();
  let filename = '';
  if (requestedName) {
    const safeRequested = sanitizeOriginalFilename(requestedName);
    const base = safeRequested.replace(/\.[^.]*$/, '') || `token-${Date.now()}-${sha256.slice(0, 12)}`;
    filename = `${base}.${ext}`;
  } else {
    const contractAddress = normalizeAddress(fields.contractAddress || fields.tokenAddress || fields.address || '');
    filename = contractAddress ? `${contractAddress}.${ext}` : `token-${Date.now()}-${sha256.slice(0, 12)}.${ext}`;
  }
  const filePath = path.join(uploadDir, filename);
  await fs.promises.writeFile(filePath, file.buffer);

  return {
    filename,
    iconUrl: resolveTokenIconUrl(filename, settings),
    sizeBytes: file.size,
    sha256,
    mimeType: file.mimeType || `image/${ext === 'jpg' ? 'jpeg' : ext}`,
    publicPath: normalizePublicPath(settings?.tokenIconPublicPath || BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH),
  };
}

function getEventTokenAddress(event) {
  const token = event?.args?.token;
  if (token === undefined || token === null) return '';
  return normalizeAddress(String(token));
}

function getPacketTokenAddress(packet) {
  return normalizeAddress(packet?.tokenAddress) || ZERO_ADDRESS;
}

function eventTokenMatchesPacket(event, packet) {
  const eventToken = getEventTokenAddress(event);
  return !eventToken || eventToken === getPacketTokenAddress(packet);
}

function getExpectedRefundAmountWei(packet) {
  try {
    return (BigInt(String(packet.amountPerClaimWei || '0')) * BigInt(Math.max(Number(packet.remainingCount || 0), 0))).toString();
  } catch (_) {
    return '0';
  }
}

function assertPacketIdHex(packetIdHex) {
  const normalized = String(packetIdHex || '').trim().toLowerCase();
  if (!/^0x[0-9a-f]{64}$/.test(normalized)) {
    throw new Error('packetIdHex invalid');
  }
  return normalized;
}

function getCreateAuthorizationDigest(packet, creatorAddress) {
  const packetIdHex = assertPacketIdHex(packet?.packetIdHex);
  const contractAddress = normalizeAddress(packet?.contractAddress);
  const creator = normalizeAddress(creatorAddress || packet?.creatorWallet);
  const token = getPacketTokenAddress(packet);
  if (!contractAddress) throw new Error('contractAddress invalid');
  if (!creator) throw new Error('creatorAddress invalid');

  return solidityPackedKeccak256(
    ['bytes32', 'uint256', 'address', 'bytes32', 'address', 'address', 'uint256', 'uint32', 'uint64'],
    [
      solidityPackedKeccak256(['string'], ['TelegramRedPacketV2:CREATE']),
      CHAIN_ID,
      getAddress(contractAddress),
      packetIdHex,
      getAddress(creator),
      getAddress(token),
      BigInt(String(packet?.totalAmountWei || '0')),
      Number(packet?.count || 0),
      Number(packet?.expiresAt || 0),
    ],
  );
}

function getClaimAuthorizationDigest(packet, claimerAddress) {
  const packetIdHex = assertPacketIdHex(packet?.packetIdHex);
  const contractAddress = normalizeAddress(packet?.contractAddress);
  const claimer = normalizeAddress(claimerAddress);
  if (!contractAddress) throw new Error('contractAddress invalid');
  if (!claimer) throw new Error('claimerAddress invalid');

  return solidityPackedKeccak256(
    ['bytes32', 'uint256', 'address', 'bytes32', 'address'],
    [
      solidityPackedKeccak256(['string'], ['TelegramRedPacketV2:CLAIM']),
      CHAIN_ID,
      getAddress(contractAddress),
      packetIdHex,
      getAddress(claimer),
    ],
  );
}

async function signRedPacketDigest(digest) {
  if (!claimSignerWallet) {
    throw new Error('RED_PACKET_AUTH_SIGNER_PRIVATE_KEY is not configured');
  }
  return claimSignerWallet.signMessage(getBytes(digest));
}

async function signCreateAuthorization(packet, creatorAddress) {
  return {
    signatureHex: await signRedPacketDigest(getCreateAuthorizationDigest(packet, creatorAddress)),
    claimSignerAddress: claimSignerWallet.address,
  };
}

async function signClaimAuthorization(packet, claimerAddress) {
  return {
    signatureHex: await signRedPacketDigest(getClaimAuthorizationDigest(packet, claimerAddress)),
    claimSignerAddress: claimSignerWallet.address,
  };
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
    // await this.pool.query(`
    //   ALTER TABLE red_packet_claims
    //   ADD COLUMN IF NOT EXISTS claimer_name VARCHAR(255) NOT NULL DEFAULT '' AFTER claimer_address
    // `);

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

    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS client_app_versions (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        version_code INT UNSIGNED NOT NULL,
        version_name VARCHAR(64) NOT NULL,
        release_notes TEXT NOT NULL,
        download_url VARCHAR(1024) NOT NULL,
        apk_filename VARCHAR(255) NOT NULL DEFAULT '',
        apk_original_name VARCHAR(255) NOT NULL DEFAULT '',
        apk_size_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
        apk_sha256 VARCHAR(64) NOT NULL DEFAULT '',
        force_update TINYINT(1) NOT NULL DEFAULT 0,
        enabled TINYINT(1) NOT NULL DEFAULT 1,
        release_date BIGINT NOT NULL,
        created_by VARCHAR(128) NOT NULL DEFAULT '',
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uniq_version_code (version_code),
        KEY idx_enabled_code (enabled, version_code),
        KEY idx_created_at (created_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);

    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS system_settings (
        setting_key VARCHAR(128) NOT NULL,
        setting_value TEXT NOT NULL,
        value_type VARCHAR(32) NOT NULL DEFAULT 'string',
        label VARCHAR(128) NOT NULL DEFAULT '',
        description VARCHAR(512) NOT NULL DEFAULT '',
        updated_by VARCHAR(128) NOT NULL DEFAULT '',
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (setting_key),
        KEY idx_updated_at (updated_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
  }

  async ensureSettingDefaults() {
    const current = nowSeconds();
    for (const definition of RUNTIME_SETTING_DEFINITIONS) {
      await this.pool.query(
        `INSERT IGNORE INTO system_settings (
          setting_key, setting_value, value_type, label, description, updated_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          definition.key,
          serializeSettingValue(definition.defaultValue, definition.type),
          definition.type,
          definition.label,
          definition.description || '',
          'env-bootstrap',
          current,
          current,
        ],
      );
    }

    const tokenPriceTtlDefinition = RUNTIME_SETTING_DEFINITION_MAP.get('tokenPriceExternalTtlSeconds');
    if (tokenPriceTtlDefinition) {
      await this.pool.query(
        `UPDATE system_settings
         SET setting_value = ?, label = ?, description = ?, updated_at = ?
         WHERE setting_key = 'tokenPriceExternalTtlSeconds'
           AND updated_by = 'env-bootstrap'
           AND setting_value = '300'`,
        [
          serializeSettingValue(tokenPriceTtlDefinition.defaultValue, tokenPriceTtlDefinition.type),
          tokenPriceTtlDefinition.label,
          tokenPriceTtlDefinition.description || '',
          current,
        ],
      );
      clearRuntimeSettingsCache();
    }
  }

  async getRuntimeSettingsRows() {
    const [rows] = await this.pool.query(
      `SELECT setting_key, setting_value, value_type, label, description, updated_by, created_at, updated_at
       FROM system_settings`,
    );
    return rows;
  }

  async saveRuntimeSettings(values, updatedBy = '') {
    const current = nowSeconds();
    const entries = Object.entries(values || {}).filter(([key]) => RUNTIME_SETTING_DEFINITION_MAP.has(key));
    for (const [key, rawValue] of entries) {
      const definition = RUNTIME_SETTING_DEFINITION_MAP.get(key);
      const normalizedValue = normalizeSettingValueForStorage(key, rawValue);
      await this.pool.query(
        `INSERT INTO system_settings (
          setting_key, setting_value, value_type, label, description, updated_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          setting_value = VALUES(setting_value),
          value_type = VALUES(value_type),
          label = VALUES(label),
          description = VALUES(description),
          updated_by = VALUES(updated_by),
          updated_at = VALUES(updated_at)`,
        [
          key,
          serializeSettingValue(normalizedValue, definition.type),
          definition.type,
          definition.label,
          definition.description || '',
          updatedBy,
          current,
          current,
        ],
      );
    }
    clearRuntimeSettingsCache();
    return getRuntimeSettings(true);
  }

  async getPacket(packetId) {
    const [rows] = await this.pool.query(
      'SELECT * FROM red_packets WHERE packet_id = ? OR packet_id_hex = ? LIMIT 1',
      [packetId, packetId],
    );
    if (!rows.length) return null;
    const row = rows[0];
    const [claims] = await this.pool.query(
      'SELECT claimer_address FROM red_packet_claims WHERE packet_id = ? ORDER BY id ASC',
      [row.packet_id],
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
        const [existingRefunds] = await conn.query(
          'SELECT id FROM red_packet_refunds WHERE packet_id = ? AND tx_hash = ? LIMIT 1',
          [packet.packetId, txHash],
        );
        if (existingRefunds.length) {
          await conn.commit();
          return this.getPacket(packet.packetId);
        }
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

  async getRefundByPacketTx(packetId, txHash) {
    const [rows] = await this.pool.query(
      `SELECT id, packet_id, creator_address, tx_hash, amount_wei, created_at
       FROM red_packet_refunds
       WHERE packet_id = ? AND tx_hash = ?
       LIMIT 1`,
      [packetId, txHash],
    );
    return rows[0] || null;
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

  async getSendRecordsByCreator(creatorWallet, status = '', limit = 100, offset = 0) {
    const normalized = normalizeAddress(creatorWallet);
    if (!normalized) {
      return [];
    }
    const safeLimit = Math.min(Math.max(Number(limit) || 20, 1), 200);
    const safeOffset = Math.max(Number(offset) || 0, 0);
    const current = nowSeconds();
    const where = ["p.creator_wallet = ?", "p.status <> 'pending_create_confirm'"];
    const params = [normalized];
    const safeStatus = normalizeAdminStatus(status);
    if (safeStatus === 'refunded') {
      where.push("p.status = 'refunded'");
    } else if (safeStatus === 'empty') {
      where.push("p.status <> 'refunded' AND p.remaining_count <= 0");
    } else if (safeStatus === 'expired') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at < ?");
      params.push(current);
    } else if (safeStatus === 'active') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at >= ? AND p.onchain_created = 1");
      params.push(current);
    }
    const [rows] = await this.pool.query(
      `SELECT p.packet_id, p.token_symbol, p.total_amount_wei, p.count_total, p.expires_at, p.created_at, p.create_tx_hash, p.greeting,
        CASE
          WHEN p.status = 'refunded' THEN 'refunded'
          WHEN p.remaining_count <= 0 THEN 'empty'
          WHEN p.expires_at < ? THEN 'expired'
          WHEN p.onchain_created = 0 THEN 'pending_create_confirm'
          ELSE 'active'
        END AS status
       FROM red_packets p
       WHERE ${where.join(' AND ')}
       ORDER BY p.created_at DESC
       LIMIT ? OFFSET ?`,
      [current, ...params, safeLimit, safeOffset],
    );
    return rows.map((row) => ({
      packetId: row.packet_id,
      tokenSymbol: row.token_symbol || 'BNB',
      totalAmount: row.total_amount_wei,
      count: Number(row.count_total || 0),
      status: String(row.status || '').toUpperCase(),
      createdAt: Number(row.created_at || 0) * 1000,
      expiresAt: Number(row.expires_at || 0) * 1000,
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
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at < ?");
      params.push(current);
    } else if (status === 'pending_create_confirm') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at >= ? AND p.onchain_created = 0");
      params.push(current);
    } else if (status === 'active') {
      where.push("p.status <> 'refunded' AND p.remaining_count > 0 AND p.expires_at >= ? AND p.onchain_created = 1");
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
        SUM(CASE WHEN status <> 'refunded' AND remaining_count > 0 AND expires_at < ? THEN 1 ELSE 0 END) AS expiredPackets,
        SUM(CASE WHEN status <> 'refunded' AND remaining_count > 0 AND expires_at >= ? AND onchain_created = 0 THEN 1 ELSE 0 END) AS pendingPackets,
        SUM(CASE WHEN status <> 'refunded' AND remaining_count > 0 AND expires_at >= ? AND onchain_created = 1 THEN 1 ELSE 0 END) AS activePackets,
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
          WHEN p.expires_at < ? THEN 'expired'
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
          WHEN p.expires_at < ? THEN 'expired'
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

  async getClientVersionPage(options = {}) {
    const page = Math.max(Number(options.page) || 1, 1);
    const safeLimit = Math.min(Math.max(Number(options.pageSize) || 20, 1), 200);
    const safeOffset = Math.max(Number(options.offset) || ((page - 1) * safeLimit), 0);
    const where = [];
    const params = [];

    const search = String(options.search || '').trim();
    if (search) {
      const term = `%${search}%`;
      where.push(`(CAST(version_code AS CHAR) LIKE ? OR version_name LIKE ? OR release_notes LIKE ? OR apk_original_name LIKE ? OR apk_sha256 LIKE ?)`);
      params.push(term, term, term, term, term);
    }

    if (options.enabled === '0' || options.enabled === '1') {
      where.push('enabled = ?');
      params.push(Number(options.enabled));
    }

    const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
    const [countRows] = await this.pool.query(
      `SELECT COUNT(*) AS total FROM client_app_versions ${whereSql}`,
      params,
    );
    const [rows] = await this.pool.query(
      `SELECT id, version_code, version_name, release_notes, download_url, apk_filename,
              apk_original_name, apk_size_bytes, apk_sha256, force_update, enabled,
              release_date, created_by, created_at, updated_at
       FROM client_app_versions
       ${whereSql}
       ORDER BY version_code DESC, created_at DESC, id DESC
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

  async getClientVersionByCode(versionCode) {
    const [rows] = await this.pool.query(
      `SELECT id, version_code, version_name, release_notes, download_url, apk_filename,
              apk_original_name, apk_size_bytes, apk_sha256, force_update, enabled,
              release_date, created_by, created_at, updated_at
       FROM client_app_versions
       WHERE version_code = ?
       LIMIT 1`,
      [versionCode],
    );
    return rows[0] || null;
  }

  async getClientVersionById(id) {
    const [rows] = await this.pool.query(
      `SELECT id, version_code, version_name, release_notes, download_url, apk_filename,
              apk_original_name, apk_size_bytes, apk_sha256, force_update, enabled,
              release_date, created_by, created_at, updated_at
       FROM client_app_versions
       WHERE id = ?
       LIMIT 1`,
      [id],
    );
    return rows[0] || null;
  }

  async getLatestClientVersion() {
    const [rows] = await this.pool.query(
      `SELECT id, version_code, version_name, release_notes, download_url, apk_filename,
              apk_original_name, apk_size_bytes, apk_sha256, force_update, enabled,
              release_date, created_by, created_at, updated_at
       FROM client_app_versions
       WHERE enabled = 1
       ORDER BY version_code DESC, id DESC
       LIMIT 1`,
    );
    return rows[0] || null;
  }

  async saveClientVersion(version) {
    const current = nowSeconds();
    await this.pool.query(
      `INSERT INTO client_app_versions (
        version_code, version_name, release_notes, download_url, apk_filename,
        apk_original_name, apk_size_bytes, apk_sha256, force_update, enabled,
        release_date, created_by, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON DUPLICATE KEY UPDATE
        version_name = VALUES(version_name),
        release_notes = VALUES(release_notes),
        download_url = VALUES(download_url),
        apk_filename = VALUES(apk_filename),
        apk_original_name = VALUES(apk_original_name),
        apk_size_bytes = VALUES(apk_size_bytes),
        apk_sha256 = VALUES(apk_sha256),
        force_update = VALUES(force_update),
        enabled = VALUES(enabled),
        release_date = VALUES(release_date),
        created_by = VALUES(created_by),
        updated_at = VALUES(updated_at)`,
      [
        version.versionCode,
        version.versionName,
        version.releaseNotes,
        version.downloadUrl,
        version.apkFilename,
        version.apkOriginalName,
        version.apkSizeBytes,
        version.apkSha256,
        version.forceUpdate ? 1 : 0,
        version.enabled ? 1 : 0,
        version.releaseDate || current,
        version.createdBy || '',
        current,
        current,
      ],
    );
    return this.getClientVersionByCode(version.versionCode);
  }

  async setClientVersionEnabled(id, enabled) {
    await this.pool.query(
      `UPDATE client_app_versions
       SET enabled = ?, updated_at = ?
       WHERE id = ?`,
      [enabled ? 1 : 0, nowSeconds(), id],
    );
    return this.getClientVersionById(id);
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

const rpcProviderCache = new Map();
const rpcHealthCache = {
  key: '',
  values: null,
  expiresAt: 0,
};

function getEnabledRpcEndpoints(settings) {
  const list = Array.isArray(settings?.rpcUrls) ? settings.rpcUrls : BOOTSTRAP_RPC_URLS;
  const enabled = list
    .map((item, index) => normalizeRpcUrlEntry(item, index, false))
    .filter((item) => item && item.enabled && item.url);
  return enabled.length ? enabled : BOOTSTRAP_RPC_URLS;
}

function getRpcEndpointKey(endpoints) {
  return JSON.stringify((endpoints || []).map((endpoint) => ({ url: endpoint.url, enabled: endpoint.enabled !== false })));
}

function getProviderForRpcUrl(rpcUrl) {
  const url = normalizeRpcUrl(rpcUrl);
  let provider = rpcProviderCache.get(url);
  if (!provider) {
    provider = new JsonRpcProvider(url, CHAIN_ID);
    rpcProviderCache.set(url, provider);
  }
  return provider;
}

function withTimeout(promise, timeoutMs, message = 'timeout') {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), timeoutMs);
    Promise.resolve(promise)
      .then((value) => {
        clearTimeout(timer);
        resolve(value);
      })
      .catch((error) => {
        clearTimeout(timer);
        reject(error);
      });
  });
}

function parseRpcQuantity(value) {
  try {
    if (typeof value === 'number') return value;
    if (typeof value === 'bigint') return Number(value);
    const text = String(value || '').trim();
    if (!text) return 0;
    return Number(text.startsWith('0x') || text.startsWith('0X') ? BigInt(text) : BigInt(text));
  } catch (_) {
    return 0;
  }
}

async function probeRpcEndpoint(endpoint, timeoutMs = 3500) {
  const startedAt = Date.now();
  const result = {
    name: endpoint.name || '',
    url: endpoint.url,
    enabled: endpoint.enabled !== false,
    ok: false,
    latencyMs: null,
    blockNumber: null,
    chainId: null,
    error: '',
    checkedAt: nowSeconds(),
  };

  try {
    const provider = getProviderForRpcUrl(endpoint.url);
    const chainIdRaw = await withTimeout(provider.send('eth_chainId', []), timeoutMs, 'RPC chainId timeout');
    const actualChainId = parseRpcQuantity(chainIdRaw);
    result.chainId = actualChainId || null;
    if (actualChainId && actualChainId !== CHAIN_ID) {
      throw new Error(`chainId mismatch: ${actualChainId}`);
    }

    const blockRaw = await withTimeout(provider.send('eth_blockNumber', []), timeoutMs, 'RPC blockNumber timeout');
    result.blockNumber = parseRpcQuantity(blockRaw) || null;
    result.latencyMs = Date.now() - startedAt;
    result.ok = Boolean(result.blockNumber);
    if (!result.ok) result.error = 'empty blockNumber';
  } catch (error) {
    result.latencyMs = Date.now() - startedAt;
    result.error = String(error?.message || error || 'rpc error').slice(0, 240);
  }

  return result;
}

function selectBestRpcHealth(healthRows = [], fallbackEndpoints = []) {
  const okRows = healthRows
    .filter((row) => row && row.ok && row.url)
    .sort((a, b) => {
      const blockDiff = Number(b.blockNumber || 0) - Number(a.blockNumber || 0);
      if (blockDiff !== 0) return blockDiff;
      return Number(a.latencyMs || Number.MAX_SAFE_INTEGER) - Number(b.latencyMs || Number.MAX_SAFE_INTEGER);
    });
  if (okRows.length) return okRows[0];
  const fallback = (fallbackEndpoints || []).find((endpoint) => endpoint?.url) || BOOTSTRAP_RPC_URLS[0];
  return fallback ? { ...fallback, ok: false, latencyMs: null, blockNumber: null, error: 'all rpc endpoints unavailable' } : null;
}

async function getRpcHealth(force = false) {
  const settings = await getRuntimeSettings();
  const endpoints = getEnabledRpcEndpoints(settings);
  const key = getRpcEndpointKey(endpoints);
  const now = Date.now();
  if (!force && rpcHealthCache.values && rpcHealthCache.key === key && rpcHealthCache.expiresAt > now) {
    return rpcHealthCache.values;
  }

  const values = await Promise.all(endpoints.map((endpoint) => probeRpcEndpoint(endpoint)));
  rpcHealthCache.key = key;
  rpcHealthCache.values = values;
  rpcHealthCache.expiresAt = now + 30_000;
  return values;
}

async function getOrderedRpcEndpointsByHealth(force = false) {
  const settings = await getRuntimeSettings();
  const endpoints = getEnabledRpcEndpoints(settings);
  const health = await getRpcHealth(force);
  const best = selectBestRpcHealth(health, endpoints);
  const byUrl = new Map(health.map((row) => [row.url, row]));
  const sortedOk = health
    .filter((row) => row.ok && row.url && row.url !== best?.url)
    .sort((a, b) => {
      const blockDiff = Number(b.blockNumber || 0) - Number(a.blockNumber || 0);
      if (blockDiff !== 0) return blockDiff;
      return Number(a.latencyMs || Number.MAX_SAFE_INTEGER) - Number(b.latencyMs || Number.MAX_SAFE_INTEGER);
    });
  const rest = endpoints
    .filter((endpoint) => endpoint.url !== best?.url && !sortedOk.some((row) => row.url === endpoint.url))
    .map((endpoint) => byUrl.get(endpoint.url) || endpoint);

  return [best, ...sortedOk, ...rest].filter((endpoint) => endpoint?.url);
}

async function getBestRpcHealth(force = false) {
  const settings = await getRuntimeSettings();
  const endpoints = getEnabledRpcEndpoints(settings);
  const health = await getRpcHealth(force);
  return selectBestRpcHealth(health, endpoints);
}

async function getBestProvider(force = false) {
  const best = await getBestRpcHealth(force);
  return getProviderForRpcUrl(best?.url || RPC_URL);
}

async function ensurePacket(packetId, res) {
  const packet = await db.getPacket(packetId);
  if (!packet) {
    res.status(404).json({ ok: false, message: 'not found' });
    return null;
  }
  return packet;
}

async function getTransactionReceipt(txHash) {
  const endpoints = await getOrderedRpcEndpointsByHealth(false);
  for (const endpoint of endpoints) {
    try {
      const provider = getProviderForRpcUrl(endpoint.url);
      const receipt = await withTimeout(provider.getTransactionReceipt(txHash), 8_000, 'receipt timeout');
      if (receipt) return receipt;
    } catch (error) {
      // eslint-disable-next-line no-console
      console.warn('[receipt-rpc-error]', endpoint.url, error?.message || error);
    }
  }
  return null;
}

function parseExpectedLog(receipt, eventName, expectedContractAddress) {
  if (!receipt || !Array.isArray(receipt.logs)) return null;
  const expectedContractNorm = normalizeAddress(expectedContractAddress);
  for (const log of receipt.logs) {
    if (!log || !log.address) continue;
    if (expectedContractNorm && normalizeAddress(log.address) !== expectedContractNorm) continue;
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

const tokenPriceCache = {
  bnbPriceUsd: '0',
  bnbCheckedAt: 0,
  bnbExpiresAtMs: 0,
};

function fetchJsonHttps(url, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: timeoutMs }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        body += chunk;
        if (body.length > 1024 * 1024) {
          req.destroy(new Error('response too large'));
        }
      });
      res.on('end', () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          reject(new Error(`HTTP ${res.statusCode}`));
          return;
        }
        try {
          resolve(JSON.parse(body || '{}'));
        } catch (error) {
          reject(error);
        }
      });
    });
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
  });
}


const tokenExternalIconCache = new Map();

function trustWalletAssetIconUrlForContract(contractAddress) {
  const address = normalizeAddress(contractAddress || '');
  if (!address || address === ZERO_ADDRESS) return '';
  try {
    return `https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/assets/${getAddress(address)}/logo.png`;
  } catch (_) {
    return '';
  }
}

function requestRemoteImageHead(url, timeoutMs = 2500, redirectsLeft = 2) {
  return new Promise((resolve) => {
    let parsed;
    try {
      parsed = new URL(String(url || '').trim());
    } catch (_) {
      resolve({ ok: false, statusCode: 0, contentType: '', url: '' });
      return;
    }

    if (parsed.protocol !== 'https:') {
      resolve({ ok: false, statusCode: 0, contentType: '', url: parsed.toString() });
      return;
    }

    const req = https.request(parsed, {
      method: 'HEAD',
      timeout: timeoutMs,
      headers: {
        Accept: 'image/*,*/*;q=0.6',
        'User-Agent': 'TelegramWalletTokenIconResolver/1.0',
      },
    }, (res) => {
      const statusCode = Number(res.statusCode || 0);
      const location = String(res.headers.location || '').trim();
      const contentType = String(res.headers['content-type'] || '').toLowerCase();
      res.resume();

      if (statusCode >= 300 && statusCode < 400 && location && redirectsLeft > 0) {
        try {
          const nextUrl = new URL(location, parsed).toString();
          requestRemoteImageHead(nextUrl, timeoutMs, redirectsLeft - 1).then(resolve);
          return;
        } catch (_) {
          // fall through to false.
        }
      }

      const okStatus = statusCode >= 200 && statusCode < 300;
      const okType = !contentType || contentType.startsWith('image/') || contentType.includes('octet-stream');
      resolve({ ok: okStatus && okType, statusCode, contentType, url: parsed.toString() });
    });

    req.on('timeout', () => req.destroy(new Error('token icon lookup timeout')));
    req.on('error', () => resolve({ ok: false, statusCode: 0, contentType: '', url: parsed.toString() }));
    req.end();
  });
}

async function getVerifiedExternalTokenIconUrl(contractAddress) {
  const url = trustWalletAssetIconUrlForContract(contractAddress);
  if (!url) return '';

  const nowMs = Date.now();
  const cached = tokenExternalIconCache.get(url);
  if (cached && cached.expiresAt > nowMs) {
    return cached.ok ? cached.url : '';
  }

  const result = await requestRemoteImageHead(url, 2500, 2);
  tokenExternalIconCache.set(url, {
    ok: Boolean(result.ok),
    url: result.ok ? (result.url || url) : url,
    expiresAt: nowMs + (result.ok ? 24 * 60 * 60 * 1000 : 60 * 60 * 1000),
  });
  return result.ok ? (result.url || url) : '';
}

async function enrichTokenMetadataWithAutoIcon(metadata) {
  if (!metadata || metadata.iconUrl) return metadata;
  const address = normalizeAddress(metadata.contractAddress || metadata.tokenAddress || '');
  if (!address || address === ZERO_ADDRESS) return metadata;

  const iconUrl = await getVerifiedExternalTokenIconUrl(address);
  if (!iconUrl) return metadata;

  return {
    ...metadata,
    contractAddress: address,
    tokenAddress: address,
    iconUrl,
    source: metadata.source && metadata.source !== 'not-configured'
      ? `${metadata.source}+trustwallet-assets`
      : 'trustwallet-assets',
  };
}

async function getBnbPriceUsdCached(force = false) {
  const nowMs = Date.now();
  if (!force && tokenPriceCache.bnbExpiresAtMs > nowMs) {
    return tokenPriceCache.bnbPriceUsd;
  }

  try {
    const json = await fetchJsonHttps('https://api.binance.com/api/v3/ticker/price?symbol=BNBUSDT', 4000);
    const priceUsd = normalizeTokenPriceUsd(json?.price, '0');
    if (Number(priceUsd) > 0) {
      tokenPriceCache.bnbPriceUsd = priceUsd;
      tokenPriceCache.bnbCheckedAt = nowSeconds();
      tokenPriceCache.bnbExpiresAtMs = nowMs + 60_000;
      return priceUsd;
    }
  } catch (error) {
    // eslint-disable-next-line no-console
    console.warn('[bnb-price-fetch-error]', error?.message || error);
  }

  tokenPriceCache.bnbExpiresAtMs = nowMs + 15_000;
  return tokenPriceCache.bnbPriceUsd || '0';
}

const tokenExternalPriceCache = new Map();

function parseExternalPriceUsd(value) {
  const normalized = normalizeTokenPriceUsd(value, '0');
  return Number(normalized) > 0 ? normalized : '0';
}

function getTokenPriceCacheKey(contractAddress) {
  const address = normalizeAddress(contractAddress || '');
  return address || '';
}

async function fetchDefiLlamaTokenPriceUsd(contractAddress) {
  const address = normalizeAddress(contractAddress || '');
  if (!address || address === ZERO_ADDRESS) return null;
  const checksumAddress = getAddress(address);
  const ids = [`bsc:${checksumAddress}`, `bsc:${address}`];
  const encoded = encodeURIComponent(ids[0]);
  const urls = [
    `https://api.llama.fi/prices/current/${encoded}?searchWidth=4h`,
    `https://coins.llama.fi/prices/current/${encoded}?searchWidth=4h`,
  ];
  let item = null;
  for (const url of urls) {
    try {
      const json = await fetchJsonHttps(url, 4500);
      const coins = json?.coins || {};
      item = coins[ids[0]] || coins[ids[1]] || coins[ids[0].toLowerCase()] || coins[ids[1].toLowerCase()];
      if (item) break;
    } catch (error) {
      // eslint-disable-next-line no-console
      console.warn('[defillama-price-fetch-error]', error?.message || error);
    }
  }
  const priceUsd = parseExternalPriceUsd(item?.price);
  if (Number(priceUsd) <= 0) return null;
  return {
    priceUsd,
    source: 'defillama',
    updatedAt: Number(item?.timestamp || 0) || nowSeconds(),
    confidence: Number.isFinite(Number(item?.confidence)) ? Number(item.confidence) : undefined,
  };
}

async function fetchCoinGeckoTokenPriceUsd(contractAddress) {
  const address = normalizeAddress(contractAddress || '');
  if (!address || address === ZERO_ADDRESS) return null;
  const json = await fetchJsonHttps(`https://api.coingecko.com/api/v3/simple/token_price/binance-smart-chain?contract_addresses=${encodeURIComponent(address)}&vs_currencies=usd`, 4500);
  const item = json?.[address] || json?.[address.toLowerCase()] || json?.[getAddress(address)];
  const priceUsd = parseExternalPriceUsd(item?.usd);
  if (Number(priceUsd) <= 0) return null;
  return { priceUsd, source: 'coingecko', updatedAt: nowSeconds() };
}

async function fetchDexScreenerTokenPriceUsd(contractAddress) {
  const address = normalizeAddress(contractAddress || '');
  if (!address || address === ZERO_ADDRESS) return null;
  const json = await fetchJsonHttps(`https://api.dexscreener.com/tokens/v1/bsc/${encodeURIComponent(address)}`, 4500);
  const pairs = Array.isArray(json) ? json : (Array.isArray(json?.pairs) ? json.pairs : []);
  const candidates = pairs
    .filter((pair) => {
      const chainId = String(pair?.chainId || '').toLowerCase();
      const baseAddress = normalizeAddress(pair?.baseToken?.address || '');
      const priceUsd = parseExternalPriceUsd(pair?.priceUsd);
      return chainId === 'bsc' && baseAddress === address && Number(priceUsd) > 0;
    })
    .map((pair) => ({
      priceUsd: parseExternalPriceUsd(pair.priceUsd),
      liquidityUsd: Number(pair?.liquidity?.usd || 0) || 0,
      pairAddress: String(pair?.pairAddress || ''),
      dexId: String(pair?.dexId || ''),
    }))
    .sort((a, b) => b.liquidityUsd - a.liquidityUsd);
  if (!candidates.length) return null;
  return {
    priceUsd: candidates[0].priceUsd,
    source: 'dexscreener',
    updatedAt: nowSeconds(),
    liquidityUsd: candidates[0].liquidityUsd,
    pairAddress: candidates[0].pairAddress,
    dexId: candidates[0].dexId,
  };
}

async function fetchExternalTokenPriceUsd(provider, contractAddress) {
  if (provider === 'defillama') return fetchDefiLlamaTokenPriceUsd(contractAddress);
  if (provider === 'coingecko') return fetchCoinGeckoTokenPriceUsd(contractAddress);
  if (provider === 'dexscreener') return fetchDexScreenerTokenPriceUsd(contractAddress);
  return null;
}

function getPriceProviderOrder(settings) {
  try {
    return normalizeTokenPriceProviderOrderSetting(settings?.tokenPriceProviderOrder || BOOTSTRAP_TOKEN_PRICE_PROVIDER_ORDER);
  } catch (_) {
    return BOOTSTRAP_TOKEN_PRICE_PROVIDER_ORDER;
  }
}


function getKnownStableTokenPriceUsd(contractAddress) {
  const address = normalizeAddress(contractAddress || '');
  if (!address || !BSC_STABLE_PRICE_ADDRESSES.has(address)) return null;
  return {
    contractAddress: address,
    tokenAddress: address,
    priceUsd: '1',
    source: 'stablecoin-address',
    updatedAt: nowSeconds(),
  };
}

async function getExternalTokenPriceUsdCached(contractAddress, settings, force = false) {
  const address = getTokenPriceCacheKey(contractAddress);
  if (!address || address === ZERO_ADDRESS) return null;
  const stablePrice = getKnownStableTokenPriceUsd(address);
  if (stablePrice) return stablePrice;
  if (Number(settings?.tokenPriceAutoEnabled ?? 1) !== 1) return null;
  const nowMs = Date.now();
  const cached = tokenExternalPriceCache.get(address);
  if (!force && cached && cached.expiresAt > nowMs) return cached.ok ? cached.data : null;
  for (const provider of getPriceProviderOrder(settings)) {
    try {
      const result = await fetchExternalTokenPriceUsd(provider, address);
      if (result && Number(result.priceUsd) > 0) {
        const ttlSeconds = Math.min(Math.max(Number(settings?.tokenPriceExternalTtlSeconds || BOOTSTRAP_TOKEN_PRICE_EXTERNAL_TTL_SECONDS), 30), 86400);
        const data = {
          contractAddress: address,
          tokenAddress: address,
          priceUsd: normalizeTokenPriceUsd(result.priceUsd, '0'),
          source: result.source || provider,
          updatedAt: Number(result.updatedAt || 0) || nowSeconds(),
          confidence: result.confidence,
          liquidityUsd: result.liquidityUsd,
          pairAddress: result.pairAddress || '',
          dexId: result.dexId || '',
        };
        tokenExternalPriceCache.set(address, { ok: true, data, expiresAt: nowMs + ttlSeconds * 1000 });
        return data;
      }
    } catch (error) {
      // eslint-disable-next-line no-console
      console.warn('[token-price-fetch-error]', provider, address, error?.message || error);
    }
  }
  tokenExternalPriceCache.set(address, { ok: false, data: null, expiresAt: nowMs + 60 * 1000 });
  return null;
}

async function enrichTokenMetadataWithAutoPrice(metadata, settings, force = false) {
  if (!metadata) return metadata;
  const address = normalizeAddress(metadata.contractAddress || metadata.tokenAddress || '');
  const existingPrice = normalizeTokenPriceUsd(metadata.priceUsd, '0');
  if (Number(existingPrice) > 0) {
    return { ...metadata, priceUsd: existingPrice, priceSource: metadata.priceSource || metadata.source || 'server-config' };
  }
  if (address === ZERO_ADDRESS || String(metadata.symbol || '').trim().toUpperCase() === 'BNB') {
    const bnbPriceUsd = await getBnbPriceUsdCached(force);
    return { ...metadata, contractAddress: ZERO_ADDRESS, tokenAddress: ZERO_ADDRESS, priceUsd: normalizeTokenPriceUsd(bnbPriceUsd, '0'), priceSource: 'binance:BNBUSDT', source: metadata.source && metadata.source !== 'not-configured' ? `${metadata.source}+binance:BNBUSDT` : 'binance:BNBUSDT', updatedAt: tokenPriceCache.bnbCheckedAt || nowSeconds() };
  }
  const resolved = await getExternalTokenPriceUsdCached(address, settings, force);
  if (!resolved || Number(resolved.priceUsd) <= 0) return { ...metadata, priceUsd: '0', priceSource: metadata.priceSource || 'not-configured' };
  return {
    ...metadata,
    contractAddress: address,
    tokenAddress: address,
    priceUsd: resolved.priceUsd,
    priceSource: resolved.source,
    liquidityUsd: resolved.liquidityUsd ?? metadata.liquidityUsd,
    priceConfidence: resolved.confidence ?? metadata.priceConfidence,
    pairAddress: resolved.pairAddress || metadata.pairAddress || '',
    dexId: resolved.dexId || metadata.dexId || '',
    source: metadata.source && metadata.source !== 'not-configured' ? `${metadata.source}+${resolved.source}` : resolved.source,
    updatedAt: resolved.updatedAt || metadata.updatedAt || nowSeconds(),
  };
}

async function enrichTokenMetadata(metadata, settings, options = {}) {
  const withIcon = await enrichTokenMetadataWithAutoIcon(metadata);
  return enrichTokenMetadataWithAutoPrice(withIcon, settings, Boolean(options.forcePrice));
}

async function enrichTokenMetadataRows(rows, settings, options = {}) {
  const enriched = await Promise.all((Array.isArray(rows) ? rows : []).map((row) => enrichTokenMetadata(row, settings, options)));
  return mergeTokenMetadataRows(enriched);
}

function parseContractAddressList(value, maxItems = 100) {
  const seen = new Set();
  return String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, maxItems)
    .map((item) => normalizeAddress(item))
    .filter((address) => {
      if (!address || seen.has(address)) return false;
      seen.add(address);
      return true;
    });
}

function encodePublicPathSegments(value) {
  return String(value || '')
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/');
}

function resolvePublicAssetUrl(value, settings, publicPath) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  try {
    const url = new URL(raw);
    if (url.protocol === 'http:' || url.protocol === 'https:') return url.toString();
  } catch (_) {
    // Not an absolute URL; continue as path or filename.
  }

  const publicHost = String(settings?.publicHost || BOOTSTRAP_PUBLIC_HOST).trim().replace(/\/+$/, '');
  if (!publicHost) return raw;
  if (raw.startsWith('/')) return `${publicHost}${raw}`;
  if (raw.includes('/')) return `${publicHost}/${encodePublicPathSegments(raw.replace(/^\/+/, ''))}`;
  const normalizedPublicPath = normalizePublicPath(publicPath || BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH);
  return `${publicHost}${normalizedPublicPath}/${encodeURIComponent(raw)}`;
}

function resolveTokenIconUrl(value, settings) {
  return resolvePublicAssetUrl(value, settings, settings?.tokenIconPublicPath || BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH);
}

const TOKEN_ICON_FILE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'webp', 'gif'];

function sanitizeTokenIconFilenameBase(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  return raw.replace(/[^a-zA-Z0-9_.@()\-]/g, '').slice(0, 128);
}

function buildLocalTokenIconCandidates(contractAddress, symbol) {
  const bases = [];
  const seenBase = new Set();
  const addBase = (value) => {
    const safe = sanitizeTokenIconFilenameBase(value);
    if (!safe) return;
    for (const candidate of [safe, safe.toLowerCase(), safe.toUpperCase()]) {
      if (!candidate || seenBase.has(candidate)) continue;
      seenBase.add(candidate);
      bases.push(candidate);
    }
  };

  const address = normalizeAddress(contractAddress || '');
  if (address && address !== ZERO_ADDRESS) {
    addBase(address);
    addBase(address.replace(/^0x/i, ''));
    try {
      const checksum = getAddress(address);
      addBase(checksum);
      addBase(checksum.replace(/^0x/i, ''));
    } catch (_) {
      // normalizeAddress already validates; keep this defensive.
    }
  }

  const symbolText = String(symbol || '').trim();
  if (symbolText && (!address || address === ZERO_ADDRESS)) {
    addBase(symbolText);
  }
  if (address === ZERO_ADDRESS || symbolText.toUpperCase() === 'BNB') {
    addBase('bnb');
    addBase('BNB');
  }

  const filenames = [];
  const seenFile = new Set();
  for (const base of bases) {
    for (const ext of TOKEN_ICON_FILE_EXTENSIONS) {
      const filename = `${base}.${ext}`;
      const key = filename.toLowerCase();
      if (seenFile.has(key)) continue;
      seenFile.add(key);
      filenames.push(filename);
    }
  }
  return filenames;
}

function findLocalTokenIconFilename(settings, contractAddress, symbol) {
  const iconDir = path.resolve(settings?.tokenIconDir || BOOTSTRAP_TOKEN_ICON_DIR);
  const candidates = buildLocalTokenIconCandidates(contractAddress, symbol);
  for (const filename of candidates) {
    const safeName = path.basename(filename);
    if (!safeName || safeName !== filename) continue;
    const filePath = path.join(iconDir, safeName);
    try {
      if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        return safeName;
      }
    } catch (_) {
      // Ignore unreadable files and keep checking candidates.
    }
  }
  return '';
}

function resolveLocalTokenIconUrl(settings, contractAddress, symbol) {
  const filename = findLocalTokenIconFilename(settings, contractAddress, symbol);
  return filename ? resolveTokenIconUrl(filename, settings) : '';
}

function withLocalTokenIcon(metadata, settings) {
  if (!metadata || metadata.iconUrl) return metadata;
  const iconUrl = resolveLocalTokenIconUrl(settings, metadata.contractAddress || metadata.tokenAddress || '', metadata.symbol || '');
  if (!iconUrl) return metadata;
  return {
    ...metadata,
    iconUrl,
    source: metadata.source && metadata.source !== 'not-configured'
      ? `${metadata.source}+local-token-icons`
      : 'local-token-icons',
  };
}

function buildWalletTokenRows(settings) {
  const tokenRows = Array.isArray(settings?.walletTokens) ? settings.walletTokens : [];
  return tokenRows
    .map((token) => ({
      symbol: String(token?.symbol || '').trim(),
      contractAddress: normalizeAddress(token?.contractAddress || token?.tokenAddress || '') || String(token?.contractAddress || token?.tokenAddress || ''),
      tokenAddress: normalizeAddress(token?.contractAddress || token?.tokenAddress || '') || String(token?.contractAddress || token?.tokenAddress || ''),
      decimals: Number.isInteger(Number(token?.decimals)) ? Number(token.decimals) : 18,
      priceUsd: tokenPriceUsdFromItem(token),
      iconUrl: resolveTokenIconUrl(token?.iconUrl ?? token?.icon_url ?? token?.logoUrl ?? token?.logo ?? token?.imageUrl ?? token?.image ?? '', settings)
        || resolveLocalTokenIconUrl(settings, token?.contractAddress || token?.tokenAddress || '', token?.symbol || ''),
      source: 'walletTokens',
    }))
    .filter((token) => token.symbol && token.contractAddress);
}

function buildTokenIconRegistryRows(settings) {
  const tokenRows = Array.isArray(settings?.tokenIconRegistry) ? settings.tokenIconRegistry : [];
  return tokenRows
    .map((token) => ({
      symbol: String(token?.symbol || token?.tokenSymbol || '').trim(),
      contractAddress: normalizeAddress(token?.contractAddress || token?.tokenAddress || token?.address || '') || String(token?.contractAddress || token?.tokenAddress || token?.address || ''),
      tokenAddress: normalizeAddress(token?.contractAddress || token?.tokenAddress || token?.address || '') || String(token?.contractAddress || token?.tokenAddress || token?.address || ''),
      decimals: Number.isInteger(Number(token?.decimals ?? token?.tokenDecimals)) ? Number(token.decimals ?? token.tokenDecimals) : 18,
      priceUsd: tokenPriceUsdFromItem(token),
      iconUrl: resolveTokenIconUrl(token?.iconUrl ?? token?.icon_url ?? token?.logoUrl ?? token?.logo ?? token?.imageUrl ?? token?.image ?? '', settings)
        || resolveLocalTokenIconUrl(settings, token?.contractAddress || token?.tokenAddress || token?.address || '', token?.symbol || token?.tokenSymbol || ''),
      source: 'tokenIconRegistry',
    }))
    .filter((token) => token.contractAddress && token.iconUrl);
}

function buildTokenPriceRegistryRows(settings) {
  const tokenRows = Array.isArray(settings?.tokenPriceRegistry) ? settings.tokenPriceRegistry : [];
  return tokenRows
    .map((token) => {
      const symbol = String(token?.symbol || token?.tokenSymbol || '').trim();
      const contractAddress = normalizeAddress(token?.contractAddress || token?.tokenAddress || token?.address || '');
      return {
        symbol,
        contractAddress: contractAddress || String(token?.contractAddress || token?.tokenAddress || token?.address || ''),
        tokenAddress: contractAddress || String(token?.contractAddress || token?.tokenAddress || token?.address || ''),
        decimals: Number.isInteger(Number(token?.decimals ?? token?.tokenDecimals)) ? Number(token.decimals ?? token.tokenDecimals) : 18,
        priceUsd: tokenPriceUsdFromItem(token),
        iconUrl: '',
        source: 'tokenPriceRegistry',
        priceSource: 'tokenPriceRegistry',
        updatedAt: nowSeconds(),
      };
    })
    .filter((token) => (token.contractAddress || token.symbol) && Number(token.priceUsd) > 0);
}

function mergeTokenMetadataRows(rows = []) {
  const byKey = new Map();
  const orderedKeys = [];

  for (const row of rows) {
    if (!row) continue;
    const contractAddress = normalizeAddress(row.contractAddress || row.tokenAddress || '');
    const symbol = String(row.symbol || '').trim();
    if (!contractAddress && !symbol) continue;
    const key = contractAddress || `symbol:${symbol.toUpperCase()}`;
    const previous = byKey.get(key) || {};
    if (!byKey.has(key)) orderedKeys.push(key);

    byKey.set(key, {
      ...previous,
      ...row,
      symbol: symbol || previous.symbol || '',
      contractAddress: contractAddress || previous.contractAddress || row.contractAddress || '',
      tokenAddress: contractAddress || previous.tokenAddress || row.tokenAddress || '',
      decimals: Number.isInteger(Number(row.decimals)) ? Number(row.decimals) : (Number.isInteger(Number(previous.decimals)) ? Number(previous.decimals) : 18),
      priceUsd: Number(tokenPriceUsdFromItem(row)) > 0 ? tokenPriceUsdFromItem(row) : (previous.priceUsd || '0'),
      priceSource: row.priceSource || previous.priceSource || (Number(tokenPriceUsdFromItem(row)) > 0 ? (row.source || 'server-config') : ''),
      iconUrl: row.iconUrl || previous.iconUrl || '',
      source: previous.source && row.source && previous.source !== row.source ? `${previous.source}+${row.source}` : (row.source || previous.source || 'server-config'),
      updatedAt: row.updatedAt || previous.updatedAt || nowSeconds(),
    });
  }

  return orderedKeys.map((key) => byKey.get(key));
}

function buildTokenMetadataRows(settings, bnbPriceUsd) {
  const walletRows = buildWalletTokenRows(settings).map((token) => ({
    symbol: token.symbol,
    contractAddress: token.contractAddress,
    tokenAddress: token.tokenAddress,
    decimals: token.decimals,
    priceUsd: token.priceUsd,
    iconUrl: token.iconUrl,
    source: Number(token.priceUsd) > 0 ? 'walletTokens' : 'not-configured',
    updatedAt: nowSeconds(),
  }));

  const registryRows = buildTokenIconRegistryRows(settings).map((token) => ({
    symbol: token.symbol,
    contractAddress: token.contractAddress,
    tokenAddress: token.tokenAddress,
    decimals: token.decimals,
    priceUsd: token.priceUsd,
    iconUrl: token.iconUrl,
    source: 'tokenIconRegistry',
    updatedAt: nowSeconds(),
  }));

  const priceRows = buildTokenPriceRegistryRows(settings).map((token) => ({
    symbol: token.symbol,
    contractAddress: token.contractAddress,
    tokenAddress: token.tokenAddress,
    decimals: token.decimals,
    priceUsd: token.priceUsd,
    iconUrl: '',
    priceSource: 'tokenPriceRegistry',
    source: 'tokenPriceRegistry',
    updatedAt: nowSeconds(),
  }));

  return mergeTokenMetadataRows([
    {
      symbol: 'BNB',
      contractAddress: ZERO_ADDRESS,
      tokenAddress: ZERO_ADDRESS,
      decimals: 18,
      priceUsd: normalizeTokenPriceUsd(bnbPriceUsd, '0'),
      iconUrl: resolveTokenIconUrl(settings?.bnbIconUrl || '', settings) || resolveLocalTokenIconUrl(settings, ZERO_ADDRESS, 'BNB'),
      source: 'binance:BNBUSDT',
      updatedAt: tokenPriceCache.bnbCheckedAt || nowSeconds(),
    },
    ...walletRows,
    ...registryRows,
    ...priceRows,
  ]);
}

function buildWalletTokenPriceRows(settings, bnbPriceUsd) {
  return buildTokenMetadataRows(settings, bnbPriceUsd);
}

function findWalletTokenMetadata(settings, contractAddress, symbol, bnbPriceUsd) {
  const lookupAddress = normalizeAddress(contractAddress || '');
  const lookupSymbol = String(symbol || '').trim().toUpperCase();
  const rows = buildTokenMetadataRows(settings, bnbPriceUsd);
  let found = null;

  if (lookupAddress) {
    found = rows.find((row) => normalizeAddress(row.contractAddress || row.tokenAddress || '') === lookupAddress) || null;
    if (found) {
      return withLocalTokenIcon(found, settings);
    }
    // 传入了合约地址时，不按 symbol 兜底，避免不同合约但同名代币误用别人的图标。
    return withLocalTokenIcon({
      symbol: String(symbol || '').trim(),
      contractAddress: lookupAddress,
      tokenAddress: lookupAddress,
      decimals: 18,
      priceUsd: '0',
      iconUrl: '',
      source: 'not-configured',
      updatedAt: nowSeconds(),
    }, settings);
  }

  if (lookupSymbol) {
    found = rows.find((row) => String(row.symbol || '').trim().toUpperCase() === lookupSymbol) || null;
  }

  if (found) {
    return withLocalTokenIcon(found, settings);
  }

  return withLocalTokenIcon({
    symbol: String(symbol || '').trim(),
    contractAddress: String(contractAddress || '').trim(),
    tokenAddress: String(contractAddress || '').trim(),
    decimals: 18,
    priceUsd: '0',
    iconUrl: '',
    source: 'not-configured',
    updatedAt: nowSeconds(),
  }, settings);
}

app.get('/healthz', async (_, res) => {
  const settings = await getRuntimeSettings();
  const rpcHealth = await getRpcHealth(false);
  const bestRpc = selectBestRpcHealth(rpcHealth, getEnabledRpcEndpoints(settings));
  const rpcOk = Boolean(bestRpc?.ok);
  let dbOk = true;

  try {
    await db.pool.query('SELECT 1');
  } catch (_) {
    dbOk = false;
  }

  res.json({
    ok: true,
    service: 'web3-red-packet',
    chainId: CHAIN_ID,
    contractAddress: settings.redPacketContract,
    rpcUrl: bestRpc?.url || RPC_URL,
    rpcOk,
    blockNumber: bestRpc?.blockNumber || null,
    dbOk,
    ts: nowSeconds(),
    authSignerConfigured: Boolean(claimSignerWallet),
    claimSignerConfigured: Boolean(claimSignerWallet),
    claimSignerAddress: claimSignerWallet?.address || '',
  });
});

app.get('/api/v1/wallet/chain-config', async (_req, res, next) => {
  try {
    const settings = await getRuntimeSettings();
    const endpoints = getEnabledRpcEndpoints(settings);
    const rpcHealth = await getRpcHealth(false);
    const bestRpc = selectBestRpcHealth(rpcHealth, endpoints);
    const healthByUrl = new Map(rpcHealth.map((row) => [row.url, row]));
    return res.json({
      ok: true,
      data: {
        chainId: CHAIN_ID,
        chainName: 'BNB Smart Chain',
        rpcUrls: endpoints.map((endpoint) => endpoint.url),
        rpcEndpoints: endpoints.map((endpoint, index) => {
          const health = healthByUrl.get(endpoint.url) || null;
          return {
            name: endpoint.name || `BSC-Binance${index + 1}`,
            url: endpoint.url,
            enabled: endpoint.enabled !== false,
            source: 'server',
            priority: index + 1,
            serverStatus: health ? {
              ok: Boolean(health.ok),
              latencyMs: health.latencyMs,
              blockNumber: health.blockNumber,
              chainId: health.chainId,
              error: health.error || '',
              checkedAt: health.checkedAt || null,
            } : null,
          };
        }),
        bestRpcUrl: bestRpc?.url || endpoints[0]?.url || RPC_URL,
        redPacketContract: settings.redPacketContract,
        contractAddress: settings.redPacketContract,
        customRpcAllowed: true,
        clientSelectionStrategy: 'client_probe_best_block_then_latency',
        updatedAt: nowSeconds(),
      },
    });
  } catch (error) {
    return next(error);
  }
});

app.get('/api/v1/wallet/default-tokens', async (_req, res, next) => {
  try {
    const settings = await getRuntimeSettings();
    return res.json({ ok: true, data: { tokens: buildWalletTokenRows(settings) } });
  } catch (error) {
    return next(error);
  }
});

app.get('/api/v1/wallet/token-prices', async (req, res, next) => {
  try {
    const settings = await getRuntimeSettings();
    const forcePrice = parseBooleanFlag(req.query.force, false);
    const bnbPriceUsd = await getBnbPriceUsdCached(forcePrice);
    const requestedAddresses = parseContractAddressList(
      req.query.contractAddresses || req.query.tokenAddresses || req.query.addresses || '',
      100,
    );
    let rows = buildWalletTokenPriceRows(settings, bnbPriceUsd);
    if (requestedAddresses.length) {
      rows = mergeTokenMetadataRows([
        rows.find((row) => normalizeAddress(row.contractAddress || row.tokenAddress || '') === ZERO_ADDRESS),
        ...requestedAddresses.map((address) => findWalletTokenMetadata(settings, address, '', bnbPriceUsd)),
        ...rows,
      ]);
    }
    const prices = await enrichTokenMetadataRows(rows, settings, { forcePrice });
    return res.json({
      ok: true,
      data: {
        baseCurrency: 'USD',
        prices,
        updatedAt: nowSeconds(),
      },
    });
  } catch (error) {
    return next(error);
  }
});

app.get(['/api/v1/wallet/token-metadata', '/api/v1/wallet/token-meta'], async (req, res, next) => {
  try {
    const contractAddressesRaw = String(req.query.contractAddresses || req.query.tokenAddresses || req.query.addresses || '').trim();
    const settings = await getRuntimeSettings();

    if (contractAddressesRaw) {
      const bnbPriceUsd = await getBnbPriceUsdCached(false);
      const addresses = parseContractAddressList(contractAddressesRaw, 100);
      const tokens = await Promise.all(addresses.map(async (address) => {
        const metadata = findWalletTokenMetadata(settings, address, '', bnbPriceUsd);
        return enrichTokenMetadata(metadata, settings);
      }));

      return res.json({
        ok: true,
        data: {
          baseCurrency: 'USD',
          tokens,
          updatedAt: nowSeconds(),
        },
      });
    }

    const contractAddressRaw = String(req.query.contractAddress || req.query.tokenAddress || req.query.address || '').trim();
    const symbol = String(req.query.symbol || req.query.tokenSymbol || '').trim().slice(0, 32);
    const normalizedAddress = normalizeAddress(contractAddressRaw);
    const isNativeBnb = normalizedAddress === ZERO_ADDRESS || symbol.toUpperCase() === 'BNB';

    if (!normalizedAddress && contractAddressRaw && !isNativeBnb) {
      return badRequest(res, 'contractAddress invalid');
    }
    if (!normalizedAddress && !symbol) {
      return badRequest(res, 'contractAddress or symbol required');
    }

    const bnbPriceUsd = isNativeBnb ? await getBnbPriceUsdCached(false) : (tokenPriceCache.bnbPriceUsd || '0');
    const metadata = await enrichTokenMetadata(
      findWalletTokenMetadata(settings, normalizedAddress || contractAddressRaw, symbol, bnbPriceUsd),
      settings,
    );
    return res.json({
      ok: true,
      data: {
        ...metadata,
        baseCurrency: 'USD',
      },
    });
  } catch (error) {
    return next(error);
  }
});

app.use((req, res, next) => {
  Promise.resolve().then(async () => {
    if (!['GET', 'HEAD'].includes(req.method)) return next();

    let settings;
    try {
      settings = await getRuntimeSettings();
    } catch (_) {
      settings = buildRuntimeSettingsFromRows([]);
    }

    const publicPath = normalizePublicPath(settings.tokenIconPublicPath || BOOTSTRAP_TOKEN_ICON_PUBLIC_PATH);
    if (req.path !== publicPath && !req.path.startsWith(`${publicPath}/`)) return next();

    const relativePath = decodeURIComponent(req.path.slice(publicPath.length).replace(/^\/+/, ''));
    const filename = path.basename(relativePath);
    if (!filename || filename !== relativePath || !/\.(?:png|jpe?g|webp|gif)$/i.test(filename)) {
      return res.status(404).json({ ok: false, message: 'not found' });
    }

    const filePath = path.join(path.resolve(settings.tokenIconDir || BOOTSTRAP_TOKEN_ICON_DIR), filename);
    if (process.env.NODE_ENV === 'production') {
      res.setHeader('Cache-Control', 'public, max-age=2592000');
    }
    return res.sendFile(filePath, (error) => {
      if (error) return next();
      return undefined;
    });
  }).catch(next);
});

app.use((req, res, next) => {
  Promise.resolve().then(async () => {
    if (!['GET', 'HEAD'].includes(req.method)) return next();

    let settings;
    try {
      settings = await getRuntimeSettings();
    } catch (_) {
      settings = buildRuntimeSettingsFromRows([]);
    }

    const publicPath = normalizePublicPath(settings.appUploadPublicPath || BOOTSTRAP_APP_UPLOAD_PUBLIC_PATH);
    if (req.path !== publicPath && !req.path.startsWith(`${publicPath}/`)) return next();

    const relativePath = decodeURIComponent(req.path.slice(publicPath.length).replace(/^\/+/, ''));
    const filename = path.basename(relativePath);
    if (!filename || filename !== relativePath || !filename.toLowerCase().endsWith('.apk')) {
      return res.status(404).json({ ok: false, message: 'not found' });
    }

    const filePath = path.join(path.resolve(settings.appUploadDir || BOOTSTRAP_APP_UPLOAD_DIR), filename);
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    if (process.env.NODE_ENV === 'production') {
      res.setHeader('Cache-Control', 'public, max-age=2592000');
    }
    return res.sendFile(filePath, (error) => {
      if (error) return next();
      return undefined;
    });
  }).catch(next);
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

function buildAdminClientVersionOptions(req, maxPageSize = 100) {
  const paging = parseAdminPageParams(req.query, maxPageSize);
  const enabled = String(req.query.enabled ?? '').trim();
  return {
    ...paging,
    search: String(req.query.search || '').trim(),
    enabled: enabled === '0' || enabled === '1' ? enabled : '',
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

app.get(`${ADMIN_BASE_PATH}/client-versions`, adminRequireAuth, adminAsync(async (req, res) => {
  const result = await db.getClientVersionPage(buildAdminClientVersionOptions(req, 100));
  return res.json({ ok: true, data: result });
}));

app.post(`${ADMIN_BASE_PATH}/client-versions`, adminRequireAuth, adminAsync(async (req, res) => {
  const runtimeSettings = await getRuntimeSettings();
  let form;
  try {
    form = await parseMultipartForm(req, runtimeSettings.maxApkUploadBytes + (256 * 1024), runtimeSettings.maxApkUploadBytes);
  } catch (error) {
    return res.status(error.statusCode || 400).json({ ok: false, message: error.message || 'APK 上传失败' });
  }

  const fields = form.fields || {};
  const versionCode = parsePositiveInt(fields.versionCode || fields.updateVersionCode || fields.code);
  const versionName = String(fields.versionName || fields.updateVersionName || fields.name || '').trim().slice(0, 64);
  const releaseNotes = String(fields.releaseNotes || fields.updateContent || fields.message || '').trim().slice(0, 5000);
  const releaseDate = parseAdminDateSeconds(fields.releaseDate, false) || nowSeconds();
  const enabled = parseBooleanFlag(fields.enabled, true);
  const forceUpdate = parseBooleanFlag(fields.forceUpdate || fields.force, false);

  if (!versionCode) return badRequest(res, 'versionCode invalid');
  if (!versionName) return badRequest(res, 'versionName required');
  if (!releaseNotes) return badRequest(res, 'releaseNotes required');

  const uploadFile = (form.files || []).find((file) => ['apkFile', 'apk', 'file', 'upload'].includes(file.fieldname));
  let apkInfo = null;
  if (uploadFile) {
    try {
      apkInfo = await saveUploadedApk(uploadFile, versionCode, runtimeSettings);
    } catch (error) {
      return res.status(error.statusCode || 400).json({ ok: false, message: error.message || 'APK 保存失败' });
    }
  }

  const existing = await db.getClientVersionByCode(versionCode);
  const externalDownloadUrl = String(fields.downloadUrl || '').trim();
  const downloadUrl = apkInfo?.downloadUrl || externalDownloadUrl || existing?.download_url || '';
  if (!downloadUrl) return badRequest(res, 'apkFile or downloadUrl required');

  const version = await db.saveClientVersion({
    versionCode,
    versionName,
    releaseNotes,
    downloadUrl,
    apkFilename: apkInfo?.apkFilename || existing?.apk_filename || '',
    apkOriginalName: apkInfo?.apkOriginalName || existing?.apk_original_name || '',
    apkSizeBytes: apkInfo?.apkSizeBytes || Number(existing?.apk_size_bytes || 0),
    apkSha256: apkInfo?.apkSha256 || existing?.apk_sha256 || '',
    forceUpdate,
    enabled,
    releaseDate,
    createdBy: req.adminUser || ADMIN_USERNAME,
  });

  return res.json({ ok: true, data: version });
}));

app.post(`${ADMIN_BASE_PATH}/client-versions/:id/enabled`, adminRequireAuth, adminAsync(async (req, res) => {
  const id = parsePositiveInt(req.params.id);
  if (!id) return badRequest(res, 'id invalid');
  if (req.body?.enabled === undefined) return badRequest(res, 'enabled required');
  const version = await db.setClientVersionEnabled(id, parseBooleanFlag(req.body.enabled, true));
  if (!version) return res.status(404).json({ ok: false, message: 'not found' });
  return res.json({ ok: true, data: version });
}));


app.post([`${ADMIN_BASE_PATH}/token-icons`, `${ADMIN_BASE_PATH}/token-icons/upload`], adminRequireAuth, adminAsync(async (req, res) => {
  const runtimeSettings = await getRuntimeSettings();
  let form;
  try {
    form = await parseMultipartForm(req, BOOTSTRAP_MAX_TOKEN_ICON_UPLOAD_BYTES + (64 * 1024), BOOTSTRAP_MAX_TOKEN_ICON_UPLOAD_BYTES);
  } catch (error) {
    return res.status(error.statusCode || 400).json({ ok: false, message: error.message || '图标上传失败' });
  }

  const uploadFile = (form.files || []).find((file) => ['iconFile', 'tokenIcon', 'image', 'file', 'upload'].includes(file.fieldname));
  if (!uploadFile) return badRequest(res, '请选择代币图标文件');

  const fields = form.fields || {};
  const contractAddressRaw = String(fields.contractAddress || fields.tokenAddress || fields.address || '').trim();
  const contractAddress = contractAddressRaw ? normalizeAddress(contractAddressRaw) : '';
  if (contractAddressRaw && !contractAddress) return badRequest(res, 'contractAddress invalid');

  const symbol = String(fields.symbol || fields.tokenSymbol || '').trim().slice(0, 32);
  const decimalsRaw = fields.decimals ?? fields.tokenDecimals;
  let decimals = Number(decimalsRaw);
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 36) decimals = 18;
  const priceUsd = normalizeTokenPriceUsd(fields.priceUsd ?? fields.price_usd ?? fields.price, '0');

  try {
    const icon = await saveUploadedTokenIcon(uploadFile, runtimeSettings, fields);
    if (!icon) return badRequest(res, '图标保存失败');

    let savedTo = 'fileOnly';
    let savedToRegistry = false;
    let tokenIconRegistryCount = Array.isArray(runtimeSettings.tokenIconRegistry) ? runtimeSettings.tokenIconRegistry.length : 0;
    const shouldSaveToRegistry = parseBooleanFlag(fields.saveToRegistry, Boolean(contractAddress));
    if (shouldSaveToRegistry) {
      if (!contractAddress) return badRequest(res, '自动写入 tokenIconRegistry 时必须填写有效合约地址');
      const currentRegistry = normalizeTokenIconRegistrySetting(runtimeSettings.tokenIconRegistry || []);
      const nextRegistry = [
        { symbol, contractAddress, decimals, priceUsd, iconUrl: icon.filename },
        ...currentRegistry.filter((item) => normalizeAddress(item.contractAddress || item.tokenAddress || item.address || '') !== contractAddress),
      ];
      const nextSettings = await db.saveRuntimeSettings({ tokenIconRegistry: nextRegistry }, req.adminUser || ADMIN_USERNAME);
      savedTo = 'tokenIconRegistry';
      savedToRegistry = true;
      tokenIconRegistryCount = Array.isArray(nextSettings.tokenIconRegistry) ? nextSettings.tokenIconRegistry.length : nextRegistry.length;
    }

    return res.json({
      ok: true,
      data: {
        ...icon,
        url: icon.iconUrl,
        contractAddress,
        symbol,
        decimals,
        priceUsd,
        savedTo,
        savedToRegistry,
        tokenIconRegistryCount,
      },
    });
  } catch (error) {
    return res.status(error.statusCode || 400).json({ ok: false, message: error.message || '图标保存失败' });
  }
}));

app.get(`${ADMIN_BASE_PATH}/token-icons`, adminRequireAuth, adminAsync(async (_req, res) => {
  const runtimeSettings = await getRuntimeSettings();
  const iconDir = path.resolve(runtimeSettings.tokenIconDir || BOOTSTRAP_TOKEN_ICON_DIR);
  let entries = [];
  try {
    const filenames = await fs.promises.readdir(iconDir);
    entries = (await Promise.all(filenames
      .filter((filename) => /\.(?:png|jpe?g|webp|gif)$/i.test(filename))
      .map(async (filename) => {
        const stat = await fs.promises.stat(path.join(iconDir, filename)).catch(() => null);
        return {
          filename,
          iconUrl: resolveTokenIconUrl(filename, runtimeSettings),
          sizeBytes: stat?.size || 0,
          updatedAt: stat?.mtimeMs ? Math.floor(stat.mtimeMs / 1000) : 0,
        };
      })));
    entries.sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0));
  } catch (_) {
    entries = [];
  }

  return res.json({ ok: true, data: { rows: entries, total: entries.length } });
}));


app.get([`${ADMIN_BASE_PATH}/wallet/token-metadata`, `${ADMIN_BASE_PATH}/wallet/token-price`], adminRequireAuth, adminAsync(async (req, res) => {
  const settings = await getRuntimeSettings();
  const contractAddressRaw = String(req.query.contractAddress || req.query.tokenAddress || req.query.address || '').trim();
  const symbol = String(req.query.symbol || req.query.tokenSymbol || '').trim().slice(0, 32);
  const normalizedAddress = normalizeAddress(contractAddressRaw);
  const isNativeBnb = normalizedAddress === ZERO_ADDRESS || symbol.toUpperCase() === 'BNB';

  if (!normalizedAddress && contractAddressRaw && !isNativeBnb) {
    return badRequest(res, 'contractAddress invalid');
  }
  if (!normalizedAddress && !symbol) {
    return badRequest(res, 'contractAddress or symbol required');
  }

  const forcePrice = parseBooleanFlag(req.query.force, true);
  const bnbPriceUsd = isNativeBnb ? await getBnbPriceUsdCached(forcePrice) : (tokenPriceCache.bnbPriceUsd || '0');
  const metadata = await enrichTokenMetadata(
    findWalletTokenMetadata(settings, normalizedAddress || contractAddressRaw, symbol, bnbPriceUsd),
    settings,
    { forcePrice },
  );

  return res.json({
    ok: true,
    data: {
      ...metadata,
      baseCurrency: 'USD',
    },
  });
}));

app.get(`${ADMIN_BASE_PATH}/settings`, adminRequireAuth, adminAsync(async (_req, res) => {
  const settings = await getRuntimeSettings(true);
  return res.json({
    ok: true,
    data: {
      values: publicRuntimeSettingsForAdmin(settings),
      definitions: runtimeSettingDefinitionsForAdmin(),
      updatedAt: nowSeconds(),
    },
  });
}));

app.post(`${ADMIN_BASE_PATH}/settings`, adminRequireAuth, adminAsync(async (req, res) => {
  let settings;
  try {
    settings = await db.saveRuntimeSettings(req.body || {}, req.adminUser || ADMIN_USERNAME);
  } catch (error) {
    return badRequest(res, error.message || '参数保存失败');
  }

  return res.json({
    ok: true,
    data: {
      values: publicRuntimeSettingsForAdmin(settings),
      definitions: runtimeSettingDefinitionsForAdmin(),
      updatedAt: nowSeconds(),
    },
  });
}));

app.get(`${ADMIN_BASE_PATH}/system`, adminRequireAuth, adminAsync(async (_req, res) => {
  const runtimeSettings = await getRuntimeSettings();
  const rpcStatus = await getRpcHealth(true);
  const bestRpc = selectBestRpcHealth(rpcStatus, getEnabledRpcEndpoints(runtimeSettings));
  const rpcOk = Boolean(bestRpc?.ok);
  const blockNumber = bestRpc?.blockNumber || null;

  let dbOk = true;
  let dbVersion = '';
  let tableRows = [];
  let latestAppVersion = null;
  try {
    const [versionRows] = await db.pool.query('SELECT VERSION() AS version');
    dbVersion = versionRows[0]?.version || '';
    const [tables] = await db.pool.query(
      `SELECT TABLE_NAME AS tableName, TABLE_ROWS AS estimatedRows
       FROM information_schema.TABLES
       WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN ('red_packets', 'red_packet_claims', 'red_packet_refunds', 'client_app_versions', 'system_settings')
       ORDER BY TABLE_NAME ASC`,
      [MYSQL_DATABASE],
    );
    tableRows = tables;
    latestAppVersion = await db.getLatestClientVersion();
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
      rpcStatus: rpcStatus.map((row) => ({
        ...row,
        url: maskSensitiveUrl(row.url),
      })),
      config: {
        chainId: CHAIN_ID,
        contractAddress: runtimeSettings.redPacketContract,
        rpcUrl: maskSensitiveUrl(bestRpc?.url || RPC_URL),
        rpcCount: getEnabledRpcEndpoints(runtimeSettings).length,
        bestRpcLatencyMs: bestRpc?.latencyMs ?? '-',
        mysql: `${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}`,
        appVersion: latestAppVersion
          ? `${latestAppVersion.version_name} (${latestAppVersion.version_code})`
          : `${runtimeSettings.fallbackVersionName} (${runtimeSettings.fallbackVersionCode})`,
        publicHost: runtimeSettings.publicHost,
        appUploadPath: runtimeSettings.appUploadPublicPath,
        appUploadDir: runtimeSettings.appUploadDir,
        appUploadUrlBase: runtimeSettings.appUploadUrlBase || '-',
        maxApkUploadMB: runtimeSettings.maxApkUploadMB,
        maxExpiresInSeconds: runtimeSettings.maxExpiresInSeconds,
        proxy: `${runtimeSettings.proxyAddress}:${runtimeSettings.proxyPort}${runtimeSettings.proxyUsername ? ` user=${runtimeSettings.proxyUsername}` : ''}`,
        walletTokens: Array.isArray(runtimeSettings.walletTokens) ? runtimeSettings.walletTokens.length : 0,
        tokenIconRegistry: Array.isArray(runtimeSettings.tokenIconRegistry) ? runtimeSettings.tokenIconRegistry.length : 0,
        tokenPriceRegistry: Array.isArray(runtimeSettings.tokenPriceRegistry) ? runtimeSettings.tokenPriceRegistry.length : 0,
        tokenPriceAutoEnabled: Number(runtimeSettings.tokenPriceAutoEnabled ?? 1) === 1,
        tokenPriceProviderOrder: Array.isArray(runtimeSettings.tokenPriceProviderOrder) ? runtimeSettings.tokenPriceProviderOrder.join(',') : '',
        tokenPriceExternalTtlSeconds: runtimeSettings.tokenPriceExternalTtlSeconds,
        tokenIconPublicPath: runtimeSettings.tokenIconPublicPath,
        tokenIconDir: runtimeSettings.tokenIconDir,
        bnbIconUrl: runtimeSettings.bnbIconUrl || '-',
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

  const runtimeSettings = await getRuntimeSettings();
  const creator = normalizeAddress(creatorWallet);
  const countNum = parsePositiveInt(count);
  const totalWei = parsePositiveBigInt(totalAmountWei);
  const expiresAtNum = parsePositiveInt(expiresAt);
  // const expiresAtNum = 1778342388;
  const tokenSymbolClean = typeof tokenSymbol === 'string' ? tokenSymbol.trim() : '';
  const isNativeBnb = tokenSymbolClean.toUpperCase() === 'BNB';
  const tokenAddr = normalizeAddress(tokenAddress);
  const tokenDecimalsNum = Number.isInteger(Number(tokenDecimals)) && Number(tokenDecimals) >= 0
    ? Number(tokenDecimals)
    : (isNativeBnb ? 18 : null);

  if (!creator) return badRequest(res, 'creatorWallet invalid');
  if (!countNum || countNum > MAX_PACKET_COUNT) return badRequest(res, `count must be 1-${MAX_PACKET_COUNT}`);
  if (!totalWei) return badRequest(res, 'totalAmountWei invalid');
  const currentSeconds = nowSeconds();
  if (!expiresAtNum || expiresAtNum <= currentSeconds) return badRequest(res, 'expiresAt must be in the future');
  const maxExpiresInSeconds = Number(runtimeSettings.maxExpiresInSeconds || BOOTSTRAP_MAX_EXPIRES_IN_SECONDS);
  if (maxExpiresInSeconds > 0 && expiresAtNum > currentSeconds + maxExpiresInSeconds) {
    return badRequest(res, `expiresAt must be within ${maxExpiresInSeconds} seconds`);
  }
  const redPacketContract = normalizeAddress(runtimeSettings.redPacketContract);
  if (!redPacketContract) return badRequest(res, 'redPacketContract is not configured');
  if (totalWei % BigInt(countNum) !== 0n) return badRequest(res, 'totalAmountWei must be divisible by count');
  if (!isNativeBnb && !tokenAddr) return badRequest(res, 'tokenAddress invalid');
  if (!tokenSymbolClean) return badRequest(res, 'tokenSymbol invalid');
  if (tokenDecimalsNum === null) return badRequest(res, 'tokenDecimals invalid');

  const packetId = `tg-${Date.now()}-${crypto.randomBytes(3).toString('hex')}`;
  const amountPerClaimWei = totalWei / BigInt(countNum);
  const createdAt = currentSeconds;

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
    tokenAddress: tokenAddr || ZERO_ADDRESS,
    tokenSymbol: tokenSymbolClean,
    tokenDecimals: tokenDecimalsNum,
    greeting: typeof greeting === 'string' ? greeting : '',
    packetType: typeof packetType === 'string' ? packetType : '',
    chainId: CHAIN_ID,
    contractAddress: redPacketContract,
    claimUrl: `${runtimeSettings.publicHost}/claim/${packetId}`,
    legacyClaimUrl: `${runtimeSettings.publicHost}/claim/${packetId}`,
    createdAt,
    updatedAt: createdAt,
  };

  let createAuthorization;
  try {
    createAuthorization = await signCreateAuthorization(packet, creator);
  } catch (error) {
    // eslint-disable-next-line no-console
    console.error('[create-sign-error]', { packetId: packet.packetId, error: error.message });
    return badRequest(res, 'red packet auth signer is not configured correctly');
  }

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
      createSignatureHex: createAuthorization.signatureHex,
      claimSignerAddress: createAuthorization.claimSignerAddress,
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

  const event = parseExpectedLog(receipt, 'PacketCreated', packet.contractAddress);
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
  if (!eventTokenMatchesPacket(event, packet)) return badRequest(res, 'PacketCreated token mismatch');
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
  const status = String(req.query.status || '').trim();
  const limit = Number(req.query.limit || 50);
  const offset = Number(req.query.offset || 0);
  if (!normalizeAddress(creatorWallet)) {
    return badRequest(res, 'creatorWallet invalid');
  }
  const records = await db.getSendRecordsByCreator(creatorWallet, status, limit, offset);
  return res.json({ ok: true, data: { records, limit, offset, hasMore: records.length >= Math.min(Math.max(Number(limit) || 20, 1), 200) } });
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
  const runtimeSettings = await getRuntimeSettings();
  return res.json({
    ok: true,
    data: {
      address: runtimeSettings.proxyAddress,
      port: runtimeSettings.proxyPort,
      username: runtimeSettings.proxyUsername,
      password: runtimeSettings.proxyPassword,
      secret: runtimeSettings.proxySecret,
      updatedAt: nowSeconds(),
    },
  });
});

app.get('/api/v1/client/version/check', async (req, res) => {
  const clientVersionCode = Number(req.query.versionCode || 0);
  const clientVersionName = String(req.query.versionName || '').trim();
  const checkedAt = nowSeconds();
  const runtimeSettings = await getRuntimeSettings();
  let latestVersion = null;

  try {
    latestVersion = await db.getLatestClientVersion();
  } catch (error) {
    // eslint-disable-next-line no-console
    console.error('[client-version-check-db-error]', error);
  }

  const serverVersionCode = latestVersion ? Number(latestVersion.version_code || 0) : Number(runtimeSettings.fallbackVersionCode || 1);
  const serverVersionName = latestVersion ? String(latestVersion.version_name || '') : String(runtimeSettings.fallbackVersionName || '1.0.0');
  const releaseDate = latestVersion
    ? Number(latestVersion.release_date || latestVersion.created_at || checkedAt)
    : (Number(runtimeSettings.fallbackReleaseDate || 0) > 0 ? Number(runtimeSettings.fallbackReleaseDate) : checkedAt);
  const apkSizeBytes = latestVersion
    ? Number(latestVersion.apk_size_bytes || 0)
    : Number(runtimeSettings.fallbackApkSizeBytes || 0);
  const downloadUrl = latestVersion ? String(latestVersion.download_url || '') : String(runtimeSettings.fallbackDownloadUrl || '');
  const messageText = latestVersion ? String(latestVersion.release_notes || '') : String(runtimeSettings.fallbackVersionMessage || '');
  const forceUpdate = latestVersion ? Boolean(latestVersion.force_update) : false;
  const hasUpdate = clientVersionCode > 0
    ? clientVersionCode < serverVersionCode
    : true;

  return res.json({
    ok: true,
    data: {
      hasUpdate,
      currentVersionCode: clientVersionCode,
      currentVersionName: clientVersionName,
      versionCode: serverVersionCode,
      versionName: serverVersionName,
      releaseDate,
      apkSizeBytes: apkSizeBytes > 0 ? apkSizeBytes : null,
      downloadUrl: hasUpdate ? downloadUrl : '',
      message: messageText,
      releaseNotes: messageText,
      forceUpdate: hasUpdate ? forceUpdate : false,
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

  let claimAuthorization;
  try {
    claimAuthorization = await signClaimAuthorization(packet, claimerAddress);
  } catch (error) {
    // eslint-disable-next-line no-console
    console.error('[claim-sign-error]', { packetId: packet.packetId, claimerAddress, error: error.message });
    return badRequest(res, 'red packet auth signer is not configured correctly');
  }

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
      signatureHex: claimAuthorization.signatureHex,
      claimSignerAddress: claimAuthorization.claimSignerAddress,
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

  const event = parseExpectedLog(receipt, 'Claimed', packet.contractAddress);
  if (!event) return badRequest(res, 'Claimed event not found');

  const eventPacketIdHex = String(event.args.packetId).toLowerCase();
  const eventClaimer = normalizeAddress(String(event.args.claimer));
  const eventAmountRaw = event.args.amount ?? event.args[3];
  const eventAmount = BigInt(eventAmountRaw).toString();

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'Claimed packetId mismatch');
  if (eventClaimer !== claimerAddress) return badRequest(res, 'Claimed claimer mismatch');
  if (!eventTokenMatchesPacket(event, packet)) return badRequest(res, 'Claimed token mismatch');
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

  if (String(packet.status || '') === 'refunded') {
    const existingRefund = await db.getRefundByPacketTx(packet.packetId, txHash);
    if (existingRefund) {
      return res.json({
        ok: true,
        data: {
          packetId: packet.packetId,
          txHash,
          refunded: true,
          status: getPacketStatus(packet),
          remainingCount: packet.remainingCount,
          refundAmountWei: String(existingRefund.amount_wei || '0'),
          alreadyConfirmed: true,
        },
      });
    }
    return badRequest(res, 'already refunded');
  }

  if (!packet.onchainCreated) return badRequest(res, 'packet not confirmed on chain');
  if (packet.remainingCount <= 0) return badRequest(res, 'nothing to refund');
  if (nowSeconds() <= Number(packet.expiresAt)) return badRequest(res, 'packet not expired');
  const receipt = await getTransactionReceipt(txHash);
  if (!receipt || receipt.status !== 1) return badRequest(res, 'transaction not confirmed');

  const event = parseExpectedLog(receipt, 'Refunded', packet.contractAddress);
  if (!event) return badRequest(res, 'Refunded event not found');

  const eventPacketIdHex = String(event.args.packetId).toLowerCase();
  const eventCreator = normalizeAddress(String(event.args.creator));
  const eventAmountRaw = event.args.amount ?? event.args[3];
  const eventAmount = BigInt(eventAmountRaw).toString();

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'Refunded packetId mismatch');
  if (eventCreator !== creatorAddress) return badRequest(res, 'Refunded creator mismatch');
  if (!eventTokenMatchesPacket(event, packet)) return badRequest(res, 'Refunded token mismatch');
  if (eventAmount !== getExpectedRefundAmountWei(packet)) return badRequest(res, 'Refunded amount mismatch');

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
  await db.ensureSettingDefaults();
  await ensureRuntimeDirectories();
  app.listen(port, () => {
    // eslint-disable-next-line no-console
    console.log(`red-packet service listening on http://127.0.0.1:${port}`);
    // eslint-disable-next-line no-console
    console.log(`admin API available at http://127.0.0.1:${port}${ADMIN_BASE_PATH}`);
    // eslint-disable-next-line no-console
    console.log(`admin web path: http://127.0.0.1:${port}${ADMIN_WEB_BASE_PATH}`);
  });
})();
