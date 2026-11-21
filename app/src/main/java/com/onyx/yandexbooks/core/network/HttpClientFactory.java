package com.onyx.yandexbooks.core.network;

import android.content.Context;
import android.util.Log;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.YandexBooksApp;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

/**
 * Фабрика OkHttpClient, адаптированная под Android 4.2 / 4.4.
 * Включает принудительную поддержку TLS 1.2 через Tls12SocketFactory и совместимый TrustManager.
 */
public class HttpClientFactory {

    private static final String TAG = "HttpClientFactory";
    private static OkHttpClient sharedClient;
    private static X509TrustManager sharedTrustManager;

    public static synchronized OkHttpClient getClient() {
        if (sharedClient == null) {
            sharedClient = createConfiguredClient();
        }
        return sharedClient;
    }

    private static OkHttpClient createConfiguredClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(new ConnectionPool(10, 2, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true);

        try {
            ConnectionSpec modernTlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build();

            List<ConnectionSpec> specs = new ArrayList<>();
            specs.add(modernTlsSpec);
            specs.add(ConnectionSpec.CLEARTEXT);
            builder.connectionSpecs(specs);

            X509TrustManager trustManager = getSharedTrustManager();
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, null);

            SSLSocketFactory socketFactory = new Tls12SocketFactory(sslContext.getSocketFactory());
            builder.sslSocketFactory(socketFactory, trustManager);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply custom SSLSocketFactory, falling back to default OkHttp builder", t);
        }

        return builder.build();
    }

    public static synchronized X509TrustManager getSharedTrustManager() {
        if (sharedTrustManager != null) {
            return sharedTrustManager;
        }
        final X509TrustManager systemTm = loadSystemTrustManager();
        final X509TrustManager bundledTm = loadBundledTrustManager(YandexBooksApp.getInstance());

        if (systemTm != null && bundledTm != null) {
            sharedTrustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        systemTm.checkClientTrusted(chain, authType);
                    } catch (CertificateException sysErr) {
                        bundledTm.checkClientTrusted(chain, authType);
                    }
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        systemTm.checkServerTrusted(chain, authType);
                    } catch (CertificateException sysErr) {
                        try {
                            bundledTm.checkServerTrusted(chain, authType);
                        } catch (CertificateException bundledErr) {
                            throw sysErr;
                        }
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    Set<X509Certificate> merged = new LinkedHashSet<>();
                    X509Certificate[] sysIssuers = systemTm.getAcceptedIssuers();
                    if (sysIssuers != null) merged.addAll(Arrays.asList(sysIssuers));
                    X509Certificate[] bundledIssuers = bundledTm.getAcceptedIssuers();
                    if (bundledIssuers != null) merged.addAll(Arrays.asList(bundledIssuers));
                    return merged.toArray(new X509Certificate[0]);
                }
            };
            return sharedTrustManager;
        }
        if (systemTm != null) {
            sharedTrustManager = systemTm;
            return sharedTrustManager;
        }
        if (bundledTm != null) {
            sharedTrustManager = bundledTm;
            return sharedTrustManager;
        }
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No system X509TrustManager available");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No system X509TrustManager available");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static X509TrustManager loadSystemTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to obtain default X509TrustManager", e);
        }
        return null;
    }

    private static X509TrustManager loadBundledTrustManager(Context context) {
        if (context == null) return null;
        InputStream raw = null;
        BufferedReader reader = null;
        try {
            raw = context.getResources().openRawResource(R.raw.ca_roots);
            reader = new BufferedReader(new InputStreamReader(raw, "US-ASCII"));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            StringBuilder pemBlock = null;
            int idx = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if ("-----BEGIN CERTIFICATE-----".equals(trimmed)) {
                    pemBlock = new StringBuilder();
                    pemBlock.append(trimmed).append('\n');
                } else if (pemBlock != null) {
                    pemBlock.append(trimmed).append('\n');
                    if ("-----END CERTIFICATE-----".equals(trimmed)) {
                        try {
                            byte[] certBytes = pemBlock.toString().getBytes("US-ASCII");
                            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
                            if (cert != null) {
                                ks.setCertificateEntry("ca_" + (idx++), cert);
                            }
                        } catch (Throwable ignored) {
                            // Отдельный корневой сертификат не поддерживается старым парсером — пропускаем
                        }
                        pemBlock = null;
                    }
                }
            }
            if (idx == 0) {
                return null;
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    Log.i(TAG, "Bundled CA TrustManager initialized (" + idx + " root certificates)");
                    return (X509TrustManager) tm;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load bundled CA roots", t);
        } finally {
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
            if (raw != null) try { raw.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket sslSocket = (SSLSocket) s;
                String[] supported = sslSocket.getSupportedProtocols();
                if (supported != null) {
                    List<String> supportedList = Arrays.asList(supported);
                    List<String> enabled = new ArrayList<>();
                    if (supportedList.contains("TLSv1.3")) enabled.add("TLSv1.3");
                    if (supportedList.contains("TLSv1.2")) enabled.add("TLSv1.2");
                    if (supportedList.contains("TLSv1.1")) enabled.add("TLSv1.1");
                    if (supportedList.contains("TLSv1")) enabled.add("TLSv1");
                    if (!enabled.isEmpty()) {
                        sslSocket.setEnabledProtocols(enabled.toArray(new String[0]));
                    }
                }
            }
            return s;
        }
    }
}
