package org.schabi.newpipe;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.util.InfoCache;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class DownloaderImpl extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE_KEY =
            "youtube_restricted_mode_key";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
    public static final String YOUTUBE_DOMAIN = "youtube.com";

    private static DownloaderImpl instance;
    private final Map<String, String> mCookies;
    private volatile OkHttpClient client;

    private DownloaderImpl(final OkHttpClient.Builder builder) {
        this.client = builder
                .readTimeout(30, TimeUnit.SECONDS)
//                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"),
//                        16 * 1024 * 1024))
                .build();
        this.mCookies = new HashMap<>();
    }

    @NonNull
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * It's recommended to call exactly once in the entire lifetime of the application.
     *
     * @param builder if null, default builder will be used
     * @return a new instance of {@link DownloaderImpl}
     */
    public static DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
        instance = new DownloaderImpl(
                builder != null ? builder : new OkHttpClient.Builder());
        return instance;
    }

    public static DownloaderImpl getInstance() {
        return instance;
    }

    public synchronized void updateNetworkConfiguration(@NonNull final Context context) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);

        final boolean proxyEnabled =
                preferences.getBoolean(context.getString(R.string.proxy_enabled_key), false);

        final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS);

        Proxy configuredProxy = null;
        String configuredProxyUser = null;
        String configuredProxyPassword = null;
        String configuredProxyHost = null;
        int configuredProxyPort = -1;

        if (proxyEnabled) {
            final String rawHost = preferences.getString(
                    context.getString(R.string.proxy_host_key), "");
            final String host = normalizeHost(rawHost);
            final String rawPort = preferences.getString(
                    context.getString(R.string.proxy_port_key),
                    context.getString(R.string.proxy_port_default));
            final Integer port = parsePort(rawPort);

            if (host != null && port != null) {
                final String proxyType = preferences.getString(
                        context.getString(R.string.proxy_type_key),
                        context.getString(R.string.proxy_type_http_value));
                final String normalizedType = proxyType == null
                        ? context.getString(R.string.proxy_type_http_value)
                        : proxyType.trim().toLowerCase(Locale.US);

                final Proxy.Type type = context.getString(R.string.proxy_type_socks5_value)
                        .equals(normalizedType)
                        ? Proxy.Type.SOCKS
                        : Proxy.Type.HTTP;
                final Proxy proxy = new Proxy(type, new InetSocketAddress(host, port));
                builder.proxy(proxy);
                configuredProxy = proxy;
                configuredProxyHost = host;
                configuredProxyPort = port;

                final String username = normalizeHost(preferences.getString(
                        context.getString(R.string.proxy_username_key), ""));
                final String password = preferences.getString(
                        context.getString(R.string.proxy_password_key), "");

                if (username != null) {
                    final String normalizedPassword = password == null ? "" : password;
                    configuredProxyUser = username;
                    configuredProxyPassword = normalizedPassword;
                    if (type == Proxy.Type.HTTP) {
                        builder.proxyAuthenticator((route, response) -> {
                            if (response.request().header("Proxy-Authorization") != null) {
                                return null;
                            }
                            return response.request().newBuilder()
                                    .header("Proxy-Authorization",
                                            Credentials.basic(username, normalizedPassword))
                                    .build();
                        });
                    }
                }
            }
        }

        applyJvmProxySystemSettings(configuredProxy, configuredProxyHost, configuredProxyPort,
                configuredProxyUser, configuredProxyPassword);
        this.client = builder.build();
    }

    private static void applyJvmProxySystemSettings(@Nullable final Proxy proxy,
                                                    @Nullable final String host,
                                                    final int port,
                                                    @Nullable final String username,
                                                    @Nullable final String password) {
        // Clear previously configured proxy settings first.
        clearProperty("http.proxyHost");
        clearProperty("http.proxyPort");
        clearProperty("https.proxyHost");
        clearProperty("https.proxyPort");
        clearProperty("socksProxyHost");
        clearProperty("socksProxyPort");
        clearProperty("java.net.socks.username");
        clearProperty("java.net.socks.password");
        clearProperty("jdk.http.auth.tunneling.disabledSchemes");
        clearProperty("jdk.http.auth.proxying.disabledSchemes");

        if (proxy == null || host == null || port <= 0) {
            Authenticator.setDefault(null);
            return;
        }

        if (proxy.type() == Proxy.Type.SOCKS) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", Integer.toString(port));
            if (username != null) {
                System.setProperty("java.net.socks.username", username);
                System.setProperty("java.net.socks.password", password == null ? "" : password);
            }
        } else {
            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", Integer.toString(port));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", Integer.toString(port));
            // Some HttpURLConnection implementations disable Basic auth for CONNECT by default.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        }

        if (username == null) {
            Authenticator.setDefault(null);
            return;
        }

        final String authUser = username;
        final String authPassword = password == null ? "" : password;
        // Some Android/JDK stacks can report a different requestor type during CONNECT-based
        // proxy authentication. Keep the decision conservative: only return credentials when
        // it looks like a proxy challenge.
        final String proxyHostForAuth = host;
        final int proxyPortForAuth = port;
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) {
                    return new PasswordAuthentication(authUser, authPassword.toCharArray());
                }

                // Fallback for stacks that report another requestor type (often TARGET) during
                // CONNECT proxy auth challenges.
                final String requestingHost = getRequestingHost();
                final int requestingPort = getRequestingPort();

                final boolean requestingHostMatchesProxy =
                        requestingHost != null
                                && proxyHostForAuth != null
                                && requestingHost.equalsIgnoreCase(proxyHostForAuth);
                final boolean requestingPortMatchesProxy = requestingPort == proxyPortForAuth;

                if (requestingHostMatchesProxy || requestingPortMatchesProxy) {
                    return new PasswordAuthentication(authUser, authPassword.toCharArray());
                }

                return null;
            }
        });
    }

    private static void clearProperty(@NonNull final String key) {
        System.clearProperty(key);
    }

    @Nullable
    private static String normalizeHost(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        String host = value.trim();
        if (host.isEmpty()) {
            return null;
        }
        host = host.replaceFirst("^https?://", "");
        final int slashIndex = host.indexOf('/');
        if (slashIndex >= 0) {
            host = host.substring(0, slashIndex);
        }
        return host.isEmpty() ? null : host;
    }

    @Nullable
    private static Integer parsePort(@Nullable final String rawPort) {
        if (rawPort == null) {
            return null;
        }
        try {
            final int port = Integer.parseInt(rawPort.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    public String getCookies(final String url) {
        final String youtubeCookie = url.contains(YOUTUBE_DOMAIN)
                ? getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY) : null;

        // Recaptcha cookie is always added TODO: not sure if this is necessary
        return Stream.of(youtubeCookie, getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY))
                .filter(Objects::nonNull)
                .flatMap(cookies -> Arrays.stream(cookies.split("; *")))
                .distinct()
                .collect(Collectors.joining("; "));
    }

    public String getCookie(final String key) {
        return mCookies.get(key);
    }

    public void setCookie(final String key, final String cookie) {
        mCookies.put(key, cookie);
    }

    public void removeCookie(final String key) {
        mCookies.remove(key);
    }

    public void updateYoutubeRestrictedModeCookies(final Context context) {
        final String restrictedModeEnabledKey =
                context.getString(R.string.youtube_restricted_mode_enabled);
        final boolean restrictedModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(restrictedModeEnabledKey, false);
        updateYoutubeRestrictedModeCookies(restrictedModeEnabled);
    }

    public void updateYoutubeRestrictedModeCookies(final boolean youtubeRestrictedModeEnabled) {
        if (youtubeRestrictedModeEnabled) {
            setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
                    YOUTUBE_RESTRICTED_MODE_COOKIE);
        } else {
            removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        }
        InfoCache.getInstance().clearCache();
    }

    /**
     * Get the size of the content that the url is pointing by firing a HEAD request.
     *
     * @param url an url pointing to the content
     * @return the size of the content, in bytes
     */
    public long getContentLength(final String url) throws IOException {
        try {
            final Response response = head(url);
            return Long.parseLong(response.getHeader("Content-Length"));
        } catch (final NumberFormatException e) {
            throw new IOException("Invalid content length", e);
        } catch (final ReCaptchaException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Response execute(@NonNull final Request request)
            throws IOException, ReCaptchaException {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(dataToSend);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)
                .addHeader("User-Agent", USER_AGENT);

        final String cookies = getCookies(url);
        if (!cookies.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookies);
        }

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue ->
                    requestBuilder.addHeader(headerName, headerValue));
        });

        try (
                okhttp3.Response response = getClient().newCall(requestBuilder.build()).execute()
        ) {
            if (response.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                responseBodyToReturn = body.string();
            }

            final String latestUrl = response.request().url().toString();
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl);
        }
    }
}
