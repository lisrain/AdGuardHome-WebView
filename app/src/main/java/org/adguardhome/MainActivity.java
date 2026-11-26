package org.adguardhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private View errorView;
    private TextView errorMessage;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean isRefreshing = false;

    // CookieManager.flush() 会阻塞并做磁盘 I/O，只能在后台线程调用
    private final Executor cookieFlushExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cookie-flush");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean cookieFlushPending = new AtomicBoolean();

    private ValueCallback<Uri[]> pendingFileCallback;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private String targetUrl = TARGET_URL;

    private static final String TARGET_URL = "http://127.0.0.1:3000";
    private static final String PREFS_NAME = "settings";
    private static final String KEY_TARGET_URL = "target_url";
    private static final long REFRESH_TIMEOUT_MS = 8000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_main);

        targetUrl = getTargetUrl();

        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView = findViewById(R.id.webview);
        errorView = findViewById(R.id.errorView);
        errorMessage = findViewById(R.id.errorMessage);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> deliverFileResult(result.getResultCode(), result.getData()));

        setupSystemBars();
        setupWebView();
        setupSwipeRefresh();
        setupErrorView();
        setupBackNavigation();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(targetUrl);
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                // 没有可回退的历史时交还系统，走默认的退出流程
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        View root = findViewById(R.id.main);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        // 顶部避让状态栏；底部保持沉浸，让页面背景一直铺到手势条下方
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    baseLeft + systemBars.left,
                    baseTop + systemBars.top,
                    baseRight + systemBars.right,
                    baseBottom);
            return insets;
        });

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            boolean isDarkMode = isDarkMode();
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        // 接收 Cookie 的开关默认是开的，但部分定制系统会改掉这个全局策略；
        // 显式声明一次，避免登录 Cookie 被静默丢弃。
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // 反代 + 单点登录时登录页和后台可能不同源，第三方 Cookie 必须放行
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // AdGuard Home 是响应式布局，缩放只会和下拉刷新争抢手势
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // 页面自带 color-scheme 适配，强制变暗反而会二次污染配色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        webView.setBackgroundColor(isDarkMode() ? 0xFF15171A : 0xFFFFFFFF);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    filePickerLauncher.launch(params.createIntent());
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingFileCallback = null;
                    return false;
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (isSameOrigin(url)) {
                downloadFile(url, userAgent, contentDisposition, mimeType);
            } else {
                openExternally(Uri.parse(url));
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                hideError();
                // 登录响应里的 Set-Cookie 此时已经写入，先落盘一次，
                // 不依赖首页资源全部加载完成
                flushCookies();
                if (!isRefreshing) {
                    return;
                }
                refreshHandler.postDelayed(MainActivity.this::stopRefreshing, REFRESH_TIMEOUT_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                stopRefreshing();
                // 登录成功会整页跳转到后台首页，此时 Cookie 刚写入，立刻落盘
                flushCookies();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(error.getDescription());
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 内网常配自签证书，交给用户决定而不是直接中止
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.ssl_error_title)
                        .setMessage(getString(R.string.ssl_error_message, error.getUrl()))
                        .setPositiveButton(R.string.ssl_error_continue,
                                (dialog, which) -> handler.proceed())
                        .setNegativeButton(R.string.ssl_error_cancel,
                                (dialog, which) -> handler.cancel())
                        .setOnCancelListener(dialog -> handler.cancel())
                        .show();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 渲染进程被系统回收时若不处理，整个应用会跟着崩溃
                rebuildWebView();
                return true;
            }
        });
    }

    /**
     * 把内存里的 Cookie 写进磁盘。
     *
     * <p>WebView 平时只把 Cookie 放在内存里，靠自己的后台任务慢慢持久化。进程被系统回收、
     * 或者用户从最近任务里划掉应用时，这次持久化未必来得及完成，下次启动就会退回登录页。
     * 页面加载完成和进入后台时各冲洗一次，能把丢失窗口压到最小。</p>
     */
    private void flushCookies() {
        if (!cookieFlushPending.compareAndSet(false, true)) {
            return;
        }
        try {
            cookieFlushExecutor.execute(() -> {
                try {
                    CookieManager.getInstance().flush();
                } catch (RuntimeException | Error e) {
                    // 渲染进程已销毁或 WebView provider 不可用时冲洗失败，
                    // 下次页面加载或退到后台还会再试
                } finally {
                    cookieFlushPending.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            cookieFlushPending.set(false);
        }
    }

    private void rebuildWebView() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        swipeRefresh.removeAllViews();
        if (webView != null) {
            webView.destroy();
        }
        webView = new WebView(this);
        swipeRefresh.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupWebView();
        setupSwipeRefresh();
        webView.loadUrl(targetUrl);
    }

    private void setupErrorView() {
        Button retryButton = errorView.findViewById(R.id.retryButton);
        retryButton.setOnClickListener(v -> {
            hideError();
            webView.reload();
        });
        errorView.findViewById(R.id.settingsButton)
                .setOnClickListener(v -> showSettingsDialog());
    }

    private String getTargetUrl() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_TARGET_URL, TARGET_URL);
    }

    private void saveTargetUrl(String url) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_TARGET_URL, url)
                .apply();
    }

    private void showSettingsDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        EditText input = content.findViewById(R.id.urlInput);
        input.setText(targetUrl);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(content)
                .setPositiveButton(R.string.settings_apply,
                        (dialog, which) -> applyTargetUrl(input.getText().toString()))
                .setNeutralButton(R.string.settings_detect,
                        (dialog, which) -> detectTargetUrl())
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    private void applyTargetUrl(String raw) {
        String url = normalizeUrl(raw);
        if (url == null) {
            Toast.makeText(this, R.string.settings_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        saveTargetUrl(url);
        targetUrl = url;
        hideError();
        webView.loadUrl(url);
    }

    private String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        return Uri.parse(value).getHost() == null ? null : value;
    }

    private void detectTargetUrl() {
        Toast.makeText(this, R.string.settings_detecting, Toast.LENGTH_SHORT).show();
        RootConfigReader.detect(url -> {
            if (url == null) {
                Toast.makeText(this, R.string.settings_detect_failed, Toast.LENGTH_LONG).show();
                return;
            }
            applyTargetUrl(url);
            Toast.makeText(this, getString(R.string.settings_detected, url), Toast.LENGTH_LONG)
                    .show();
        });
    }

    private void showError(CharSequence detail) {
        stopRefreshing();
        if (TextUtils.isEmpty(detail)) {
            errorMessage.setText(R.string.error_message);
        } else {
            errorMessage.setText(getString(R.string.error_detail_full,
                    getString(R.string.error_message), detail));
        }
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    /** 同源链接交给 WebView，其余交给系统浏览器或对应的外部应用。 */
    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return !isSameOrigin(uri.toString());
        }
        openExternally(uri);
        return true;
    }

    private boolean isSameOrigin(String url) {
        Uri base = Uri.parse(targetUrl);
        Uri target = Uri.parse(url);
        return TextUtils.equals(base.getScheme(), target.getScheme())
                && TextUtils.equals(base.getHost(), target.getHost())
                && base.getPort() == target.getPort();
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = "intent".equals(uri.getScheme())
                    ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadFile(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (!TextUtils.isEmpty(mimeType)) {
                request.setMimeType(mimeType);
            }
            if (!TextUtils.isEmpty(userAgent)) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            // AdGuard Home 的导出接口依赖登录 Cookie，DownloadManager 不会自动带上
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie);
            }
            request.setTitle(fileName);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                // Android 10 以下写入公共目录需要存储权限，这里退回到应用专属目录避免授权
                request.setDestinationInExternalFilesDir(
                        this, Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("DownloadManager is unavailable");
            }
            manager.enqueue(request);
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void deliverFileResult(int resultCode, Intent data) {
        if (pendingFileCallback == null) {
            return;
        }
        Uri[] uris = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                uris = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    uris[i] = clipData.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        pendingFileCallback.onReceiveValue(uris);
        pendingFileCallback = null;
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            R.color.swipe_refresh_color_1,
            R.color.swipe_refresh_color_2,
            R.color.swipe_refresh_color_3,
            R.color.swipe_refresh_color_4
        );

        swipeRefresh.setOnRefreshListener(() -> {
            isRefreshing = true;
            webView.reload();
        });

        // SPA 内部有自己的滚动容器，只有页面真正滚到顶部时才允许下拉刷新
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                swipeRefresh.setEnabled(scrollY == 0));
    }

    private void stopRefreshing() {
        refreshHandler.removeCallbacksAndMessages(null);
        isRefreshing = false;
        swipeRefresh.setRefreshing(false);
        swipeRefresh.setEnabled(webView == null || webView.getScrollY() == 0);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // onPause 一定会发生在进程被后台回收之前，在这里落盘最保险
        flushCookies();
    }

    @Override
    protected void onDestroy() {
        flushCookies();
        refreshHandler.removeCallbacksAndMessages(null);
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}