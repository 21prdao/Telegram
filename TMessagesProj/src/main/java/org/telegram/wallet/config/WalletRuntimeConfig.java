package org.telegram.wallet.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runtime chain configuration for the Web3 wallet.
 *
 * Source of truth:
 *   GET /api/v1/wallet/chain-config
 *
 * The server controls the official RPC node list and red-packet contract. The
 * client keeps user-added custom nodes locally, probes every enabled node on the
 * device, uses the fastest healthy node by default, and allows the user to pin a
 * manually selected node.
 */
public final class WalletRuntimeConfig {
    private static final String PREF_NAME = "wallet_runtime_config";
    private static final String KEY_CHAIN_ID = "chain_id";
    private static final String KEY_CONTRACT = "red_packet_contract";
    private static final String KEY_RPC_URLS = "rpc_urls"; // old cache format, kept for compatibility
    private static final String KEY_RPC_ENDPOINTS = "rpc_endpoints";
    private static final String KEY_CUSTOM_RPC_ENDPOINTS = "custom_rpc_endpoints";
    private static final String KEY_BEST_RPC = "best_rpc_url";
    private static final String KEY_SELECTED_RPC = "selected_rpc_url";
    private static final String KEY_AUTO_SELECT_RPC = "auto_select_rpc";
    private static final String KEY_UPDATED_AT = "updated_at";

    private static final int HTTP_CONNECT_TIMEOUT_MS = 4_000;
    private static final int HTTP_READ_TIMEOUT_MS = 4_000;
    private static final int RPC_PROBE_TIMEOUT_MS = 2_500;
    private static final int RPC_PROBE_TOTAL_TIMEOUT_MS = 7_000;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long RPC_PROBE_CACHE_TTL_MS = 12_000L;
    private static final long RPC_PROBE_STALE_BLOCK_TOLERANCE = 6L;
    private static final long RPC_PROBE_SIMILAR_LATENCY_MS = 200L;

    private static final Object LOCK = new Object();
    private static final Object PROBE_LOCK = new Object();
    private static volatile ChainConfig cachedConfig;
    private static volatile long cachedAtMs;
    private static volatile String cachedProbeSignature;
    private static volatile List<RpcProbeResult> cachedProbeResults;
    private static volatile long cachedProbeAtMs;

    private WalletRuntimeConfig() {}

    public static ChainConfig get() {
        return get(false);
    }

    public static ChainConfig get(boolean forceRefresh) {
        if (forceRefresh) {
            clearProbeCache();
        }
        ChainConfig cached = cachedConfig;
        long now = System.currentTimeMillis();
        if (!forceRefresh && cached != null && now - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }

        synchronized (LOCK) {
            cached = cachedConfig;
            now = System.currentTimeMillis();
            if (!forceRefresh && cached != null && now - cachedAtMs < CACHE_TTL_MS) {
                return cached;
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                ChainConfig local = loadFromPreferences();
                if (local == null) {
                    local = buildFallbackConfig();
                }
                cachedConfig = local;
                // Do not treat main-thread cache as fresh, so a background call can still refresh it.
                cachedAtMs = 0L;
                return local;
            }

            ChainConfig loaded = null;
            try {
                loaded = loadFromServer(forceRefresh);
            } catch (Throwable t) {
                FileLog.e(t);
            }
            if (loaded == null) {
                loaded = loadFromPreferences();
            }
            if (loaded == null) {
                loaded = buildFallbackConfig();
            }

            cachedConfig = loaded;
            cachedAtMs = System.currentTimeMillis();
            return loaded;
        }
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cachedConfig = null;
            cachedAtMs = 0L;
        }
        clearProbeCache();
    }

    public static void clearRpcProbeCache() {
        clearProbeCache();
    }

    public static String getBestRpcUrl() {
        return get().bestRpcUrl;
    }

    public static long getChainId() {
        return get().chainId;
    }

    public static String getRedPacketContract() {
        return get().redPacketContract;
    }

    public static boolean isAutoSelectRpcEnabled() {
        SharedPreferences preferences = prefs();
        return preferences == null || preferences.getBoolean(KEY_AUTO_SELECT_RPC, true);
    }

    public static String getManuallySelectedRpcUrl() {
        SharedPreferences preferences = prefs();
        return preferences == null ? "" : normalizeRpcUrl(preferences.getString(KEY_SELECTED_RPC, ""));
    }

    public static List<RpcEndpoint> getRpcEndpoints(boolean forceRefresh) {
        ChainConfig config = get(forceRefresh);
        return config.rpcEndpoints;
    }

    public static void selectRpcUrl(String rpcUrl) {
        String normalized = normalizeRpcUrl(rpcUrl);
        if (TextUtils.isEmpty(normalized)) {
            throw new IllegalArgumentException("RPC URL 无效");
        }
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit()
                    .putString(KEY_SELECTED_RPC, normalized)
                    .putBoolean(KEY_AUTO_SELECT_RPC, false)
                    .putString(KEY_BEST_RPC, normalized)
                    .apply();
        }
        synchronized (LOCK) {
            ChainConfig current = cachedConfig != null ? cachedConfig : loadFromPreferences();
            if (current != null) {
                cachedConfig = current.withBestRpc(normalized, false, normalized);
                cachedAtMs = System.currentTimeMillis();
            } else {
                cachedConfig = null;
                cachedAtMs = 0L;
            }
        }
    }

    public static void useAutoSelectBestRpc() {
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit()
                    .remove(KEY_SELECTED_RPC)
                    .remove(KEY_BEST_RPC)
                    .putBoolean(KEY_AUTO_SELECT_RPC, true)
                    .apply();
        }
        invalidate();
    }

    public static void rememberAutoBestRpcUrl(String rpcUrl) {
        String normalized = normalizeRpcUrl(rpcUrl);
        if (TextUtils.isEmpty(normalized) || !isAutoSelectRpcEnabled()) {
            return;
        }
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit().putString(KEY_BEST_RPC, normalized).apply();
        }
        synchronized (LOCK) {
            ChainConfig current = cachedConfig != null ? cachedConfig : loadFromPreferences();
            if (current != null && current.autoSelectRpc) {
                cachedConfig = current.withBestRpc(normalized, true, "");
                cachedAtMs = System.currentTimeMillis();
            }
        }
    }

    public static void addCustomRpcEndpoint(String name, String rpcUrl) {
        String normalized = normalizeRpcUrl(rpcUrl);
        if (TextUtils.isEmpty(normalized)) {
            throw new IllegalArgumentException("请输入有效的 http/https RPC URL");
        }
        List<RpcEndpoint> custom = loadCustomEndpoints();
        ArrayList<RpcEndpoint> next = new ArrayList<>();
        boolean replaced = false;
        String cleanName = cleanNodeName(name, custom.size());
        for (RpcEndpoint endpoint : custom) {
            if (endpoint.url.equalsIgnoreCase(normalized)) {
                next.add(new RpcEndpoint(cleanName, normalized, true, "custom", true));
                replaced = true;
            } else {
                next.add(endpoint);
            }
        }
        if (!replaced) {
            next.add(new RpcEndpoint(cleanName, normalized, true, "custom", true));
        }
        saveCustomEndpoints(next);
        invalidate();
    }

    public static boolean removeCustomRpcEndpoint(String rpcUrl) {
        String normalized = normalizeRpcUrl(rpcUrl);
        if (TextUtils.isEmpty(normalized)) return false;
        List<RpcEndpoint> custom = loadCustomEndpoints();
        ArrayList<RpcEndpoint> next = new ArrayList<>();
        boolean removed = false;
        for (RpcEndpoint endpoint : custom) {
            if (endpoint.url.equalsIgnoreCase(normalized)) {
                removed = true;
            } else {
                next.add(endpoint);
            }
        }
        if (!removed) return false;
        saveCustomEndpoints(next);
        SharedPreferences preferences = prefs();
        if (preferences != null && normalized.equalsIgnoreCase(normalizeRpcUrl(preferences.getString(KEY_SELECTED_RPC, "")))) {
            preferences.edit().remove(KEY_SELECTED_RPC).putBoolean(KEY_AUTO_SELECT_RPC, true).apply();
        }
        invalidate();
        return true;
    }

    public static boolean isCustomRpcUrl(String rpcUrl) {
        String normalized = normalizeRpcUrl(rpcUrl);
        if (TextUtils.isEmpty(normalized)) return false;
        for (RpcEndpoint endpoint : loadCustomEndpoints()) {
            if (endpoint.url.equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

    public static List<RpcProbeResult> probeRpcEndpoints(List<RpcEndpoint> endpoints, long expectedChainId) {
        return probeRpcEndpoints(endpoints, expectedChainId, false);
    }

    public static List<RpcProbeResult> probeRpcEndpoints(List<RpcEndpoint> endpoints, long expectedChainId, boolean forceProbe) {
        final ArrayList<RpcEndpoint> nodes = dedupeEndpoints(endpoints);
        if (nodes.isEmpty()) return Collections.emptyList();

        String signature = probeSignature(nodes, expectedChainId);
        long now = System.currentTimeMillis();
        if (!forceProbe) {
            synchronized (PROBE_LOCK) {
                if (!TextUtils.isEmpty(signature)
                        && signature.equals(cachedProbeSignature)
                        && cachedProbeResults != null
                        && now - cachedProbeAtMs < RPC_PROBE_CACHE_TTL_MS) {
                    return new ArrayList<>(cachedProbeResults);
                }
            }
        }

        int poolSize = Math.max(1, Math.min(nodes.size(), 8));
        int waves = Math.max(1, (nodes.size() + poolSize - 1) / poolSize);
        long totalTimeoutMs = Math.max(RPC_PROBE_TOTAL_TIMEOUT_MS, waves * (RPC_PROBE_TIMEOUT_MS * 2L + 500L));
        totalTimeoutMs = Math.min(totalTimeoutMs, 12_000L);

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        ArrayList<Callable<RpcProbeResult>> tasks = new ArrayList<>();
        for (RpcEndpoint endpoint : nodes) {
            tasks.add(() -> probeRpcEndpoint(endpoint, expectedChainId));
        }

        ArrayList<RpcProbeResult> probes = new ArrayList<>();
        try {
            List<Future<RpcProbeResult>> futures = executor.invokeAll(tasks, totalTimeoutMs, TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<RpcProbeResult> future = futures.get(i);
                try {
                    if (future.isDone() && !future.isCancelled()) {
                        probes.add(future.get());
                    } else {
                        RpcEndpoint endpoint = nodes.get(i);
                        probes.add(new RpcProbeResult(endpoint, false, Long.MAX_VALUE, 0L, 0L, "timeout"));
                    }
                } catch (Throwable t) {
                    RpcEndpoint endpoint = nodes.get(i);
                    probes.add(new RpcProbeResult(endpoint, false, Long.MAX_VALUE, 0L, 0L, String.valueOf(t.getMessage())));
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            executor.shutdownNow();
        }

        sortProbeResults(probes);
        synchronized (PROBE_LOCK) {
            cachedProbeSignature = signature;
            cachedProbeResults = new ArrayList<>(probes);
            cachedProbeAtMs = System.currentTimeMillis();
        }
        return probes;
    }

    public static RpcProbeResult selectBestProbe(List<RpcProbeResult> probes) {
        if (probes == null) return null;
        long latestBlock = latestBlockNumber(probes);
        RpcProbeResult best = null;
        for (RpcProbeResult probe : probes) {
            if (probe == null || !probe.ok || !isProbeFresh(probe, latestBlock)) continue;
            if (best == null || compareProbeQuality(probe, best, latestBlock) < 0) {
                best = probe;
            }
        }
        return best;
    }

    private static void sortProbeResults(ArrayList<RpcProbeResult> probes) {
        long latestBlock = latestBlockNumber(probes);
        Collections.sort(probes, (a, b) -> compareProbeQuality(a, b, latestBlock));
    }

    private static int compareProbeQuality(RpcProbeResult a, RpcProbeResult b, long latestBlock) {
        if (a == b) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        if (a.ok != b.ok) return a.ok ? -1 : 1;
        if (!a.ok) {
            int latencyCompare = Long.compare(a.latencyMs, b.latencyMs);
            if (latencyCompare != 0) return latencyCompare;
            return String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name));
        }

        boolean aFresh = isProbeFresh(a, latestBlock);
        boolean bFresh = isProbeFresh(b, latestBlock);
        if (aFresh != bFresh) return aFresh ? -1 : 1;
        if (!aFresh) {
            int blockCompare = Long.compare(b.blockNumber, a.blockNumber);
            if (blockCompare != 0) return blockCompare;
            int latencyCompare = Long.compare(a.latencyMs, b.latencyMs);
            if (latencyCompare != 0) return latencyCompare;
            return String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name));
        }

        long aBucket = latencyBucket(a.latencyMs);
        long bBucket = latencyBucket(b.latencyMs);
        if (aBucket != bBucket) return Long.compare(aBucket, bBucket);

        int blockCompare = Long.compare(b.blockNumber, a.blockNumber);
        if (blockCompare != 0) return blockCompare;
        int latencyCompare = Long.compare(a.latencyMs, b.latencyMs);
        if (latencyCompare != 0) return latencyCompare;
        return String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name));
    }

    private static long latencyBucket(long latencyMs) {
        if (latencyMs <= 0) return 0L;
        if (latencyMs >= Long.MAX_VALUE / 2) return Long.MAX_VALUE / RPC_PROBE_SIMILAR_LATENCY_MS;
        return latencyMs / RPC_PROBE_SIMILAR_LATENCY_MS;
    }

    private static boolean isProbeFresh(RpcProbeResult probe, long latestBlock) {
        if (probe == null || !probe.ok) return false;
        return latestBlock <= 0 || probe.blockNumber + RPC_PROBE_STALE_BLOCK_TOLERANCE >= latestBlock;
    }

    private static long latestBlockNumber(List<RpcProbeResult> probes) {
        long latestBlock = 0L;
        if (probes == null) return latestBlock;
        for (RpcProbeResult probe : probes) {
            if (probe != null && probe.ok && probe.blockNumber > latestBlock) {
                latestBlock = probe.blockNumber;
            }
        }
        return latestBlock;
    }

    private static String probeSignature(List<RpcEndpoint> nodes, long expectedChainId) {
        StringBuilder sb = new StringBuilder();
        sb.append(expectedChainId);
        if (nodes != null) {
            for (RpcEndpoint endpoint : nodes) {
                if (endpoint == null) continue;
                sb.append('|').append(normalizeRpcUrl(endpoint.url).toLowerCase(Locale.US));
            }
        }
        return sb.toString();
    }

    private static void clearProbeCache() {
        synchronized (PROBE_LOCK) {
            cachedProbeSignature = null;
            cachedProbeResults = null;
            cachedProbeAtMs = 0L;
        }
    }

    private static ChainConfig loadFromServer(boolean forceProbe) throws Exception {
        String apiBase = WalletConfig.getRedPacketApiBaseUrl();
        JSONObject root = requestJson(apiBase + "/wallet/chain-config");
        JSONObject data = unwrapData(root);

        long chainId = optLong(data, "chainId", "chain_id");
        if (chainId <= 0) {
            chainId = WalletConfig.BSC_CHAIN_ID;
        }

        String contract = firstNonEmpty(
                optString(data, "redPacketContract", "contractAddress", "contract_address"),
                WalletConfig.getBuildRedPacketContract()
        );
        contract = normalizeAddress(contract);

        ArrayList<RpcEndpoint> serverEndpoints = new ArrayList<>();
        appendRpcEndpoints(serverEndpoints, data.optJSONArray("rpcEndpoints"), "server", false);
        appendRpcUrlsAsEndpoints(serverEndpoints, data.optJSONArray("rpcUrls"), "server", false);
        addRpcEndpoint(serverEndpoints, new RpcEndpoint("推荐节点", optString(data, "bestRpcUrl", "rpcUrl"), true, "server", false));
        if (serverEndpoints.isEmpty()) {
            serverEndpoints.addAll(buildFallbackEndpoints());
        }

        ArrayList<RpcEndpoint> allEndpoints = mergeEndpoints(serverEndpoints, loadCustomEndpoints());
        RpcSelection selection = resolveRpcSelection(allEndpoints, chainId, optString(data, "bestRpcUrl", "rpcUrl"), forceProbe);
        ChainConfig config = new ChainConfig(chainId, contract, serverEndpoints, allEndpoints, selection.bestRpcUrl, selection.autoSelect, selection.selectedRpcUrl, System.currentTimeMillis());
        saveToPreferences(config);
        return config;
    }

    private static ChainConfig buildFallbackConfig() {
        ArrayList<RpcEndpoint> serverEndpoints = buildFallbackEndpoints();
        ArrayList<RpcEndpoint> allEndpoints = mergeEndpoints(serverEndpoints, loadCustomEndpoints());
        SharedPreferences preferences = prefs();
        boolean auto = preferences == null || preferences.getBoolean(KEY_AUTO_SELECT_RPC, true);
        String manual = preferences == null ? "" : normalizeRpcUrl(preferences.getString(KEY_SELECTED_RPC, ""));
        String savedBest = preferences == null ? "" : normalizeRpcUrl(preferences.getString(KEY_BEST_RPC, ""));
        String bestRpcUrl;
        if (!auto && containsRpcUrl(allEndpoints, manual)) {
            bestRpcUrl = manual;
        } else if (!TextUtils.isEmpty(savedBest) && containsRpcUrl(allEndpoints, savedBest)) {
            bestRpcUrl = savedBest;
        } else {
            bestRpcUrl = allEndpoints.isEmpty() ? getFallbackRpcUrl() : allEndpoints.get(0).url;
        }
        return new ChainConfig(
                WalletConfig.BSC_CHAIN_ID,
                getFallbackContract(),
                serverEndpoints,
                allEndpoints,
                bestRpcUrl,
                auto,
                auto ? "" : manual,
                System.currentTimeMillis()
        );
    }

    private static RpcSelection resolveRpcSelection(List<RpcEndpoint> endpoints, long chainId, String serverBestRpcUrl, boolean forceProbe) {
        SharedPreferences preferences = prefs();
        boolean auto = preferences == null || preferences.getBoolean(KEY_AUTO_SELECT_RPC, true);
        String manual = preferences == null ? "" : normalizeRpcUrl(preferences.getString(KEY_SELECTED_RPC, ""));
        String best;

        if (!auto && containsRpcUrl(endpoints, manual)) {
            best = manual;
        } else {
            auto = true;
            best = chooseBestRpcUrl(endpoints, chainId, forceProbe);
            String serverBest = normalizeRpcUrl(serverBestRpcUrl);
            if (TextUtils.isEmpty(best) && !TextUtils.isEmpty(serverBest) && containsRpcUrl(endpoints, serverBest)) {
                best = serverBest;
            }
            if (TextUtils.isEmpty(best)) {
                best = endpoints.isEmpty() ? getFallbackRpcUrl() : endpoints.get(0).url;
            }
            manual = "";
        }
        return new RpcSelection(best, auto, manual);
    }

    private static String chooseBestRpcUrl(List<RpcEndpoint> endpoints, long expectedChainId, boolean forceProbe) {
        List<RpcProbeResult> probes = probeRpcEndpoints(endpoints, expectedChainId, forceProbe);
        RpcProbeResult best = selectBestProbe(probes);
        if (best != null) return best.url;
        ArrayList<RpcEndpoint> urls = dedupeEndpoints(endpoints);
        return urls.isEmpty() ? "" : urls.get(0).url;
    }

    private static RpcProbeResult probeRpcEndpoint(RpcEndpoint endpoint, long expectedChainId) {
        long startNanos = System.nanoTime();
        if (endpoint == null || TextUtils.isEmpty(endpoint.url)) {
            return new RpcProbeResult(endpoint, false, Long.MAX_VALUE, 0L, 0L, "empty url");
        }
        if (!endpoint.enabled) {
            return new RpcProbeResult(endpoint, false, Long.MAX_VALUE, 0L, 0L, "disabled");
        }
        try {
            String chainIdRaw = callJsonRpc(endpoint.url, "eth_chainId");
            long actualChainId = parseRpcQuantity(chainIdRaw);
            if (expectedChainId > 0) {
                if (actualChainId <= 0) {
                    throw new IllegalStateException("chainId unavailable");
                }
                if (actualChainId != expectedChainId) {
                    throw new IllegalStateException("chainId mismatch: " + actualChainId);
                }
            }
            String blockRaw = callJsonRpc(endpoint.url, "eth_blockNumber");
            long blockNumber = parseRpcQuantity(blockRaw);
            long latency = elapsedMs(startNanos);
            boolean ok = blockNumber > 0;
            return new RpcProbeResult(endpoint, ok, latency, blockNumber, actualChainId, ok ? "" : "empty blockNumber");
        } catch (Throwable t) {
            return new RpcProbeResult(endpoint, false, elapsedMs(startNanos), 0L, 0L, t.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }

    private static String callJsonRpc(String rpcUrl, String method) throws Exception {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            URL url = new URL(rpcUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(RPC_PROBE_TIMEOUT_MS);
            connection.setReadTimeout(RPC_PROBE_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "TelegramWallet/1.0");
            connection.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            body.put("method", method);
            body.put("params", new JSONArray());
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = connection.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = connection.getResponseCode();
            stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = readFully(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + text);
            }
            JSONObject root = new JSONObject(text);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                throw new IllegalStateException(error.optString("message", "rpc error"));
            }
            return root.optString("result", "");
        } finally {
            try {
                if (stream != null) stream.close();
            } catch (Throwable ignore) {
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject requestJson(String urlText) throws Exception {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "TelegramWallet/1.0");

            int code = connection.getResponseCode();
            stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = readFully(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + text);
            }
            return TextUtils.isEmpty(text) ? new JSONObject() : new JSONObject(text);
        } finally {
            try {
                if (stream != null) stream.close();
            } catch (Throwable ignore) {
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject unwrapData(JSONObject root) {
        if (root == null) return new JSONObject();
        JSONObject data = root.optJSONObject("data");
        if (data != null) return data;
        JSONObject result = root.optJSONObject("result");
        if (result != null) return result;
        return root;
    }

    private static void appendRpcUrlsAsEndpoints(List<RpcEndpoint> out, JSONArray array, String source, boolean custom) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                appendRpcEndpoints(out, new JSONArray().put(value), source, custom);
            } else {
                addRpcEndpoint(out, new RpcEndpoint(defaultNodeName(source, i), String.valueOf(value), true, source, custom));
            }
        }
    }

    private static void appendRpcEndpoints(List<RpcEndpoint> out, JSONArray array, String source, boolean custom) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                JSONObject item = (JSONObject) value;
                String name = optString(item, "name", "title", "label");
                String url = optString(item, "url", "rpcUrl", "endpoint");
                boolean enabled = !item.has("enabled") || item.optBoolean("enabled", true);
                String itemSource = firstNonEmpty(optString(item, "source"), source);
                boolean itemCustom = custom || "custom".equalsIgnoreCase(itemSource) || item.optBoolean("custom", false);
                addRpcEndpoint(out, new RpcEndpoint(TextUtils.isEmpty(name) ? defaultNodeName(itemSource, i) : name, url, enabled, itemSource, itemCustom));
            } else {
                addRpcEndpoint(out, new RpcEndpoint(defaultNodeName(source, i), String.valueOf(value), true, source, custom));
            }
        }
    }

    private static void addRpcEndpoint(List<RpcEndpoint> out, RpcEndpoint endpoint) {
        if (out == null || endpoint == null || TextUtils.isEmpty(endpoint.url)) return;
        String normalized = normalizeRpcUrl(endpoint.url);
        if (TextUtils.isEmpty(normalized)) return;
        for (RpcEndpoint existing : out) {
            if (normalized.equalsIgnoreCase(existing.url)) return;
        }
        out.add(new RpcEndpoint(endpoint.name, normalized, endpoint.enabled, endpoint.source, endpoint.custom));
    }

    private static ArrayList<RpcEndpoint> mergeEndpoints(List<RpcEndpoint> server, List<RpcEndpoint> custom) {
        ArrayList<RpcEndpoint> result = new ArrayList<>();
        if (server != null) {
            for (RpcEndpoint endpoint : server) addRpcEndpoint(result, endpoint);
        }
        if (custom != null) {
            for (RpcEndpoint endpoint : custom) addRpcEndpoint(result, endpoint);
        }
        return result;
    }

    private static ArrayList<RpcEndpoint> dedupeEndpoints(List<RpcEndpoint> input) {
        ArrayList<RpcEndpoint> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (input == null) return result;
        for (int i = 0; i < input.size(); i++) {
            RpcEndpoint endpoint = input.get(i);
            if (endpoint == null || !endpoint.enabled) continue;
            String url = normalizeRpcUrl(endpoint.url);
            if (TextUtils.isEmpty(url)) continue;
            String key = url.toLowerCase(Locale.US);
            if (seen.add(key)) {
                String name = TextUtils.isEmpty(endpoint.name) ? defaultNodeName(endpoint.source, i) : endpoint.name;
                result.add(new RpcEndpoint(name, url, true, endpoint.source, endpoint.custom));
            }
        }
        return result;
    }

    private static boolean containsRpcUrl(List<RpcEndpoint> endpoints, String rpcUrl) {
        String normalized = normalizeRpcUrl(rpcUrl);
        if (TextUtils.isEmpty(normalized) || endpoints == null) return false;
        for (RpcEndpoint endpoint : endpoints) {
            if (endpoint != null && endpoint.enabled && normalized.equalsIgnoreCase(endpoint.url)) return true;
        }
        return false;
    }

    private static ArrayList<RpcEndpoint> buildFallbackEndpoints() {
        ArrayList<RpcEndpoint> result = new ArrayList<>();
        List<String> urls = WalletConfig.getBuildRpcUrls();
        for (int i = 0; i < urls.size(); i++) {
            addRpcEndpoint(result, new RpcEndpoint(defaultNodeName("server", i), urls.get(i), true, "server", false));
        }
        if (result.isEmpty()) {
            result.add(new RpcEndpoint("BSC-Binance1", "https://data-seed-prebsc-1-s1.bnbchain.org:8545", true, "server", false));
        }
        return result;
    }

    private static ArrayList<String> rpcUrlsFromEndpoints(List<RpcEndpoint> endpoints) {
        ArrayList<String> result = new ArrayList<>();
        for (RpcEndpoint endpoint : dedupeEndpoints(endpoints)) {
            result.add(endpoint.url);
        }
        return result;
    }

    private static String normalizeRpcUrl(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String s = value.trim();
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return "";
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String getFallbackRpcUrl() {
        List<String> urls = WalletConfig.getBuildRpcUrls();
        return urls.isEmpty() ? "https://data-seed-prebsc-1-s1.bnbchain.org:8545" : urls.get(0);
    }

    private static String getFallbackContract() {
        return normalizeAddress(WalletConfig.getBuildRedPacketContract());
    }

    private static String normalizeAddress(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String s = value.trim();
        if (!s.matches("(?i)^0x[0-9a-f]{40}$")) return "";
        return s;
    }

    private static long parseRpcQuantity(String value) {
        if (TextUtils.isEmpty(value)) return 0L;
        String s = value.trim().toLowerCase(Locale.US);
        try {
            if (s.startsWith("0x")) {
                return Long.parseLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    private static String readFully(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static String optString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            String value = String.valueOf(obj.opt(key)).trim();
            if (!TextUtils.isEmpty(value) && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static long optLong(JSONObject obj, String... keys) {
        String value = optString(obj, keys);
        if (TextUtils.isEmpty(value)) return 0L;
        try {
            return value.startsWith("0x") || value.startsWith("0X")
                    ? Long.parseLong(value.substring(2), 16)
                    : Long.parseLong(value);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private static String defaultNodeName(String source, int index) {
        if ("custom".equalsIgnoreCase(source)) {
            return "自定义-" + (index + 1);
        }
        return "BSC-Binance" + (index + 1);
    }

    private static String cleanNodeName(String value, int index) {
        String name = value == null ? "" : value.trim();
        if (TextUtils.isEmpty(name)) name = defaultNodeName("custom", index);
        if (name.length() > 32) name = name.substring(0, 32);
        return name;
    }

    private static SharedPreferences prefs() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return null;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static JSONArray endpointsToJson(List<RpcEndpoint> endpoints) throws Exception {
        JSONArray array = new JSONArray();
        if (endpoints == null) return array;
        for (RpcEndpoint endpoint : endpoints) {
            if (endpoint == null || TextUtils.isEmpty(endpoint.url)) continue;
            JSONObject item = new JSONObject();
            item.put("name", endpoint.name);
            item.put("url", endpoint.url);
            item.put("enabled", endpoint.enabled);
            item.put("source", endpoint.source);
            item.put("custom", endpoint.custom);
            array.put(item);
        }
        return array;
    }

    private static void saveToPreferences(ChainConfig config) {
        try {
            SharedPreferences preferences = prefs();
            if (preferences == null || config == null) return;
            JSONArray urls = new JSONArray();
            for (String url : config.rpcUrls) {
                urls.put(url);
            }
            preferences.edit()
                    .putLong(KEY_CHAIN_ID, config.chainId)
                    .putString(KEY_CONTRACT, config.redPacketContract)
                    .putString(KEY_BEST_RPC, config.bestRpcUrl)
                    .putString(KEY_RPC_URLS, urls.toString())
                    .putString(KEY_RPC_ENDPOINTS, endpointsToJson(config.serverRpcEndpoints).toString())
                    .putBoolean(KEY_AUTO_SELECT_RPC, config.autoSelectRpc)
                    .putString(KEY_SELECTED_RPC, config.selectedRpcUrl == null ? "" : config.selectedRpcUrl)
                    .putLong(KEY_UPDATED_AT, config.updatedAtMs)
                    .apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void saveCustomEndpoints(List<RpcEndpoint> endpoints) {
        try {
            SharedPreferences preferences = prefs();
            if (preferences == null) return;
            preferences.edit().putString(KEY_CUSTOM_RPC_ENDPOINTS, endpointsToJson(endpoints).toString()).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static ArrayList<RpcEndpoint> loadCustomEndpoints() {
        ArrayList<RpcEndpoint> result = new ArrayList<>();
        try {
            SharedPreferences preferences = prefs();
            if (preferences == null) return result;
            String raw = preferences.getString(KEY_CUSTOM_RPC_ENDPOINTS, "");
            if (!TextUtils.isEmpty(raw)) {
                appendRpcEndpoints(result, new JSONArray(raw), "custom", true);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return result;
    }

    private static ChainConfig loadFromPreferences() {
        try {
            SharedPreferences preferences = prefs();
            if (preferences == null) return null;
            long chainId = preferences.getLong(KEY_CHAIN_ID, WalletConfig.BSC_CHAIN_ID);
            String contract = normalizeAddress(preferences.getString(KEY_CONTRACT, WalletConfig.getBuildRedPacketContract()));
            String bestRpc = normalizeRpcUrl(preferences.getString(KEY_BEST_RPC, getFallbackRpcUrl()));
            boolean auto = preferences.getBoolean(KEY_AUTO_SELECT_RPC, true);
            String manual = normalizeRpcUrl(preferences.getString(KEY_SELECTED_RPC, ""));

            ArrayList<RpcEndpoint> serverEndpoints = new ArrayList<>();
            String rawEndpoints = preferences.getString(KEY_RPC_ENDPOINTS, "");
            if (!TextUtils.isEmpty(rawEndpoints)) {
                appendRpcEndpoints(serverEndpoints, new JSONArray(rawEndpoints), "server", false);
            }
            if (serverEndpoints.isEmpty()) {
                String rawUrls = preferences.getString(KEY_RPC_URLS, "");
                if (!TextUtils.isEmpty(rawUrls)) {
                    appendRpcUrlsAsEndpoints(serverEndpoints, new JSONArray(rawUrls), "server", false);
                }
            }
            if (serverEndpoints.isEmpty()) serverEndpoints.addAll(buildFallbackEndpoints());

            ArrayList<RpcEndpoint> allEndpoints = mergeEndpoints(serverEndpoints, loadCustomEndpoints());
            if (!auto && containsRpcUrl(allEndpoints, manual)) {
                bestRpc = manual;
            } else {
                auto = true;
                manual = "";
                if (TextUtils.isEmpty(bestRpc) || !containsRpcUrl(allEndpoints, bestRpc)) {
                    bestRpc = allEndpoints.isEmpty() ? getFallbackRpcUrl() : allEndpoints.get(0).url;
                }
            }
            if (TextUtils.isEmpty(contract) || TextUtils.isEmpty(bestRpc)) return null;
            return new ChainConfig(chainId, contract, serverEndpoints, allEndpoints, bestRpc, auto, manual, preferences.getLong(KEY_UPDATED_AT, System.currentTimeMillis()));
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static final class RpcSelection {
        final String bestRpcUrl;
        final boolean autoSelect;
        final String selectedRpcUrl;

        RpcSelection(String bestRpcUrl, boolean autoSelect, String selectedRpcUrl) {
            this.bestRpcUrl = bestRpcUrl;
            this.autoSelect = autoSelect;
            this.selectedRpcUrl = selectedRpcUrl;
        }
    }

    public static final class RpcEndpoint {
        public final String name;
        public final String url;
        public final boolean enabled;
        public final String source;
        public final boolean custom;

        public RpcEndpoint(String name, String url, boolean enabled, String source, boolean custom) {
            this.name = TextUtils.isEmpty(name) ? defaultNodeName(source, 0) : name.trim();
            this.url = normalizeRpcUrl(url);
            this.enabled = enabled;
            this.source = TextUtils.isEmpty(source) ? "server" : source;
            this.custom = custom || "custom".equalsIgnoreCase(this.source);
        }
    }

    public static final class RpcProbeResult {
        public final String name;
        public final String url;
        public final boolean enabled;
        public final String source;
        public final boolean custom;
        public final boolean ok;
        public final long latencyMs;
        public final long blockNumber;
        public final long chainId;
        public final String error;

        private RpcProbeResult(RpcEndpoint endpoint, boolean ok, long latencyMs, long blockNumber, long chainId, String error) {
            this.name = endpoint == null ? "" : endpoint.name;
            this.url = endpoint == null ? "" : endpoint.url;
            this.enabled = endpoint == null || endpoint.enabled;
            this.source = endpoint == null ? "" : endpoint.source;
            this.custom = endpoint != null && endpoint.custom;
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.blockNumber = blockNumber;
            this.chainId = chainId;
            this.error = error == null ? "" : error;
        }

        public int speedLevel() {
            if (!ok) return 3;
            if (latencyMs <= 300) return 1;
            if (latencyMs <= 900) return 2;
            return 3;
        }
    }

    public static final class ChainConfig {
        public final long chainId;
        public final String redPacketContract;
        public final List<String> rpcUrls;
        public final List<RpcEndpoint> serverRpcEndpoints;
        public final List<RpcEndpoint> rpcEndpoints;
        public final String bestRpcUrl;
        public final boolean autoSelectRpc;
        public final String selectedRpcUrl;
        public final long updatedAtMs;

        private ChainConfig(long chainId, String redPacketContract, List<RpcEndpoint> serverRpcEndpoints, List<RpcEndpoint> rpcEndpoints, String bestRpcUrl, boolean autoSelectRpc, String selectedRpcUrl, long updatedAtMs) {
            this.chainId = chainId > 0 ? chainId : WalletConfig.BSC_CHAIN_ID;
            this.redPacketContract = TextUtils.isEmpty(redPacketContract) ? getFallbackContract() : redPacketContract;
            this.serverRpcEndpoints = Collections.unmodifiableList(new ArrayList<>(dedupeEndpoints(serverRpcEndpoints)));
            this.rpcEndpoints = Collections.unmodifiableList(new ArrayList<>(dedupeEndpoints(rpcEndpoints)));
            this.rpcUrls = Collections.unmodifiableList(rpcUrlsFromEndpoints(this.rpcEndpoints));
            String normalizedBest = normalizeRpcUrl(bestRpcUrl);
            this.bestRpcUrl = TextUtils.isEmpty(normalizedBest)
                    ? (this.rpcUrls.isEmpty() ? getFallbackRpcUrl() : this.rpcUrls.get(0))
                    : normalizedBest;
            this.autoSelectRpc = autoSelectRpc;
            this.selectedRpcUrl = normalizeRpcUrl(selectedRpcUrl);
            this.updatedAtMs = updatedAtMs;
        }

        private ChainConfig withBestRpc(String bestRpcUrl, boolean autoSelect, String selectedRpcUrl) {
            return new ChainConfig(chainId, redPacketContract, serverRpcEndpoints, rpcEndpoints, bestRpcUrl, autoSelect, selectedRpcUrl, System.currentTimeMillis());
        }
    }
}
