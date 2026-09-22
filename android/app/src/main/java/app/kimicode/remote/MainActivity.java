package app.kimicode.remote;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;
import java.util.List;

/**
 * 单 Activity 两状态：WebView（加载远程链接）与 扫码/手动输入。
 * 启动默认加载上一次的链接；加载失败或手动取消 → 扫码状态。
 * 直连链接与官方 RC 链接通用：扫到什么加载什么，仅按 host 显示通道标签。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "kimi_remote";
    private static final String KEY_LAST_URL = "last_url";
    private static final String RC_HOST = "code-rc.kimi.com";
    private static final int REQ_CAMERA = 1;

    private FrameLayout webContainer;
    private View scanContainer;
    private View topBar;
    private WebView webView;
    /** window.open 打开的登录弹窗（覆盖层），关闭后置回 null */
    private WebView popupView;
    private ProgressBar progress;
    private BarcodeView barcodeView;
    private TextView cameraDenied;
    private TextView lastUrl;
    private EditText urlInput;

    private SharedPreferences prefs;
    private View bottomCard;
    /** WebView 文件选择回调（WebUI 的附件上传） */
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_FILE = 2;
    /** 页面亮暗轮询，用于状态栏/导航栏跟随页面主题 */
    private final Handler themeHandler = new Handler(Looper.getMainLooper());
    private Boolean lastPageLight = null;
    private int sysTop = 0;
    /** 当前页面是否自己处理顶部安全区（RC 页等 viewport-fit=cover 且无 --safe-top 变量的页面） */
    private boolean pageSelfManagedTop = false;
    /** 上次按返回键的时间，用于双击返回保护 */
    private long lastBackAt = 0;

    private final Runnable themePoller = new Runnable() {
        @Override
        public void run() {
            if (webContainer.getVisibility() == View.VISIBLE && popupView == null) {
                webView.evaluateJavascript(
                        "(function(){try{var c=getComputedStyle(document.body).backgroundColor;"
                                + "var m=c.match(/[\\d.]+/g);var light='';"
                                + "if(m&&m.length>=3){light=((0.299*+m[0]+0.587*+m[1]+0.114*+m[2])>140)?'1':'0'}"
                                + "var cover='0';var meta=document.querySelector('meta[name=viewport]');"
                                + "if(meta&&/viewport-fit\\s*=\\s*cover/i.test(meta.content))cover='1';"
                                // WebUI 的顶部安全区走 CSS 变量 --safe-top（env 运行时无法归零），
                                // 用 !important 把它清零，顶部留白统一交给原生 padding，
                                // 否则与原生留白叠加出双额头；文件改动浮层不用该变量，靠原生 padding 即可
                                + "var hasVar='0';"
                                + "if(getComputedStyle(document.documentElement).getPropertyValue('--safe-top').trim()!==''){"
                                + "hasVar='1';"
                                + "if(!document.getElementById('kr-safe-top-fix')){"
                                + "var s=document.createElement('style');s.id='kr-safe-top-fix';"
                                + "s.textContent=':root{--safe-top:0px !important}';"
                                + "document.head.appendChild(s)}}"
                                + "return light+','+cover+','+hasVar}catch(e){return ''}})()",
                        v -> {
                            if (v == null || v.length() < 3 || v.charAt(0) != '"') return;
                            String[] parts = v.substring(1, v.length() - 1).split(",");
                            if (parts.length != 3 || parts[0].isEmpty()) return;
                            applyPageStyle("1".equals(parts[0]),
                                    "1".equals(parts[1]) && !"1".equals(parts[2]));
                        });
            }
            themeHandler.postDelayed(this, 2000);
        }
    };

    /** 扫码页等自绘界面用：系统栏与背景跟随系统主题 */
    private void applySystemBars() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        lastPageLight = null; // 回 WebView 时让轮询重新判定
        pageSelfManagedTop = false;
        View root = findViewById(android.R.id.content);
        root.setBackgroundColor(night ? 0xFF16171A : Color.WHITE);
        root.setPadding(0, sysTop, 0, 0);
        WindowInsetsControllerCompat c =
                new WindowInsetsControllerCompat(getWindow(), webView);
        c.setAppearanceLightStatusBars(!night);
        c.setAppearanceLightNavigationBars(!night);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 系统切换明暗主题（uiMode 在 configChanges 里，Activity 不重建）
        if (scanContainer.getVisibility() == View.VISIBLE) {
            applySystemBars();
        }
    }

    /** 状态栏透明悬浮，图标颜色跟随页面；selfManaged 页面（RC 页）自己处理顶部安全区，其余由原生加留白 */
    private void applyPageStyle(boolean light, boolean selfManaged) {
        View root = findViewById(android.R.id.content);
        lastPageLight = light;
        pageSelfManagedTop = selfManaged;
        root.setBackgroundColor(light ? Color.WHITE : 0xFF16171A);
        root.setPadding(0, selfManaged ? 0 : sysTop, 0, 0);
        WindowInsetsControllerCompat c =
                new WindowInsetsControllerCompat(getWindow(), webView);
        c.setAppearanceLightStatusBars(light);
        c.setAppearanceLightNavigationBars(light);
    }

    /** edge-to-edge：状态栏/导航栏透明；顶部留白默认原生加，页面自管理时（RC 页）不加，底部延伸到手势条下 */
    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        // 关掉系统自动加的系统栏对比度遮罩，否则透明栏上仍有底色
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    sysTop = sys.top;
                    v.setPadding(0, pageSelfManagedTop ? 0 : sysTop, 0, 0);
                    float d = getResources().getDisplayMetrics().density;
                    bottomCard.setPadding((int) (20 * d), (int) (20 * d),
                            (int) (20 * d), (int) (20 * d) + sys.bottom);
                    return insets;
                });
    }

    /** 上传类型选择：自绘底部悬浮圆角卡片，不用系统默认 AlertDialog */
    private void showPickerDialog(boolean multiple) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_picker);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setWindowAnimations(R.style.PickerDialogAnim);
        }
        dialog.findViewById(R.id.pickImage).setOnClickListener(v -> {
            dialog.dismiss();
            launchPicker(true, multiple);
        });
        dialog.findViewById(R.id.pickFile).setOnClickListener(v -> {
            dialog.dismiss();
            launchPicker(false, multiple);
        });
        dialog.setOnCancelListener(d -> {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
            }
        });
        dialog.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        webContainer = findViewById(R.id.webContainer);
        scanContainer = findViewById(R.id.scanContainer);
        topBar = findViewById(R.id.topBar);
        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);
        barcodeView = findViewById(R.id.barcodeView);
        cameraDenied = findViewById(R.id.cameraDenied);
        lastUrl = findViewById(R.id.lastUrl);
        urlInput = findViewById(R.id.urlInput);
        bottomCard = findViewById(R.id.bottomCard);
        TextView btnCancel = findViewById(R.id.btnCancel);
        TextView btnConnect = findViewById(R.id.btnConnect);

        setupEdgeToEdge();
        setupWebView();

        barcodeView.setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                String text = result.getText();
                if (text != null && isValidUrl(text)) {
                    connect(text);
                }
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {
            }
        });

        btnCancel.setOnClickListener(v -> {
            webView.stopLoading();
            showScan();
        });
        btnConnect.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (isValidUrl(url)) {
                connect(url);
            } else {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
            }
        });
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            btnConnect.performClick();
            return true;
        });
        lastUrl.setOnClickListener(v -> connect(prefs.getString(KEY_LAST_URL, "")));

        themeHandler.post(themePoller);

        String last = prefs.getString(KEY_LAST_URL, null);
        if (last != null && isValidUrl(last)) {
            showWeb(last);
        } else {
            showScan();
        }
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 官方 RC 登录走 window.open 弹窗，默认 WebView 不渲染新窗口（白屏），
        // 开多窗口支持并在 onCreateWindow 里把弹窗拉回本 WebView
        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        WebView.setWebContentsDebuggingEnabled(true);
        // 官方 RC 页有登录流程，Cookie 默认即持久化，跨启动保留登录态
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 加载成功才记住，失败地址不留存
                prefs.edit().putString(KEY_LAST_URL, url).apply();
                // 加载完成后隐藏 App 顶栏，WebView 全屏沉浸
                topBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // 只关心主框架失败；子资源失败（图片等）不切换
                if (request.isForMainFrame()) {
                    showScan();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 所有 http(s) 导航都留在 WebView 内。
                // 曾把站外链接（OAuth 登录域）分流到系统浏览器，结果登录链被劈断，
                // WebView 内流程回调校验失败（failed_precondition）——登录必须全程同一上下文
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // WebUI 附件上传：先问传图片还是文件，再调对应选择器
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean multiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                showPickerDialog(multiple);
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                // RC 登录走 window.open：创建真实的覆盖层弹窗 WebView。
                // 弹窗与主 WebView 共享 CookieManager，登录态全局生效；
                // 登录成功页的「关闭页面」调 window.close() 会触发弹窗的 onCloseWindow
                final WebView popup = new WebView(view.getContext());
                popupView = popup;
                popup.getSettings().setJavaScriptEnabled(true);
                popup.getSettings().setDomStorageEnabled(true);
                popup.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                webContainer.addView(popup);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        // 登录流程跳回 RC 站 = 登录完成：主 WebView 接管，关闭弹窗
                        if (RC_HOST.equals(uri.getHost())) {
                            webContainer.removeView(popup);
                            popupView = null;
                            showWeb(uri.toString());
                            return true;
                        }
                        return false;
                    }
                });
                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView window) {
                        // 登录成功页 window.close()：只关弹窗。
                        // 主页面（opener）已通过 postMessage 收到登录令牌，
                        // 会自行切换到已登录态——重载反而会冲掉它刚收到的内存态
                        webContainer.removeView(popup);
                        popupView = null;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private void showWeb(String url) {
        barcodeView.pause();
        scanContainer.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        webView.loadUrl(url);
    }

    private void showScan() {
        webView.stopLoading();
        webContainer.setVisibility(View.GONE);
        scanContainer.setVisibility(View.VISIBLE);
        // 扫码页是 App 自绘界面，系统栏跟随系统主题（而非页面轮询）
        applySystemBars();

        String last = prefs.getString(KEY_LAST_URL, null);
        if (last != null && !last.isEmpty()) {
            String channel = isRcUrl(last)
                    ? getString(R.string.channel_rc)
                    : getString(R.string.channel_direct);
            lastUrl.setText(getString(R.string.last_url_fmt, channel, last));
            lastUrl.setVisibility(View.VISIBLE);
        } else {
            lastUrl.setVisibility(View.GONE);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            barcodeView.setVisibility(View.VISIBLE);
            cameraDenied.setVisibility(View.GONE);
            barcodeView.resume();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            barcodeView.setVisibility(View.VISIBLE);
            cameraDenied.setVisibility(View.GONE);
            barcodeView.resume();
        } else {
            barcodeView.setVisibility(View.GONE);
            cameraDenied.setVisibility(View.VISIBLE);
        }
    }

    private void connect(String url) {
        prefs.edit().putString(KEY_LAST_URL, url).apply();
        showWeb(url);
    }

    /** 图片用 image 通配、文件用全类型通配，交系统选择器 */
    private void launchPicker(boolean image, boolean multiple) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(image ? "image/*" : "*/*");
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_FILE);
        } catch (Exception e) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;
        if (fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    private boolean isValidUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRcUrl(String url) {
        Uri uri = Uri.parse(url);
        return RC_HOST.equals(uri.getHost());
    }

    @Override
    public void onBackPressed() {
        if (popupView != null) {
            // 登录弹窗优先关弹窗
            webContainer.removeView(popupView);
            popupView = null;
            return;
        }
        if (webContainer.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webContainer.getVisibility() == View.VISIBLE) {
            // 双击保护：第一次只提示，2 秒内再按才退回连接页，防误触断连
            long now = System.currentTimeMillis();
            if (now - lastBackAt < 2000) {
                lastBackAt = 0;
                showScan();
            } else {
                lastBackAt = now;
                Toast.makeText(this, R.string.back_hint, Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        if (scanContainer.getVisibility() == View.VISIBLE
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            barcodeView.resume();
        }
    }

    @Override
    protected void onPause() {
        barcodeView.pause();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        themeHandler.removeCallbacks(themePoller);
        super.onDestroy();
    }
}
