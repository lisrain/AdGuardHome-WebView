package org.adguardhome;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private View errorView;
    private TextView errorMessage;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean isRefreshing = false;

    private ValueCallback<Uri[]> pendingFileCallback;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private String targetUrl = TARGET_URL;

    private static final String TARGET_URL = "http://127.0.0.1:3000";
    private static final long REFRESH_TIMEOUT_MS = 8000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_main);

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

        webView.loadUrl(targetUrl);
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

        // EdgeToEdge 下必须自己把状态栏和导航栏占用的空间还回去，否则页面顶部和底部会被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    baseLeft + systemBars.left,
                    baseTop + systemBars.top,
                    baseRight + systemBars.right,
                    baseBottom + systemBars.bottom);
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

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        boolean dark = isDarkMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(dark);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_AUTO);
        }

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
                if (!isRefreshing) {
                    return;
                }
                refreshHandler.postDelayed(MainActivity.this::stopRefreshing, REFRESH_TIMEOUT_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                stopRefreshing();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(error.getDescription());
                }
            }
        });
    }

    private void setupErrorView() {
        Button retryButton = errorView.findViewById(R.id.retryButton);
        retryButton.setOnClickListener(v -> {
            hideError();
            webView.reload();
        });
    }

    private void showError(CharSequence detail) {
        stopRefreshing();
        if (TextUtils.isEmpty(detail)) {
            errorMessage.setText(R.string.error_message);
        } else {
            errorMessage.setText(getString(R.string.error_message)
                    + "\n\n" + getString(R.string.error_detail, detail));
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
    }

    private void stopRefreshing() {
        refreshHandler.removeCallbacksAndMessages(null);
        isRefreshing = false;
        swipeRefresh.setRefreshing(false);
    }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}