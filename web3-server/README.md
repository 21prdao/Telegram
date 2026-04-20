# Web3 Red-Packet Backend + Contract (BNB Chain)

This folder provides a minimal custom backend and contract for the Telegram Web3 wallet/red-packet flow.

## Components

- `contracts/TelegramRedPacket.sol`: Solidity contract for creating, claiming, and refunding red packets.
- `src/server.js`: minimal Node.js API implementing:
  - `POST /api/v1/red-packets/create`
  - `GET /api/v1/red-packets/:packetId`
  - `POST /api/v1/red-packets/:packetId/claim/prepare`
  - `POST /api/v1/red-packets/:packetId/confirm`

## Notes

- Current server uses in-memory storage for demo purposes. Replace with DB + signature generation in production.
- Replace `signatureHex` mock with a real backend signer and validate on-chain in your contract.
- Set Android `BuildConfig.WEB3_RED_PACKET_HOST` and `BuildConfig.RED_PACKET_CONTRACT` to your deployed values.
