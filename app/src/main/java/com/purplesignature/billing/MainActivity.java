package com.purplesignature.billing;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;

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

        webView.addJavascriptInterface(new PdfBridge(), "NativePdf");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    public class PdfBridge {
        @JavascriptInterface
        public void savePdf(final String payloadJson) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    createAndSavePdf(payloadJson);
                }
            });
        }
    }

    private void createAndSavePdf(String payloadJson) {
        PdfDocument document = null;
        OutputStream outputStream = null;
        Uri savedUri = null;
        String fileName = "Purple_Signature_Bill.pdf";

        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject bill = root.getJSONObject("bill");
            JSONObject shop = root.getJSONObject("settings");
            fileName = cleanFileName("Purple_Signature_" + bill.optString("invoiceNo", "Bill") + ".pdf");

            document = new PdfDocument();
            int pageWidth = 595;
            int pageHeight = 842;
            int margin = 32;

            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            paint.setColor(Color.WHITE);
            canvas.drawRect(0, 0, pageWidth, pageHeight, paint);

            paint.setColor(Color.rgb(42, 6, 60));
            canvas.drawRect(0, 0, pageWidth, 96, paint);
            paint.setColor(Color.rgb(183, 138, 53));
            canvas.drawRect(0, 96, pageWidth, 102, paint);

            paint.setFakeBoldText(true);
            paint.setTextSize(25);
            paint.setColor(Color.WHITE);
            canvas.drawText(limit(shop.optString("businessName", "Purple Signature Salon"), 34), margin, 40, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(13);
            canvas.drawText(limit(shop.optString("tagline", "Skin · Hair · Bridal · Nails"), 60), margin, 64, paint);
            canvas.drawText(limit(shop.optString("phone", ""), 45), margin, 82, paint);

            paint.setColor(Color.rgb(36, 17, 47));
            paint.setTextSize(13);
            paint.setFakeBoldText(true);
            canvas.drawText("Invoice", margin, 132, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(bill.optString("invoiceNo", ""), margin + 70, 132, paint);
            paint.setFakeBoldText(true);
            canvas.drawText("Date", 360, 132, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(bill.optString("billDate", ""), 410, 132, paint);

            paint.setFakeBoldText(true);
            canvas.drawText("Customer", margin, 158, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(limit(bill.optString("customer", "Walk-in Customer"), 36), margin + 78, 158, paint);
            paint.setFakeBoldText(true);
            canvas.drawText("Mobile", 360, 158, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(limit(bill.optString("mobile", ""), 18), 410, 158, paint);

            int y = 196;
            paint.setColor(Color.rgb(42, 6, 60));
            canvas.drawRect(margin, y - 18, pageWidth - margin, y + 8, paint);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(12);
            canvas.drawText("Item", margin + 8, y, paint);
            canvas.drawText("Qty", 300, y, paint);
            canvas.drawText("Rate", 365, y, paint);
            canvas.drawText("Total", 465, y, paint);

            y += 30;
            paint.setColor(Color.rgb(36, 17, 47));
            paint.setFakeBoldText(false);
            JSONArray itemArray = bill.optJSONArray("items");
            if (itemArray != null && itemArray.length() > 0) {
                for (int i = 0; i < itemArray.length(); i++) {
                    JSONObject item = itemArray.getJSONObject(i);
                    if (y > 610) {
                        paint.setTextSize(11);
                        canvas.drawText("More items available in app bill view...", margin + 8, y, paint);
                        y += 22;
                        break;
                    }
                    String name = limit(item.optString("name", "Item"), 32);
                    double qty = item.optDouble("qty", 1);
                    double rate = item.optDouble("rate", 0);
                    double total = qty * rate;

                    paint.setColor(Color.rgb(234, 221, 238));
                    canvas.drawRect(margin, y - 17, pageWidth - margin, y + 8, paint);
                    paint.setColor(Color.rgb(36, 17, 47));
                    paint.setTextSize(12);
                    canvas.drawText(name, margin + 8, y, paint);
                    canvas.drawText(formatQty(qty), 300, y, paint);
                    canvas.drawText(formatMoney(rate), 365, y, paint);
                    canvas.drawText(formatMoney(total), 465, y, paint);
                    y += 28;
                }
            } else {
                canvas.drawText("No items added", margin + 8, y, paint);
                y += 28;
            }

            int boxX = 330;
            int boxW = pageWidth - margin - boxX;
            int sy = Math.max(y + 18, 640);
            drawSummaryRow(canvas, paint, boxX, sy, boxW, "Subtotal", formatMoney(bill.optDouble("subtotal", 0)), false); sy += 28;
            drawSummaryRow(canvas, paint, boxX, sy, boxW, "Discount", formatMoney(bill.optDouble("discount", 0)), false); sy += 28;
            drawSummaryRow(canvas, paint, boxX, sy, boxW, "Tax " + bill.optDouble("tax", 0) + "%", formatMoney(bill.optDouble("taxAmount", 0)), false); sy += 28;
            drawSummaryRow(canvas, paint, boxX, sy, boxW, "Grand Total", formatMoney(bill.optDouble("grand", 0)), true);

            paint.setColor(Color.rgb(36, 17, 47));
            paint.setTextSize(12);
            paint.setFakeBoldText(true);
            canvas.drawText("Payment:", margin, 665, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(bill.optString("payment", "Cash"), margin + 62, 665, paint);
            paint.setFakeBoldText(true);
            canvas.drawText("Staff:", margin, 690, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(limit(bill.optString("staff", "-"), 35), margin + 62, 690, paint);
            paint.setFakeBoldText(true);
            canvas.drawText("Notes:", margin, 715, paint);
            paint.setFakeBoldText(false);
            canvas.drawText(limit(bill.optString("notes", "-"), 64), margin + 62, 715, paint);

            Bitmap qrBitmap = decodeDataUri(shop.optString("qr", ""));
            if (qrBitmap != null) {
                RectF qrRect = new RectF(margin, 735, margin + 75, 810);
                canvas.drawBitmap(qrBitmap, null, qrRect, paint);
                paint.setTextSize(10);
                canvas.drawText("Scan to pay", margin, 825, paint);
            }

            paint.setTextSize(12);
            paint.setColor(Color.rgb(115, 92, 122));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Thank you. Visit again.", pageWidth / 2f, 824, paint);
            paint.setTextAlign(Paint.Align.LEFT);

            document.finishPage(page);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Purple Signature");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                savedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (savedUri == null) throw new Exception("Cannot create PDF file");
                outputStream = resolver.openOutputStream(savedUri);
                if (outputStream == null) throw new Exception("Cannot open PDF output stream");
                document.writeTo(outputStream);
                outputStream.flush();
                outputStream.close();
                outputStream = null;
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(savedUri, values, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Purple Signature");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create Downloads folder");
                File file = new File(dir, fileName);
                outputStream = new FileOutputStream(file);
                document.writeTo(outputStream);
                outputStream.flush();
                outputStream.close();
                outputStream = null;
            }

            Toast.makeText(this, "PDF saved in Downloads/Purple Signature", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "PDF failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            try {
                if (outputStream != null) outputStream.close();
            } catch (Exception ignored) {}
            if (document != null) document.close();
        }
    }

    private void drawSummaryRow(Canvas canvas, Paint paint, int x, int y, int width, String label, String value, boolean grand) {
        if (grand) paint.setColor(Color.rgb(123, 36, 143));
        else paint.setColor(Color.rgb(251, 246, 255));
        canvas.drawRect(x, y - 18, x + width, y + 8, paint);
        paint.setColor(grand ? Color.WHITE : Color.rgb(36, 17, 47));
        paint.setTextSize(grand ? 13 : 12);
        paint.setFakeBoldText(grand);
        canvas.drawText(label, x + 8, y, paint);
        canvas.drawText(value, x + width - 95, y, paint);
        paint.setFakeBoldText(false);
    }

    private Bitmap decodeDataUri(String dataUri) {
        try {
            if (dataUri == null || !dataUri.startsWith("data:image")) return null;
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatMoney(double value) {
        return "Rs. " + String.format(Locale.US, "%.2f", value);
    }

    private String formatQty(double value) {
        if (Math.abs(value - Math.round(value)) < 0.001) return String.valueOf((int) Math.round(value));
        return String.format(Locale.US, "%.2f", value);
    }

    private String limit(String text, int max) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    private String cleanFileName(String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
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
