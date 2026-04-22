package org.telegram.wallet.chain;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Bep20Service {

    public String getBalance(String owner, String contractAddress, int decimals) throws Exception {
        BigInteger raw = getBalanceRaw(owner, contractAddress);
        return new BigDecimal(raw).movePointLeft(Math.max(0, decimals)).stripTrailingZeros().toPlainString();
    }

    public BigInteger getBalanceRaw(String owner, String contractAddress) throws Exception {
        Function function = new Function(
                "balanceOf",
                Arrays.<Type>asList(new Address(owner)),
                Collections.singletonList(new TypeReference<Uint256>() {})
        );
        String data = FunctionEncoder.encode(function);
        EthCall response = BscRpcClient.get().ethCall(
                Transaction.createEthCallTransaction(owner, contractAddress, data),
                DefaultBlockParameterName.LATEST
        ).send();

        List<Type> outputs = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        if (outputs.isEmpty()) {
            return BigInteger.ZERO;
        }
        return (BigInteger) outputs.get(0).getValue();
    }
}
