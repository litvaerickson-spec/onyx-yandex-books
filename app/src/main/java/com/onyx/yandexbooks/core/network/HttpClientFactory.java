package com.onyx.yandexbooks.core.network;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    public static X509TrustManager getSharedTrustManager() {
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
