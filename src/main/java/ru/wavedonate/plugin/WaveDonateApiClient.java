package ru.wavedonate.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class WaveDonateApiClient {
    static final String DEFAULT_URL = "https://api.wdonate.wtf";

    private static final SSLSocketFactory TLS = buildTls();

    private final String apiUrl;
    private final String shopKey;
    private final int serverId;
    private final int timeoutMillis;
    private final String version;

    WaveDonateApiClient(String apiUrl, String shopKey, int serverId, int timeoutMillis, String version) {
        this.apiUrl = normalizeUrl(apiUrl);
        this.shopKey = shopKey == null ? "" : shopKey.trim();
        this.serverId = serverId;
        this.timeoutMillis = timeoutMillis;
        this.version = version;
    }

    String url() {
        return apiUrl;
    }

    List<PendingOrder> fetchPendingOrders() throws IOException {
        Map<String, Object> body = Json.object(Json.parse(request("GET", "/api/plugin/pending", null)));
        List<PendingOrder> orders = new ArrayList<PendingOrder>();
        for (Object item : Json.array(body.get("orders"))) {
            PendingOrder order = PendingOrder.fromJson(Json.object(item));
            if (order != null) orders.add(order);
        }
        return orders;
    }

    List<String> claimOrder(long orderId, String player) throws IOException {
        String payload = "{\"order_id\":" + orderId + ",\"player\":" + Json.quote(player) + "}";
        Map<String, Object> body = Json.object(Json.parse(request("POST", "/api/plugin/execute", payload)));
        if (!body.containsKey("commands")) {
            return null;
        }
        List<String> commands = new ArrayList<String>();
        for (Object c : Json.array(body.get("commands"))) {
            if (c != null) commands.add(String.valueOf(c));
        }
        return commands;
    }

    void ping() throws IOException {
        Map<String, Object> body = Json.object(Json.parse(request("GET", "/api/plugin/ping", null)));
        if (!Boolean.TRUE.equals(body.get("ok"))) {
            throw new IOException("API ответил без ok");
        }
    }

    private String request(String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl + path).openConnection();
        if (connection instanceof HttpsURLConnection && TLS != null) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(TLS);
        }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "WaveDonate-Plugin/" + version);
        connection.setRequestProperty("x-plugin-version", version);
        connection.setRequestProperty("x-shop-key", shopKey);
        if (serverId > 0) {
            connection.setRequestProperty("x-server-id", String.valueOf(serverId));
        }
        try {
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int status = connection.getResponseCode();
            String response = readFully(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new ApiException(status, describe(status, response));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(int status, String response) {
        String message = "";
        try {
            message = Json.string(Json.object(Json.parse(response)).get("message"), "");
        } catch (RuntimeException ignored) {
        }
        if (status == 401 || status == 403) {
            if (message.toLowerCase().contains("server-id")) return "неверный server-id — проверьте ID режима в кабинете";
            if (message.toLowerCase().contains("key")) return "неверный shop-key — скопируйте ключ из кабинета";
            return "доступ запрещён (HTTP " + status + ")" + (message.isEmpty() ? "" : ": " + message);
        }
        if (status == 429) return "слишком частые запросы — увеличьте request-delay-seconds";
        if (status >= 500) return "сервер WaveDonate временно недоступен (HTTP " + status + ")";
        return "HTTP " + status + (message.isEmpty() ? "" : ": " + message);
    }

    private static String normalizeUrl(String rawUrl) {
        String value = rawUrl == null || rawUrl.trim().isEmpty() ? DEFAULT_URL : rawUrl.trim();

        if (value.contains("lastway.fun")) value = DEFAULT_URL;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/api")) value = value.substring(0, value.length() - 4);
        return value;
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 2_000_000) throw new IOException("Слишком большой ответ API");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }

    private static SSLSocketFactory buildTls() {
        try {
            TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            system.init((KeyStore) null);

            KeyStore extraStore = KeyStore.getInstance(KeyStore.getDefaultType());
            extraStore.load(null, null);
            InputStream pem = WaveDonateApiClient.class.getResourceAsStream("/letsencrypt-roots.pem");
            if (pem == null) return null;
            try {
                int n = 0;
                for (java.security.cert.Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(pem)) {
                    extraStore.setCertificateEntry("letsencrypt-root-" + (n++), cert);
                }
            } finally {
                pem.close();
            }
            TrustManagerFactory extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            extra.init(extraStore);

            final X509TrustManager primary = firstX509(system.getTrustManagers());
            final X509TrustManager fallback = firstX509(extra.getTrustManagers());
            if (primary == null || fallback == null) return null;
            X509TrustManager combined = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    primary.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        primary.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        fallback.checkServerTrusted(chain, authType);
                    } catch (RuntimeException e) {
                        fallback.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return primary.getAcceptedIssuers();
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{combined}, null);
            return context.getSocketFactory();
        } catch (Exception e) {
            return null;
        }
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) return (X509TrustManager) m;
        }
        return null;
    }

    static final class ApiException extends IOException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
