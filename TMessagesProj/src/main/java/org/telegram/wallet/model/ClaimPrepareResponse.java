package org.telegram.wallet.model;

public class ClaimPrepareResponse {
    public String packetIdHex;
    public String signatureHex;
    public String contractAddress;
    public long chainId;
    public String claimerAddress;
}