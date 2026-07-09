package com.purplesignature.billing;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private WebView printWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#2A063C"));
        window.setNavigationBarColor(Color.parseColor("#170021"));

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new PrintBridge(), "NativePrint");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void printHtmlDocument(String html) {
        if (html == null || html.trim().isEmpty()) {
            Toast.makeText(this, "No bill content to print", Toast.LENGTH_SHORT).show();
            return;
        }

        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(this, "Print service not available on this phone", Toast.LENGTH_LONG).show();
            return;
        }

        printWebView = new WebView(this);
        WebSettings printSettings = printWebView.getSettings();
        printSettings.setJavaScriptEnabled(false);
        printSettings.setLoadWithOverviewMode(true);
        printSettings.setUseWideViewPort(true);

        printWebView.setWebViewClient(new WebViewClient() {
            private boolean printed = false;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (printed) return;
                printed = true;
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String jobName = "Purple_Signature_Bill_" + System.currentTimeMillis();
                            PrintDocumentAdapter adapter = printWebView.createPrintDocumentAdapter(jobName);
                            PrintAttributes attributes = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                    .build();
                            printManager.print(jobName, adapter, attributes);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Print failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }, 500);
            }
        });

        printWebView.loadDataWithBaseURL("file:///android_asset/www/", html, "text/html", "UTF-8", null);
    }

    public class PrintBridge {
        @JavascriptInterface
        public void printHtml(final String html) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    printHtmlDocument(html);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
