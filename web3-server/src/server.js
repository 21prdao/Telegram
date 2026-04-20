/**
 * Minimal red-packet service for Telegram wallet integration.
 * Run: npm i express ethers body-parser && node src/server.js
 */
const express = require('express');
const bodyParser = require('body-parser');

const app = express();
app.use(bodyParser.json());

const packets = new Map();

app.post('/api/v1/red-packets/create', (req, res) => {
  const { dialogId, creatorWallet, totalAmountWei, count, expiresAt } = req.body;
  const packetId = `tg-${Date.now()}-${Math.floor(Math.random()*100000)}`;
  packets.set(packetId, {
    packetId,
    dialogId,
    creatorWallet,
    totalAmountWei,
    count,
    remainingCount: Number(count),
    expiresAt,
    status: 'active',
    tokenSymbol: 'BNB',
    amountPerClaimWei: (BigInt(totalAmountWei) / BigInt(count)).toString(),
  });

  res.json({ data: {
    packetId,
    packetIdHex: '0x' + Buffer.from(packetId).toString('hex').slice(0, 64).padEnd(64, '0'),
    claimUrl: `https://rp.yourdomain.com/claim/${packetId}`,
    contractAddress: process.env.RED_PACKET_CONTRACT || '0xYourContractAddress',
    expiresAt,
    totalAmountWei,
    tokenSymbol: 'BNB',
    count,
  }});
});

app.get('/api/v1/red-packets/:packetId', (req, res) => {
  const packet = packets.get(req.params.packetId);
  if (!packet) return res.status(404).json({ message: 'not found' });
  const expired = Date.now() / 1000 > Number(packet.expiresAt);
  res.json({ data: {
    ...packet,
    expired,
    canClaim: !expired && packet.remainingCount > 0,
    canRefund: expired && packet.remainingCount > 0,
  }});
});

app.post('/api/v1/red-packets/:packetId/claim/prepare', (req, res) => {
  const packet = packets.get(req.params.packetId);
  if (!packet) return res.status(404).json({ message: 'not found' });
  res.json({ data: {
    packetIdHex: '0x' + Buffer.from(packet.packetId).toString('hex').slice(0, 64).padEnd(64, '0'),
    signatureHex: '0x' + '11'.repeat(65),
    contractAddress: process.env.RED_PACKET_CONTRACT || '0xYourContractAddress',
    chainId: 56,
    claimerAddress: req.body.claimerAddress,
  }});
});

app.post('/api/v1/red-packets/:packetId/confirm', (req, res) => {
  const packet = packets.get(req.params.packetId);
  if (!packet) return res.status(404).json({ message: 'not found' });
  res.json({ ok: true });
});

app.listen(process.env.PORT || 8787, () => {
  console.log('red-packet service listening on', process.env.PORT || 8787);
});
