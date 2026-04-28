package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.wallet.config.WalletConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ServerApiManager {

    private static final String PREFS_NAME = "mainconfig";
    private static final String KEY_SERVER_BASE_URL = "server_api_base_url";

    private static final String PATH_PROXY = "/client/proxy";
    private static final String PATH_VERSION_CHECK = "/client/version/check";

    private static volatile boolean refreshingProxy;

    public interface VersionCheckCallback {
        void onResult(boolean hasUpdate, String updateUrl, String latestVersion, String message);

        default void onError(String error) {
        }
    }

    public static void refreshProxyFromServerIfNeeded() {
        if (refreshingProxy) {
            return;
        }
        refreshingProxy = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.ProxyInfo proxyInfo = requestProxyInfo();
                if (proxyInfo != null) {
                    SharedConfig.applyForcedProxy(proxyInfo);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                refreshingProxy = false;
            }
        });
    }

    public static void checkVersion(VersionCheckCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject response = requestJson(versionCheckUrl());
                JSONObject data = response.optJSONObject("data");
                if (data == null) {
                    throw new IllegalStateException("version data missing");
                }
                boolean hasUpdate = data.optBoolean("hasUpdate", false);
                String updateUrl = data.optString("downloadUrl", "");
                String latestVersion = data.optString("versionName", "");
                String message = data.optString("message", "");
                AndroidUtilities.runOnUIThread(() -> callback.onResult(hasUpdate, updateUrl, latestVersion, message));
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private static SharedConfig.ProxyInfo requestProxyInfo() throws Exception {
        JSONObject response = requestJson(proxyUrl());
        JSONObject data = response.optJSONObject("data");
        if (data == null) {
            return null;
        }
        String address = data.optString("address", "");
        int port = data.optInt("port", 0);
        if (TextUtils.isEmpty(address) || port <= 0) {
            return null;
        }
        return new SharedConfig.ProxyInfo(
                address,
                port,
                data.optString("username", ""),
                data.optString("password", ""),
                data.optString("secret", "")
        );
    }

    private static String proxyUrl() {
        return baseUrl() + PATH_PROXY;
    }

    private static String versionCheckUrl() {
        Uri.Builder builder = Uri.parse(baseUrl() + PATH_VERSION_CHECK).buildUpon();
        builder.appendQueryParameter("platform", "android");
        builder.appendQueryParameter("versionCode", String.valueOf(SharedConfig.buildVersion()));
        return builder.toString();
    }

    private static String baseUrl() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String value = preferences.getString(KEY_SERVER_BASE_URL, WalletConfig.getRedPacketApiBaseUrl());
        if (TextUtils.isEmpty(value)) {
            value = WalletConfig.getRedPacketApiBaseUrl();
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static JSONObject requestJson(String link) throws Exception {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            connection = (HttpURLConnection) new URL(link).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("http code " + responseCode);
            }
            inputStream = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString("UTF-8"));
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Throwable ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
