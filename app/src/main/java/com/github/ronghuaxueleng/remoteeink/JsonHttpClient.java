package com.github.ronghuaxueleng.remoteeink;

import android.content.Context;
import android.net.SSLCertificateSocketFactory;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Shared JSON transport with the Kindle Android 4.4 TLS compatibility settings. */
final class JsonHttpClient {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private final Context context;
    private final String weatherHost, apiKey;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;
    private SSLSocketFactory socketFactory;

    JsonHttpClient(Context context, String weatherHost, String apiKey) {
        this.context = context.getApplicationContext();
        this.weatherHost = weatherHost;
        this.apiKey = apiKey;
    }

    String fetch(String endpoint, int timeout) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = connection;
            if (cancelled) throw new IOException("已取消");
            if (Build.VERSION.SDK_INT < 20 && connection instanceof HttpsURLConnection) {
                if (socketFactory == null) socketFactory = tls12SocketFactory();
                ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
            }
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json, text/javascript, */*");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) KindleWeatherInk");
            if ("https".equals(connection.getURL().getProtocol()) && weatherHost.equals(connection.getURL().getHost())) {
                connection.setRequestProperty("X-QW-Api-Key", apiKey);
                // Don't forward the credential if the service redirects to another host.
                connection.setInstanceFollowRedirects(false);
            }
            connection.setRequestProperty("Accept-Encoding", "gzip");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException(errorText(code));
            if (connection.getContentLength() > MAX_BODY_BYTES) throw new IOException("服务器响应过大");
            try (InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                        ? new GZIPInputStream(connection.getInputStream()) : connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled) throw new IOException("已取消");
                    if (bytes.size() + count > MAX_BODY_BYTES) throw new IOException("服务器响应过大");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), "UTF-8");
            }
        } finally {
            activeConnection = null;
            if (connection != null) connection.disconnect();
        }
    }
    void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
    private static String errorText(int code) {
        if (code == 401 || code == 403) return "服务器拒绝访问（HTTP " + code + "）";
        if (code == 404) return "接口不存在（HTTP 404）";
        return "服务器返回 HTTP " + code;
    }
    private SSLSocketFactory tls12SocketFactory() throws GeneralSecurityException, IOException {
        TrustManagerFactory systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemFactory.init((KeyStore) null);
        X509TrustManager systemTrust = (X509TrustManager) systemFactory.getTrustManagers()[0];
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        // Official CA: https://letsencrypt.org/certs/isrg-root-x2.pem (expires 2040-09-17).
        try (InputStream input = context.getResources().openRawResource(R.raw.isrg_root_x2)) {
            roots.setCertificateEntry("isrg-root-x2", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        // Official CA: https://cacerts.digicert.com/DigiCertGlobalRootG2.crt (expires 2038-01-15).
        try (InputStream input = context.getResources().openRawResource(R.raw.digicert_global_root_g2)) {
            roots.setCertificateEntry("digicert-global-root-g2", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        TrustManagerFactory extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        extraFactory.init(roots);
        X509TrustManager extraTrust = (X509TrustManager) extraFactory.getTrustManagers()[0];
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try { systemTrust.checkServerTrusted(chain, authType); }
                catch (CertificateException e) { extraTrust.checkServerTrusted(chain, authType); }
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                systemTrust.checkClientTrusted(chain, authType);
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] system = systemTrust.getAcceptedIssuers();
                X509Certificate[] extra = extraTrust.getAcceptedIssuers();
                X509Certificate[] all = Arrays.copyOf(system, system.length + extra.length);
                System.arraycopy(extra, 0, all, system.length, extra.length);
                return all;
            }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {trust}, null);
        return new Tls12SocketFactory(tls.getSocketFactory());
    }

    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket enableTls12(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                // Old OpenSSL can advertise TLS 1.0 when TLS 1.2 is added with TLS 1.1 disabled.
                ssl.setEnabledProtocols(new String[] {"TLSv1.2"});
            }
            return socket;
        }

        private Socket configure(Socket socket, String host) {
            // API 17-19's platform TLS sockets expose SNI through this public Android API.
            new SSLCertificateSocketFactory(15000).setHostname(socket, host);
            return enableTls12(socket);
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return enableTls12(delegate.createSocket()); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(socket, host, port, autoClose), host);
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port), host);
        }
        @Override public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, localAddress, localPort), host);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort) throws IOException {
            return enableTls12(delegate.createSocket(host, port, localAddress, localPort));
        }
    }

}
