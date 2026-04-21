/**
 * Red-packet service for Telegram wallet integration.
 * Local run:
 *   npm i
 *   npm run dev
 */
const crypto = require('crypto');
const express = require('express');

const app = express();
app.use(express.json({ limit: '256kb' }));

const CHAIN_ID = Number(process.env.CHAIN_ID || 56);
const CONTRACT_ADDRESS = process.env.RED_PACKET_CONTRACT || '0xYourContractAddress';
const HOST = process.env.PUBLIC_HOST || 'http://127.0.0.1:8787';
const MAX_PACKET_COUNT = 500;

/** @type {Map<string, any>} */
const packets = new Map();

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function normalizeAddress(value) {
  if (typeof value !== 'string') return '';
  const v = value.trim();
  if (!/^0x[0-9a-fA-F]{40}$/.test(v)) return '';
  return v.toLowerCase();
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

function buildPacketResponse(packet) {
  const expired = nowSeconds() > Number(packet.expiresAt);
  const ended = packet.status === 'refunded' || packet.status === 'empty' || expired;
  return {
    ...packet,
    expired,
    ended,
    canClaim: !ended && packet.remainingCount > 0,
    canRefund: packet.creatorWallet === packet.requestWallet && packet.remainingCount > 0 && (expired || packet.status === 'empty'),
  };
}

function badRequest(res, message) {
  return res.status(400).json({ ok: false, message });
}

app.get('/healthz', (_, res) => {
  res.json({ ok: true, service: 'web3-red-packet', chainId: CHAIN_ID, ts: nowSeconds() });
});

app.post('/api/v1/red-packets/create', (req, res) => {
  const { dialogId, creatorWallet, totalAmountWei, count, expiresAt } = req.body || {};

  const creator = normalizeAddress(creatorWallet);
  const countNum = parsePositiveInt(count);
  const totalWei = parsePositiveBigInt(totalAmountWei);
  const expiresAtNum = parsePositiveInt(expiresAt);

  if (!creator) return badRequest(res, 'creatorWallet invalid');
  if (!countNum || countNum > MAX_PACKET_COUNT) return badRequest(res, `count must be 1-${MAX_PACKET_COUNT}`);
  if (!totalWei) return badRequest(res, 'totalAmountWei invalid');
  if (!expiresAtNum || expiresAtNum <= nowSeconds()) return badRequest(res, 'expiresAt must be in the future');
  if (totalWei % BigInt(countNum) !== 0n) return badRequest(res, 'totalAmountWei must be divisible by count');

  const packetId = `tg-${Date.now()}-${crypto.randomBytes(3).toString('hex')}`;
  const amountPerClaimWei = totalWei / BigInt(countNum);

  const packet = {
    packetId,
    packetIdHex: packetIdToHex(packetId),
    dialogId,
    creatorWallet: creator,
    requestWallet: creator,
    totalAmountWei: totalWei.toString(),
    amountPerClaimWei: amountPerClaimWei.toString(),
    count: countNum,
    remainingCount: countNum,
    claimedWallets: [],
    expiresAt: expiresAtNum,
    status: 'active',
    tokenSymbol: 'BNB',
    chainId: CHAIN_ID,
    contractAddress: CONTRACT_ADDRESS,
    claimUrl: `${HOST}/claim/${packetId}`,
    createdAt: nowSeconds(),
    updatedAt: nowSeconds(),
  };

  packets.set(packetId, packet);

  return res.json({ ok: true, data: {
    packetId: packet.packetId,
    packetIdHex: packet.packetIdHex,
    claimUrl: packet.claimUrl,
    contractAddress: packet.contractAddress,
    chainId: packet.chainId,
    expiresAt: packet.expiresAt,
    totalAmountWei: packet.totalAmountWei,
    tokenSymbol: packet.tokenSymbol,
    count: packet.count,
  }});
});

app.get('/api/v1/red-packets/:packetId', (req, res) => {
  const packet = packets.get(req.params.packetId);
  if (!packet) return res.status(404).json({ ok: false, message: 'not found' });

  packet.requestWallet = normalizeAddress(req.query.wallet || packet.requestWallet) || packet.requestWallet;
  return res.json({ ok: true, data: buildPacketResponse(packet) });
});

app.post('/api/v1/red-packets/:packetId/claim/prepare', (req, res) => {
  const packet = packets.get(req.params.packetId);
  if (!packet) return res.status(404).json({ ok: false, message: 'not found' });

  const claimerAddress = normalizeAddress(req.body?.claimerAddress);
  if (!claimerAddress) return badRequest(res, 'claimerAddress invalid');

  const expired = nowSeconds() > Number(packet.expiresAt);
  if (expired) return badRequest(res, 'packet expired');
  if (packet.remainingCount <= 0) return badRequest(res, 'packet empty');
  if (packet.claimedWallets.includes(claimerAddress)) return badRequest(res, 'already claimed');

  packet.requestWallet = claimerAddress;
  packet.updatedAt = nowSeconds();

  return res.json({ ok: true, data: {
    packetIdHex: packet.packetIdHex,
    signatureHex: `0x${'11'.repeat(65)}`,
    contractAddress: packet.contractAddress,
    chainId: packet.chainId,
    claimerAddress,
    amountPerClaimWei: packet.amountPerClaimWei,
  }});
});

app.post('/api/v1/red-packets/:packetId/confirm', (req, res) => {
  const packet = packets.get(req.params.packetId);
  if (!packet) return res.status(404).json({ ok: false, message: 'not found' });

  const claimerAddress = normalizeAddress(req.body?.claimerAddress);
  const txHash = String(req.body?.txHash || '').trim();
  if (!claimerAddress) return badRequest(res, 'claimerAddress invalid');
  if (!/^0x[0-9a-fA-F]{64}$/.test(txHash)) return badRequest(res, 'txHash invalid');

  const expired = nowSeconds() > Number(packet.expiresAt);
  if (expired) return badRequest(res, 'packet expired');
  if (packet.remainingCount <= 0) return badRequest(res, 'packet empty');
  if (packet.claimedWallets.includes(claimerAddress)) return badRequest(res, 'already claimed');

  packet.claimedWallets.push(claimerAddress);
  packet.remainingCount -= 1;
  packet.updatedAt = nowSeconds();
  if (packet.remainingCount <= 0) {
    packet.status = 'empty';
  }

  return res.json({ ok: true, data: {
    packetId: packet.packetId,
    txHash,
    remainingCount: packet.remainingCount,
    status: packet.status,
  }});
});

const port = Number(process.env.PORT || 8787);
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`red-packet service listening on http://127.0.0.1:${port}`);
});
