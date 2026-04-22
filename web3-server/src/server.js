/**
 * Red-packet service for Telegram wallet integration.
 * Local run:
 *   npm i
 *   npm run dev
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const express = require('express');
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
const DB_FILE = process.env.RED_PACKET_DB_FILE || path.join(__dirname, '../data/red-packets.json');
const RPC_URL = process.env.RPC_URL || 'https://data-seed-prebsc-1-s1.bnbchain.org:8545';

const provider = new JsonRpcProvider(RPC_URL, CHAIN_ID);
const contractAddressNorm = normalizeAddress(CONTRACT_ADDRESS);

const contractInterface = new Interface([
  'event PacketCreated(bytes32 indexed packetId, address indexed creator, uint256 total, uint32 count, uint64 expiresAt)',
  'event Claimed(bytes32 indexed packetId, address indexed claimer, uint256 amount)',
]);

class JsonDB {
  constructor(filePath) {
    this.filePath = filePath;
    this.data = { packets: {} };
  }

  init() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    if (!fs.existsSync(this.filePath)) {
      fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), 'utf8');
      return;
    }
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
      if (parsed && typeof parsed === 'object' && parsed.packets && typeof parsed.packets === 'object') {
        this.data = parsed;
      }
    } catch (_) {
      fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), 'utf8');
    }
  }

  save() {
    fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), 'utf8');
  }

  getPacket(packetId) {
    return this.data.packets[packetId] || null;
  }

  upsertPacket(packet) {
    this.data.packets[packet.packetId] = packet;
    this.save();
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
}

const db = new JsonDB(DB_FILE);
db.init();

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

function ensurePacket(packetId, res) {
  const packet = db.getPacket(packetId);
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
  try {
    await provider.getBlockNumber();
  } catch (_) {
    rpcOk = false;
  }

  res.json({
    ok: true,
    service: 'web3-red-packet',
    chainId: CHAIN_ID,
    contractAddress: CONTRACT_ADDRESS,
    rpcUrl: RPC_URL,
    rpcOk,
    ts: nowSeconds(),
  });
});

app.post('/api/v1/red-packets/prepare-create', (req, res) => {
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

  db.upsertPacket(packet);

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
  const packet = ensurePacket(req.params.packetId, res);
  if (!packet) return;

  const txHash = String(req.body?.txHash || '').trim();
  if (!/^0x[0-9a-fA-F]{64}$/.test(txHash)) return badRequest(res, 'txHash invalid');

  const receipt = await getTransactionReceipt(txHash);
  if (!receipt || receipt.status !== 1) return badRequest(res, 'transaction not confirmed');

  const event = parseExpectedLog(receipt, 'PacketCreated');
  if (!event) return badRequest(res, 'PacketCreated event not found');

  const eventPacketIdHex = String(event.args.packetId).toLowerCase();
  const eventCreator = normalizeAddress(String(event.args.creator));
  const eventTotal = BigInt(event.args.total).toString();
  const eventCount = Number(event.args.count);
  const eventExpiresAt = Number(event.args.expiresAt);

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'PacketCreated packetId mismatch');
  if (eventCreator !== packet.creatorWallet) return badRequest(res, 'PacketCreated creator mismatch');
  if (eventTotal !== packet.totalAmountWei) return badRequest(res, 'PacketCreated total mismatch');
  if (eventCount !== packet.count) return badRequest(res, 'PacketCreated count mismatch');
  if (eventExpiresAt !== Number(packet.expiresAt)) return badRequest(res, 'PacketCreated expiresAt mismatch');

  packet.onchainCreated = true;
  packet.status = 'active';
  packet.createTxHash = txHash;
  packet.updatedAt = nowSeconds();
  db.upsertPacket(packet);
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

app.get('/api/v1/red-packets/:packetId', (req, res) => {
  const packet = ensurePacket(req.params.packetId, res);
  if (!packet) return;
  return res.json({ ok: true, data: buildPacketResponse(packet, req.query.wallet) });
});

app.post('/api/v1/red-packets/:packetId/claim/prepare', (req, res) => {
  const packet = ensurePacket(req.params.packetId, res);
  if (!packet) return;

  const claimerAddress = normalizeAddress(req.body?.claimerAddress);
  if (!claimerAddress) return badRequest(res, 'claimerAddress invalid');

  const status = getPacketStatus(packet);
  if (status === 'expired') return badRequest(res, 'packet expired');
  if (status === 'empty') return badRequest(res, 'packet empty');
  if (!packet.onchainCreated) return badRequest(res, 'packet not confirmed on chain');
  if (packet.claimedWallets.includes(claimerAddress)) return badRequest(res, 'already claimed');

  packet.updatedAt = nowSeconds();
  db.upsertPacket(packet);
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
      // 合约不需要签名时，不返回 signatureHex。
    },
  });
});

app.post('/api/v1/red-packets/:packetId/claim-confirm', async (req, res) => {
  const packet = ensurePacket(req.params.packetId, res);
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
  const eventAmount = BigInt(event.args.amount).toString();

  if (eventPacketIdHex !== packet.packetIdHex.toLowerCase()) return badRequest(res, 'Claimed packetId mismatch');
  if (eventClaimer !== claimerAddress) return badRequest(res, 'Claimed claimer mismatch');
  if (eventAmount !== packet.amountPerClaimWei) return badRequest(res, 'Claimed amount mismatch');

  packet.claimedWallets.push(claimerAddress);
  packet.remainingCount -= 1;
  packet.updatedAt = nowSeconds();
  if (packet.remainingCount <= 0) {
    packet.status = 'empty';
  }
  db.upsertPacket(packet);
  // eslint-disable-next-line no-console
  console.log('[claim-confirmed]', {
    packetId: packet.packetId,
    claimerAddress,
    txHash,
    remainingCount: packet.remainingCount,
  });

  return res.json({
    ok: true,
    data: {
      packetId: packet.packetId,
      txHash,
      remainingCount: packet.remainingCount,
      status: getPacketStatus(packet),
    },
  });
});

const port = Number(process.env.PORT || 8787);
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`red-packet service listening on http://127.0.0.1:${port}`);
});