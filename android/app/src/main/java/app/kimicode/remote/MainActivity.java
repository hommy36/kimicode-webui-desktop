package app.kimicode.remote;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    private View webContainer;
    private View scanContainer;
    private WebView webView;
    private ProgressBar progress;
    private BarcodeView barcodeView;
    private TextView cameraDenied;
    private TextView lastUrl;
    private EditText urlInput;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        webContainer = findViewById(R.id.webContainer);
        scanContainer = findViewById(R.id.scanContainer);
        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);
        barcodeView = findViewById(R.id.barcodeView);
        cameraDenied = findViewById(R.id.cameraDenied);
        lastUrl = findViewById(R.id.lastUrl);
        urlInput = findViewById(R.id.urlInput);
        Button btnCancel = findViewById(R.id.btnCancel);
        Button btnConnect = findViewById(R.id.btnConnect);

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
        WebView.setWebContentsDebuggingEnabled(true);
        // 官方 RC 页有登录流程，Cookie 默认即持久化，跨启动保留登录态
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 加载成功才记住，失败地址不留存
                prefs.edit().putString(KEY_LAST_URL, url).apply();
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // 只关心主框架失败；子资源失败（图片等）不切换
                if (request.isForMainFrame()) {
                    showScan();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void showWeb(String url) {
        barcodeView.pause();
        scanContainer.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        webView.loadUrl(url);
    }

    private void showScan() {
        webView.stopLoading();
        webContainer.setVisibility(View.GONE);
        scanContainer.setVisibility(View.VISIBLE);

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
        if (webContainer.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webContainer.getVisibility() == View.VISIBLE) {
            showScan();
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
}
