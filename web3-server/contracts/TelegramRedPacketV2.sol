// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

contract TelegramRedPacketV2 is ReentrancyGuard {
    using SafeERC20 for IERC20;

    string public name;
    string public symbol;

    constructor(string memory name_, string memory symbol_) {
        name = bytes(name_).length == 0 ? "ETZRedPacket" : name_;
        symbol = bytes(symbol_).length == 0 ? "ETZRedPacket" : symbol_;
    }

    uint32 public constant MAX_PACKET_COUNT = 500;
    uint64 public constant MAX_EXPIRES_IN = 30 days;

    struct Packet {
        address creator;
        address token;
        uint256 total;
        uint256 amountPerClaim;
        uint32 totalCount;
        uint32 claimedCount;
        uint64 expiresAt;
        bool refunded;
        mapping(address => bool) claimed;
    }

    mapping(bytes32 => Packet) private packets;

    event PacketCreated(
        bytes32 indexed packetId,
        address indexed creator,
        address indexed token,
        uint256 total,
        uint32 count,
        uint64 expiresAt
    );
    event Claimed(bytes32 indexed packetId, address indexed claimer, address indexed token, uint256 amount);
    event Refunded(bytes32 indexed packetId, address indexed creator, address indexed token, uint256 amount);

    function createNativePacket(bytes32 packetId, uint32 count, uint64 expiresAt) external payable nonReentrant {
        _validateCreate(packetId, count, expiresAt, msg.value);

        Packet storage p = packets[packetId];
        p.creator = msg.sender;
        p.token = address(0);
        p.total = msg.value;
        p.amountPerClaim = msg.value / count;
        p.totalCount = count;
        p.expiresAt = expiresAt;

        emit PacketCreated(packetId, msg.sender, address(0), msg.value, count, expiresAt);
    }

    function createTokenPacket(
        bytes32 packetId,
        address token,
        uint256 totalAmount,
        uint32 count,
        uint64 expiresAt
    ) external nonReentrant {
        require(token != address(0), "token=0");
        _validateCreate(packetId, count, expiresAt, totalAmount);

        Packet storage p = packets[packetId];
        p.creator = msg.sender;
        p.token = token;
        p.total = totalAmount;
        p.amountPerClaim = totalAmount / count;
        p.totalCount = count;
        p.expiresAt = expiresAt;

        IERC20(token).safeTransferFrom(msg.sender, address(this), totalAmount);

        emit PacketCreated(packetId, msg.sender, token, totalAmount, count, expiresAt);
    }

    function claim(bytes32 packetId) external nonReentrant {
        Packet storage p = packets[packetId];
        require(p.creator != address(0), "not found");
        require(!p.refunded, "refunded");
        require(block.timestamp <= p.expiresAt, "expired");
        require(!p.claimed[msg.sender], "claimed");
        require(p.claimedCount < p.totalCount, "empty");

        p.claimed[msg.sender] = true;
        p.claimedCount += 1;

        if (p.token == address(0)) {
            (bool sent, ) = payable(msg.sender).call{value: p.amountPerClaim}("");
            require(sent, "native transfer failed");
        } else {
            IERC20(p.token).safeTransfer(msg.sender, p.amountPerClaim);
        }

        emit Claimed(packetId, msg.sender, p.token, p.amountPerClaim);
    }

    function refund(bytes32 packetId) external nonReentrant {
        Packet storage p = packets[packetId];
        require(p.creator == msg.sender, "not creator");
        require(!p.refunded, "already refunded");
        require(block.timestamp > p.expiresAt || p.claimedCount == p.totalCount, "not end");

        p.refunded = true;
        uint256 remaining = p.amountPerClaim * (p.totalCount - p.claimedCount);

        if (remaining > 0) {
            if (p.token == address(0)) {
                (bool sent, ) = payable(msg.sender).call{value: remaining}("");
                require(sent, "native refund failed");
            } else {
                IERC20(p.token).safeTransfer(msg.sender, remaining);
            }
        }

        emit Refunded(packetId, msg.sender, p.token, remaining);
    }

    function getPacket(bytes32 packetId)
        external
        view
        returns (
            address creator,
            address token,
            uint256 total,
            uint256 amountPerClaim,
            uint32 totalCount,
            uint32 claimedCount,
            uint64 expiresAt,
            bool refunded,
            bool ended
        )
    {
        Packet storage p = packets[packetId];
        creator = p.creator;
        token = p.token;
        total = p.total;
        amountPerClaim = p.amountPerClaim;
        totalCount = p.totalCount;
        claimedCount = p.claimedCount;
        expiresAt = p.expiresAt;
        refunded = p.refunded;
        ended = p.creator == address(0) || p.refunded || p.claimedCount == p.totalCount || block.timestamp > p.expiresAt;
    }

    function hasClaimed(bytes32 packetId, address claimer) external view returns (bool) {
        return packets[packetId].claimed[claimer];
    }

    function _validateCreate(bytes32 packetId, uint32 count, uint64 expiresAt, uint256 totalAmount) private view {
        require(packetId != bytes32(0), "packetId=0");
        require(count > 0 && count <= MAX_PACKET_COUNT, "invalid count");
        require(totalAmount > 0, "amount=0");
        require(expiresAt > block.timestamp, "expired");
        require(expiresAt <= block.timestamp + MAX_EXPIRES_IN, "expires too far");
        require(totalAmount % count == 0, "not divisible");

        Packet storage p = packets[packetId];
        require(p.creator == address(0), "exists");
    }
}
