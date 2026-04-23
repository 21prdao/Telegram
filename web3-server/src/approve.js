require("dotenv").config();
const { ethers } = require("ethers");

const RPC_URL = process.env.RPC_URL;
const PRIVATE_KEY = process.env.PRIVATE_KEY;
const TOKEN_ADDRESS = process.env.TOKEN_ADDRESS;
const SPENDER_ADDRESS = process.env.SPENDER_ADDRESS;
const AMOUNT = process.env.AMOUNT;

const ERC20_ABI = [
  "function approve(address spender, uint256 value) external returns (bool)",
  "function allowance(address owner, address spender) external view returns (uint256)",
  "function decimals() external view returns (uint8)",
  "function symbol() external view returns (string)",
  "function name() external view returns (string)",
  "function balanceOf(address owner) external view returns (uint256)"
];

function assertEnv() {
  const missing = [];
  if (!RPC_URL) missing.push("RPC_URL");
  if (!PRIVATE_KEY) missing.push("PRIVATE_KEY");
  if (!TOKEN_ADDRESS) missing.push("TOKEN_ADDRESS");
  if (!SPENDER_ADDRESS) missing.push("SPENDER_ADDRESS");
  if (!AMOUNT) missing.push("AMOUNT");

  if (missing.length > 0) {
    throw new Error(`缺少环境变量: ${missing.join(", ")}`);
  }

  if (!ethers.isAddress(TOKEN_ADDRESS)) {
    throw new Error(`TOKEN_ADDRESS 不是有效地址: ${TOKEN_ADDRESS}`);
  }

  if (!ethers.isAddress(SPENDER_ADDRESS)) {
    throw new Error(`SPENDER_ADDRESS 不是有效地址: ${SPENDER_ADDRESS}`);
  }

  if (!String(PRIVATE_KEY).startsWith("0x")) {
    throw new Error("PRIVATE_KEY 必须以 0x 开头");
  }
}

async function main() {
  assertEnv();

  console.log("=== approve start ===");

  const provider = new ethers.JsonRpcProvider(RPC_URL);
  const wallet = new ethers.Wallet(PRIVATE_KEY, provider);
  const ownerAddress = wallet.address;

  console.log("ownerAddress   =", ownerAddress);
  console.log("tokenAddress   =", TOKEN_ADDRESS);
  console.log("spenderAddress =", SPENDER_ADDRESS);

  const token = new ethers.Contract(TOKEN_ADDRESS, ERC20_ABI, wallet);

  const [name, symbol, decimals, balance, allowanceOld, network, feeData, nonce] = await Promise.all([
    token.name().catch(() => "Unknown Token"),
    token.symbol().catch(() => "TOKEN"),
    token.decimals(),
    token.balanceOf(ownerAddress),
    token.allowance(ownerAddress, SPENDER_ADDRESS),
    provider.getNetwork(),
    provider.getFeeData(),
    provider.getTransactionCount(ownerAddress, "pending")
  ]);

  const approveAmount = ethers.parseUnits(String(AMOUNT), decimals);

  console.log("network.chainId =", network.chainId.toString());
  console.log("tokenName        =", name);
  console.log("tokenSymbol      =", symbol);
  console.log("decimals         =", decimals.toString());
  console.log("balance          =", ethers.formatUnits(balance, decimals), symbol);
  console.log("oldAllowance     =", ethers.formatUnits(allowanceOld, decimals), symbol);
  console.log("approveAmount    =", ethers.formatUnits(approveAmount, decimals), symbol);
  console.log("nonce            =", nonce.toString());

  if (balance < approveAmount) {
    console.warn("警告：当前钱包代币余额小于授权数量。");
    console.warn("这通常不影响 approve 本身执行，但后续 transferFrom 时可能失败。");
  }

  if (allowanceOld >= approveAmount) {
    console.log("当前 allowance 已经足够，无需重复授权。");
    console.log("=== approve skipped ===");
    return;
  }

  let tx;

  // 优先 EIP-1559，若节点不支持则退回 gasPrice
  if (feeData.maxFeePerGas && feeData.maxPriorityFeePerGas) {
    tx = await token.approve(
      SPENDER_ADDRESS,
      approveAmount,
      {
        nonce,
        maxFeePerGas: feeData.maxFeePerGas,
        maxPriorityFeePerGas: feeData.maxPriorityFeePerGas
      }
    );
  } else {
    tx = await token.approve(
      SPENDER_ADDRESS,
      approveAmount,
      {
        nonce,
        gasPrice: feeData.gasPrice
      }
    );
  }

  console.log("tx.hash =", tx.hash);
  console.log("等待确认中...");

  const receipt = await tx.wait();

  console.log("已确认");
  console.log("blockNumber =", receipt.blockNumber);
  console.log("status      =", receipt.status);

  const allowanceNew = await token.allowance(ownerAddress, SPENDER_ADDRESS);

  console.log("newAllowance =", ethers.formatUnits(allowanceNew, decimals), symbol);
  console.log("=== approve success ===");
}

main().catch((err) => {
  console.error("=== approve failed ===");
  console.error(err);

  if (err?.shortMessage) {
    console.error("shortMessage:", err.shortMessage);
  }
  if (err?.reason) {
    console.error("reason:", err.reason);
  }
  if (err?.code) {
    console.error("code:", err.code);
  }

  process.exit(1);
});