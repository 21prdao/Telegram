// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract TelegramRedPacket {
    struct Packet {
        address creator;
        uint256 total;
        uint256 amountPerClaim;
        uint32 totalCount;
        uint32 claimedCount;
        uint64 expiresAt;
        bool refunded;
        mapping(address => bool) claimed;
    }

    mapping(bytes32 => Packet) private packets;

    event PacketCreated(bytes32 indexed packetId, address indexed creator, uint256 total, uint32 count, uint64 expiresAt);
    event Claimed(bytes32 indexed packetId, address indexed claimer, uint256 amount);
    event Refunded(bytes32 indexed packetId, address indexed creator, uint256 amount);

    function createPacket(bytes32 packetId, uint32 count, uint64 expiresAt) external payable {
        require(count > 0, "count=0");
        require(msg.value > 0, "value=0");
        require(expiresAt > block.timestamp, "expired");
        Packet storage p = packets[packetId];
        require(p.creator == address(0), "exists");
        require(msg.value % count == 0, "not divisible");

        p.creator = msg.sender;
        p.total = msg.value;
        p.amountPerClaim = msg.value / count;
        p.totalCount = count;
        p.expiresAt = expiresAt;

        emit PacketCreated(packetId, msg.sender, msg.value, count, expiresAt);
    }

    function claim(bytes32 packetId) external {
        Packet storage p = packets[packetId];
        require(p.creator != address(0), "not found");
        require(!p.refunded, "refunded");
        require(block.timestamp <= p.expiresAt, "expired");
        require(!p.claimed[msg.sender], "claimed");
        require(p.claimedCount < p.totalCount, "empty");

        p.claimed[msg.sender] = true;
        p.claimedCount += 1;
        payable(msg.sender).transfer(p.amountPerClaim);

        emit Claimed(packetId, msg.sender, p.amountPerClaim);
    }

    function refund(bytes32 packetId) external {
        Packet storage p = packets[packetId];
        require(p.creator == msg.sender, "not creator");
        require(!p.refunded, "already");
        require(block.timestamp > p.expiresAt || p.claimedCount == p.totalCount, "not end");

        p.refunded = true;
        uint256 remaining = p.amountPerClaim * (p.totalCount - p.claimedCount);
        if (remaining > 0) {
            payable(msg.sender).transfer(remaining);
        }

        emit Refunded(packetId, msg.sender, remaining);
    }
}
