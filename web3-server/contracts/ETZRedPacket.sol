// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";

contract ETZRedPacket is ReentrancyGuard {
    using SafeERC20 for IERC20;

    string public name;
    string public symbol;

    address public owner;
    address public claimSigner;

    uint32 public constant MAX_PACKET_COUNT = 500;
    uint64 public constant MAX_EXPIRES_IN = 30 days;

    bytes32 private constant CREATE_AUTHORIZATION_PREFIX = keccak256("TelegramRedPacketV2:CREATE");
    bytes32 private constant CLAIM_AUTHORIZATION_PREFIX = keccak256("TelegramRedPacketV2:CLAIM");

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
    event ClaimSignerChanged(address indexed oldSigner, address indexed newSigner);
    event OwnershipTransferred(address indexed oldOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "not owner");
        _;
    }

    constructor(string memory name_, string memory symbol_) {
        name = bytes(name_).length == 0 ? "ETZRedPacket" : name_;
        symbol = bytes(symbol_).length == 0 ? "ETZRedPacket" : symbol_;
        owner = msg.sender;
        claimSigner = msg.sender;
        emit OwnershipTransferred(address(0), msg.sender);
        emit ClaimSignerChanged(address(0), msg.sender);
    }

    function setClaimSigner(address newSigner) external onlyOwner {
        require(newSigner != address(0), "signer=0");
        address oldSigner = claimSigner;
        claimSigner = newSigner;
        emit ClaimSignerChanged(oldSigner, newSigner);
    }

    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "owner=0");
        address oldOwner = owner;
        owner = newOwner;
        emit OwnershipTransferred(oldOwner, newOwner);
    }

    function createNativePacket(bytes32, uint32, uint64) external payable {
        revert("signature required");
    }

    function createNativePacket(
        bytes32 packetId,
        uint32 count,
        uint64 expiresAt,
        bytes calldata signature
    ) external payable nonReentrant {
        _validateCreateSignature(packetId, msg.sender, address(0), msg.value, count, expiresAt, signature);
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

    function createTokenPacket(bytes32, address, uint256, uint32, uint64) external pure {
        revert("signature required");
    }

    function createTokenPacket(
        bytes32 packetId,
        address token,
        uint256 totalAmount,
        uint32 count,
        uint64 expiresAt,
        bytes calldata signature
    ) external nonReentrant {
        require(token != address(0), "token=0");
        _validateCreateSignature(packetId, msg.sender, token, totalAmount, count, expiresAt, signature);
        _validateCreate(packetId, count, expiresAt, totalAmount);

        uint256 balanceBefore = IERC20(token).balanceOf(address(this));
        IERC20(token).safeTransferFrom(msg.sender, address(this), totalAmount);
        uint256 received = IERC20(token).balanceOf(address(this)) - balanceBefore;
        require(received == totalAmount, "token transfer mismatch");

        Packet storage p = packets[packetId];
        p.creator = msg.sender;
        p.token = token;
        p.total = totalAmount;
        p.amountPerClaim = totalAmount / count;
        p.totalCount = count;
        p.expiresAt = expiresAt;

        emit PacketCreated(packetId, msg.sender, token, totalAmount, count, expiresAt);
    }

    function claim(bytes32) external pure {
        revert("signature required");
    }

    function claim(bytes32 packetId, bytes calldata signature) external nonReentrant {
        _validateClaimSignature(packetId, msg.sender, signature);
        _claim(packetId, msg.sender);
    }

    function refund(bytes32 packetId) external nonReentrant {
        Packet storage p = packets[packetId];
        require(p.creator == msg.sender, "not creator");
        require(!p.refunded, "already refunded");
        require(block.timestamp > p.expiresAt, "not expired");

        uint256 remaining = p.amountPerClaim * (p.totalCount - p.claimedCount);
        require(remaining > 0, "nothing to refund");

        p.refunded = true;

        if (p.token == address(0)) {
            (bool sent, ) = payable(msg.sender).call{value: remaining}("");
            require(sent, "native refund failed");
        } else {
            IERC20(p.token).safeTransfer(msg.sender, remaining);
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

    function getRemainingAmount(bytes32 packetId) external view returns (uint256) {
        Packet storage p = packets[packetId];
        if (p.creator == address(0) || p.refunded) {
            return 0;
        }
        return p.amountPerClaim * (p.totalCount - p.claimedCount);
    }

    function canRefund(bytes32 packetId, address wallet) external view returns (bool) {
        Packet storage p = packets[packetId];
        if (p.creator == address(0) || p.refunded || wallet != p.creator) {
            return false;
        }
        return block.timestamp > p.expiresAt && p.claimedCount < p.totalCount;
    }

    function getCreateAuthorizationDigest(
        bytes32 packetId,
        address creator,
        address token,
        uint256 totalAmount,
        uint32 count,
        uint64 expiresAt
    ) public view returns (bytes32) {
        return keccak256(abi.encodePacked(
            CREATE_AUTHORIZATION_PREFIX,
            block.chainid,
            address(this),
            packetId,
            creator,
            token,
            totalAmount,
            count,
            expiresAt
        ));
    }

    function getClaimAuthorizationDigest(bytes32 packetId, address claimer) public view returns (bytes32) {
        return keccak256(abi.encodePacked(
            CLAIM_AUTHORIZATION_PREFIX,
            block.chainid,
            address(this),
            packetId,
            claimer
        ));
    }

    function getEthSignedCreateAuthorizationHash(
        bytes32 packetId,
        address creator,
        address token,
        uint256 totalAmount,
        uint32 count,
        uint64 expiresAt
    ) external view returns (bytes32) {
        return _toEthSignedMessageHash(getCreateAuthorizationDigest(packetId, creator, token, totalAmount, count, expiresAt));
    }

    function getEthSignedClaimAuthorizationHash(bytes32 packetId, address claimer) external view returns (bytes32) {
        return _toEthSignedMessageHash(getClaimAuthorizationDigest(packetId, claimer));
    }

    function _claim(bytes32 packetId, address claimer) private {
        Packet storage p = packets[packetId];
        require(p.creator != address(0), "not found");
        require(!p.refunded, "refunded");
        require(block.timestamp <= p.expiresAt, "expired");
        require(!p.claimed[claimer], "claimed");
        require(p.claimedCount < p.totalCount, "empty");

        p.claimed[claimer] = true;
        p.claimedCount += 1;

        if (p.token == address(0)) {
            (bool sent, ) = payable(claimer).call{value: p.amountPerClaim}("");
            require(sent, "native transfer failed");
        } else {
            IERC20(p.token).safeTransfer(claimer, p.amountPerClaim);
        }

        emit Claimed(packetId, claimer, p.token, p.amountPerClaim);
    }

    function _validateCreateSignature(
        bytes32 packetId,
        address creator,
        address token,
        uint256 totalAmount,
        uint32 count,
        uint64 expiresAt,
        bytes calldata signature
    ) private view {
        require(claimSigner != address(0), "signer=0");
        require(signature.length != 0, "signature required");
        bytes32 digest = getCreateAuthorizationDigest(packetId, creator, token, totalAmount, count, expiresAt);
        address recovered = ECDSA.recover(_toEthSignedMessageHash(digest), signature);
        require(recovered == claimSigner, "bad signature");
    }

    function _validateClaimSignature(bytes32 packetId, address claimer, bytes calldata signature) private view {
        require(claimSigner != address(0), "signer=0");
        require(signature.length != 0, "signature required");
        bytes32 digest = getClaimAuthorizationDigest(packetId, claimer);
        address recovered = ECDSA.recover(_toEthSignedMessageHash(digest), signature);
        require(recovered == claimSigner, "bad signature");
    }

    function _toEthSignedMessageHash(bytes32 digest) private pure returns (bytes32) {
        return keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", digest));
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
