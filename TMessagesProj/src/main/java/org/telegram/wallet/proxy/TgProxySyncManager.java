package org.telegram.wallet.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.wallet.config.WalletConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Syncs the Telegram proxy from the ETZone/Web3 backend.
 *
 * Source of truth:
 *   GET {WalletConfig.getRedPacketApiBaseUrl()}/client/proxy
 *
 * Expected payload:
 *   {"ok":true,"data":{"address":"...","port":443,"username":"","password":"","secret":"..."}}
 *
 * Rules:
 *   - secret != empty  -> MTProto proxy
 *   - secret == empty  -> SOCKS5 proxy, username/password optional
 *
 * Call TgProxySyncManager.ensureStarted(context, true) once when the ETZone/Telegram app starts.
 */
public final class TgProxySyncManager {
    private static final String TAG = "TgProxySync";
    private static final String PREF_NAME = "etzone_tg_proxy_sync";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_LAST_APPLIED_SIGNATURE = "last_applied_signature";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_PORT = "port";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_ENABLED = "enabled";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final long MIN_BACKGROUND_SYNC_INTERVAL_MS = 60_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "etzone-tg-proxy-sync");
            thread.setDaemon(true);
            return thread;
        }
    });

    private TgProxySyncManager() {}

    public static void ensureStarted(Context context) {
        ensureStarted(context, false);
    }

    /**
     * @param forceRefresh true when called at app start; false when called from fallback pages.
     */
    public static void ensureStarted(Context context, boolean forceRefresh) {
        Context appContext = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
        if (appContext == null) {
            return;
        }

        ProxyConfig cached = loadCached(appContext);
        if (cached != null && cached.isValid()) {
            applyOnUiThread(appContext, cached, false);
        }

        SharedPreferences prefs = prefs(appContext);
        long now = System.currentTimeMillis();
        long lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L);
        if (!forceRefresh && now - lastSync < MIN_BACKGROUND_SYNC_INTERVAL_MS) {
            return;
        }
        syncInBackground(appContext);
    }

    public static void syncNow(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
        if (appContext == null) {
            return;
        }
        syncInBackground(appContext);
    }

    private static void syncInBackground(final Context appContext) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ProxyConfig remote = fetchFromServer();
                    prefs(appContext).edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply();
                    if (remote != null && remote.isValid()) {
                        saveCached(appContext, remote);
                        applyOnUiThread(appContext, remote, true);
                    } else if (remote != null && !remote.enabled) {
                        // Backend explicitly disabled proxy.
                        clearCached(appContext);
                        disableTelegramProxyOnUiThread(appContext);
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    RUNNING.set(false);
                }
            }
        });
    }

    private static ProxyConfig fetchFromServer() throws Exception {
        String endpoint = WalletConfig.getRedPacketApiBaseUrl() + "/client/proxy";
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readFully(stream);
            if (code < 200 || code >= 300 || TextUtils.isEmpty(body)) {
                throw new IllegalStateException("proxy endpoint http " + code);
            }
            JSONObject root = new JSONObject(body);
            if (!root.optBoolean("ok", false)) {
                throw new IllegalStateException(root.optString("message", "proxy endpoint failed"));
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                throw new IllegalStateException("proxy endpoint missing data");
            }
            ProxyConfig config = new ProxyConfig();
            config.enabled = !data.has("enabled") || data.optBoolean("enabled", true);
            String rawAddress = firstNonEmpty(data,
                    "address", "server", "host", "proxyAddress", "proxyHost");
            config.address = rawAddress;
            config.port = firstPositiveInt(data,
                    "port", "proxyPort");
            if (config.port <= 0) {
                config.port = extractPort(rawAddress);
            }
            config.username = firstNonEmpty(data,
                    "username", "user", "proxyUsername", "proxyUser");
            config.password = firstNonEmpty(data,
                    "password", "pass", "proxyPassword", "proxyPass");
            config.secret = firstNonEmpty(data,
                    "secret", "proxySecret", "mtprotoSecret");
            config.normalize();
            return config;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readFully(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private static String firstNonEmpty(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private static int extractPort(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        String result = value.trim();
        int slash = result.indexOf('/');
        if (slash >= 0) {
            result = result.substring(0, slash);
        }
        int colon = result.lastIndexOf(':');
        if (colon <= 0 || colon >= result.length() - 1 || result.indexOf(']') >= colon) {
            return 0;
        }
        try {
            int port = Integer.parseInt(result.substring(colon + 1));
            return port > 0 && port <= 65535 ? port : 0;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static int firstPositiveInt(JSONObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key)) {
                continue;
            }
            int value = object.optInt(key, 0);
            if (value > 0 && value <= 65535) {
                return value;
            }
            String raw = object.optString(key, "");
            if (!TextUtils.isEmpty(raw)) {
                try {
                    value = Integer.parseInt(raw.trim());
                    if (value > 0 && value <= 65535) {
                        return value;
                    }
                } catch (Throwable ignore) {
                    // Ignore and try next key.
                }
            }
        }
        return 0;
    }

    private static void applyOnUiThread(final Context context, final ProxyConfig config, final boolean fromServer) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                applyTelegramProxy(context, config, fromServer);
            }
        });
    }

    private static void applyTelegramProxy(Context context, ProxyConfig config, boolean fromServer) {
        if (context == null || config == null || !config.isValid()) {
            return;
        }
        try {
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            String signature = config.signature();
            String appliedSignature = prefs(context).getString(KEY_LAST_APPLIED_SIGNATURE, "");
            boolean currentlyEnabled = prefs.getBoolean("proxy_enabled", false);
            String currentAddress = prefs.getString("proxy_ip", "");
            int currentPort = prefs.getInt("proxy_port", 1080);
            String currentUser = prefs.getString("proxy_user", "");
            String currentPass = prefs.getString("proxy_pass", "");
            String currentSecret = prefs.getString("proxy_secret", "");
            boolean sameCurrent = currentlyEnabled
                    && config.address.equals(currentAddress)
                    && config.port == currentPort
                    && config.username.equals(nullToEmpty(currentUser))
                    && config.password.equals(nullToEmpty(currentPass))
                    && config.secret.equals(nullToEmpty(currentSecret));

            if (sameCurrent && signature.equals(appliedSignature)) {
                return;
            }

            prefs.edit()
                    .putBoolean("proxy_enabled", true)
                    .putString("proxy_ip", config.address)
                    .putInt("proxy_port", config.port)
                    .putString("proxy_user", config.username)
                    .putString("proxy_pass", config.password)
                    .putString("proxy_secret", config.secret)
                    .apply();

            SharedConfig.ProxyInfo proxyInfo = new SharedConfig.ProxyInfo(
                    config.address,
                    config.port,
                    config.username,
                    config.password,
                    config.secret
            );
            SharedConfig.currentProxy = proxyInfo;
            addOrUpdateProxyList(proxyInfo);

            ConnectionsManager.setProxySettings(true,
                    config.address,
                    config.port,
                    config.username,
                    config.password,
                    config.secret);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            prefs(context).edit().putString(KEY_LAST_APPLIED_SIGNATURE, signature).apply();
            if (fromServer) {
                FileLog.d(TAG + " applied Telegram proxy " + config.safeLogString());
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void disableTelegramProxyOnUiThread(final Context context) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = MessagesController.getGlobalMainSettings();
                    prefs.edit()
                            .putBoolean("proxy_enabled", false)
                            .apply();
                    SharedConfig.currentProxy = null;
                    ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    prefs(context).edit().remove(KEY_LAST_APPLIED_SIGNATURE).apply();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        });
    }

    private static void addOrUpdateProxyList(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return;
        }
        try {
            for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
                SharedConfig.ProxyInfo item = SharedConfig.proxyList.get(i);
                if (item != null
                        && proxyInfo.address.equals(item.address)
                        && proxyInfo.port == item.port
                        && proxyInfo.secret.equals(nullToEmpty(item.secret))) {
                    item.username = proxyInfo.username;
                    item.password = proxyInfo.password;
                    item.secret = proxyInfo.secret;
                    return;
                }
            }
            SharedConfig.proxyList.add(0, proxyInfo);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static ProxyConfig loadCached(Context context) {
        try {
            SharedPreferences prefs = prefs(context);
            ProxyConfig config = new ProxyConfig();
            config.enabled = prefs.getBoolean(KEY_ENABLED, false);
            config.address = prefs.getString(KEY_ADDRESS, "");
            config.port = prefs.getInt(KEY_PORT, 0);
            config.username = prefs.getString(KEY_USERNAME, "");
            config.password = prefs.getString(KEY_PASSWORD, "");
            config.secret = prefs.getString(KEY_SECRET, "");
            config.normalize();
            return config.isValid() ? config : null;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static void saveCached(Context context, ProxyConfig config) {
        if (context == null || config == null || !config.isValid()) {
            return;
        }
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_ADDRESS, config.address)
                .putInt(KEY_PORT, config.port)
                .putString(KEY_USERNAME, config.username)
                .putString(KEY_PASSWORD, config.password)
                .putString(KEY_SECRET, config.secret)
                .apply();
    }

    private static void clearCached(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit()
                .clear()
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class ProxyConfig {
        boolean enabled = true;
        String address = "";
        int port;
        String username = "";
        String password = "";
        String secret = "";

        void normalize() {
            address = normalizeAddress(address);
            username = trim(username);
            password = trim(password);
            secret = trim(secret);
            if (!TextUtils.isEmpty(secret)) {
                // Telegram treats non-empty secret as MTProto. Keep SOCKS credentials empty in this case.
                username = "";
                password = "";
            }
        }

        boolean isValid() {
            return enabled && !TextUtils.isEmpty(address) && port > 0 && port <= 65535;
        }

        String signature() {
            return address + ":" + port + ":" + username + ":" + password + ":" + secret;
        }

        String safeLogString() {
            return address + ":" + port + (TextUtils.isEmpty(secret) ? " socks" : " mtproto");
        }

        private static String normalizeAddress(String value) {
            String result = trim(value);
            if (result.startsWith("http://")) {
                result = result.substring("http://".length());
            } else if (result.startsWith("https://")) {
                result = result.substring("https://".length());
            } else if (result.startsWith("socks5://")) {
                result = result.substring("socks5://".length());
            } else if (result.startsWith("tg://")) {
                result = result.substring("tg://".length());
            }
            int slash = result.indexOf('/');
            if (slash >= 0) {
                result = result.substring(0, slash);
            }
            int colon = result.lastIndexOf(':');
            if (colon > 0 && colon < result.length() - 1 && result.indexOf(']') < colon) {
                // If backend accidentally sends host:port in address, keep host only; port field is authoritative.
                String tail = result.substring(colon + 1);
                try {
                    Integer.parseInt(tail);
                    result = result.substring(0, colon);
                } catch (Throwable ignore) {
                    // IPv6 or hostname with colon; keep as is.
                }
            }
            return result.trim();
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
