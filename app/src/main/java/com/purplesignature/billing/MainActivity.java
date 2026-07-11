package com.purplesignature.billing;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
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
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String FILE_PROVIDER_AUTHORITY = "com.purplesignature.billing.fileprovider";
    private WebView webView;
    private Bitmap cachedBannerBitmap;

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
        webView.addJavascriptInterface(new ShareBridge(), "NativeShare");
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
                    savePdfAndOpenPrint(payloadJson);
                }
            });
        }
    }

    public class ShareBridge {
        @JavascriptInterface
        public void shareBill(final String payloadJson) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    shareBillImage(payloadJson);
                }
            });
        }
    }

    private void savePdfAndOpenPrint(String payloadJson) {
        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject bill = root.getJSONObject("bill");
            String fileName = cleanFileName("Purple_Signature_" + bill.optString("invoiceNo", "Bill") + ".pdf");
            byte[] pdfBytes = buildPdfBytes(root);
            savePdfToDownloads(pdfBytes, fileName);
            openPrintScreen(pdfBytes, fileName);
            Toast.makeText(this, "PDF saved. Print screen opened.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "PDF failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private byte[] buildPdfBytes(JSONObject root) throws Exception {
        PdfDocument document = new PdfDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            int pageWidth = 595;
            int pageHeight = 842;
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            drawBill(page.getCanvas(), root, pageWidth, pageHeight, false);
            document.finishPage(page);
            document.writeTo(out);
            return out.toByteArray();
        } finally {
            document.close();
            out.close();
        }
    }

    private void savePdfToDownloads(byte[] pdfBytes, String fileName) throws Exception {
        OutputStream outputStream = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Purple Signature");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("Cannot create PDF file");
                outputStream = resolver.openOutputStream(uri);
                if (outputStream == null) throw new Exception("Cannot open PDF file");
                outputStream.write(pdfBytes);
                outputStream.flush();
                outputStream.close();
                outputStream = null;
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Purple Signature");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create Downloads folder");
                File file = new File(dir, fileName);
                outputStream = new FileOutputStream(file);
                outputStream.write(pdfBytes);
                outputStream.flush();
                outputStream.close();
                outputStream = null;
            }
        } finally {
            if (outputStream != null) outputStream.close();
        }
    }

    private void openPrintScreen(byte[] pdfBytes, String fileName) {
        try {
            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (printManager == null) {
                Toast.makeText(this, "Print service not available", Toast.LENGTH_LONG).show();
                return;
            }
            PrintAttributes attributes = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();
            printManager.print(fileName, new PdfBytesPrintAdapter(pdfBytes, fileName), attributes);
        } catch (Exception e) {
            Toast.makeText(this, "Print screen failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private class PdfBytesPrintAdapter extends PrintDocumentAdapter {
        private final byte[] pdfBytes;
        private final String fileName;

        PdfBytesPrintAdapter(byte[] pdfBytes, String fileName) {
            this.pdfBytes = pdfBytes;
            this.fileName = fileName;
        }

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(fileName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancellationSignal, WriteResultCallback callback) {
            FileOutputStream out = null;
            try {
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                out = new FileOutputStream(destination.getFileDescriptor());
                out.write(pdfBytes);
                out.flush();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                callback.onWriteFailed(e.getMessage());
            } finally {
                try {
                    if (out != null) out.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private void shareBillImage(String payloadJson) {
        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject bill = root.getJSONObject("bill");
            Bitmap bitmap = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawBill(canvas, root, 1080, 1500, true);

            File shareDir = new File(getCacheDir(), "shared_bills");
            if (!shareDir.exists() && !shareDir.mkdirs()) throw new Exception("Cannot create share cache");
            File imageFile = new File(shareDir, cleanFileName("Bill_" + bill.optString("invoiceNo", "Bill") + ".jpg"));
            FileOutputStream out = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();

            Uri uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, imageFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            String jid = whatsappJid(bill.optString("mobile", ""));
            if (!jid.isEmpty()) intent.putExtra("jid", jid);
            intent.setPackage("com.whatsapp");

            try {
                startActivity(intent);
            } catch (ActivityNotFoundException first) {
                intent.setPackage("com.whatsapp.w4b");
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException second) {
                    intent.setPackage(null);
                    startActivity(Intent.createChooser(intent, "Share bill image"));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void drawBill(Canvas canvas, JSONObject root, int pageWidth, int pageHeight, boolean imageMode) throws Exception {
        JSONObject bill = root.getJSONObject("bill");
        JSONObject shop = root.getJSONObject("settings");
        float scale = imageMode ? (pageWidth / 595f) : 1f;
        float margin = imageMode ? 54f : 32f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, pageWidth, pageHeight, paint);

        Bitmap banner = loadBannerBitmap();
        float bannerTop = imageMode ? 32f : 20f;
        float bannerHeight = imageMode ? 260f : 120f;
        RectF bannerRect = new RectF(margin, bannerTop, pageWidth - margin, bannerTop + bannerHeight);
        if (banner != null) drawBitmapCrop(canvas, banner, bannerRect, paint);
        else {
            paint.setColor(Color.rgb(42, 6, 60));
            canvas.drawRect(bannerRect, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(24f * scale);
            paint.setFakeBoldText(true);
            canvas.drawText("Purple Signature Salon", margin + 10f * scale, bannerTop + 56f * scale, paint);
            paint.setFakeBoldText(false);
        }

        float y = bannerRect.bottom + 30f * scale;
        paint.setColor(Color.rgb(36, 17, 47));
        paint.setTextSize(13f * scale);
        paint.setFakeBoldText(true);
        canvas.drawText("Invoice", margin, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(bill.optString("invoiceNo", ""), margin + 70f * scale, y, paint);
        paint.setFakeBoldText(true);
        canvas.drawText("Date", pageWidth - margin - 180f * scale, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(bill.optString("billDate", ""), pageWidth - margin - 130f * scale, y, paint);

        y += 28f * scale;
        paint.setFakeBoldText(true);
        canvas.drawText("Customer", margin, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("customer", "Walk-in Customer"), imageMode ? 32 : 36), margin + 82f * scale, y, paint);
        paint.setFakeBoldText(true);
        canvas.drawText("Mobile", pageWidth - margin - 180f * scale, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("mobile", ""), 18), pageWidth - margin - 130f * scale, y, paint);

        y += 40f * scale;
        float tableLeft = margin;
        float tableRight = pageWidth - margin;
        float itemX = tableLeft + 8f * scale;
        float qtyX = tableLeft + (imageMode ? 555f : 268f) * scale;
        float rateX = tableLeft + (imageMode ? 700f : 333f) * scale;
        float totalX = tableLeft + (imageMode ? 870f : 433f) * scale;
        float rowH = 28f * scale;

        paint.setColor(Color.rgb(42, 6, 60));
        canvas.drawRect(tableLeft, y - 20f * scale, tableRight, y + 8f * scale, paint);
        paint.setColor(Color.WHITE);
        paint.setFakeBoldText(true);
        paint.setTextSize(12f * scale);
        canvas.drawText("Item", itemX, y, paint);
        canvas.drawText("Qty", qtyX, y, paint);
        canvas.drawText("Rate", rateX, y, paint);
        canvas.drawText("Total", totalX, y, paint);
        paint.setFakeBoldText(false);

        y += 32f * scale;
        JSONArray itemArray = bill.optJSONArray("items");
        int maxRows = imageMode ? 18 : 12;
        if (itemArray != null && itemArray.length() > 0) {
            for (int i = 0; i < itemArray.length() && i < maxRows; i++) {
                JSONObject item = itemArray.getJSONObject(i);
                paint.setColor(i % 2 == 0 ? Color.rgb(251, 246, 255) : Color.WHITE);
                canvas.drawRect(tableLeft, y - 18f * scale, tableRight, y + 8f * scale, paint);
                paint.setColor(Color.rgb(36, 17, 47));
                paint.setTextSize(12f * scale);
                String name = limit(item.optString("name", "Item"), imageMode ? 35 : 32);
                double qty = item.optDouble("qty", 1);
                double rate = item.optDouble("rate", 0);
                double total = qty * rate;
                canvas.drawText(name, itemX, y, paint);
                canvas.drawText(formatQty(qty), qtyX, y, paint);
                canvas.drawText(formatMoney(rate), rateX, y, paint);
                canvas.drawText(formatMoney(total), totalX, y, paint);
                y += rowH;
            }
            if (itemArray.length() > maxRows) {
                paint.setTextSize(11f * scale);
                canvas.drawText("More items available in saved bill view...", itemX, y, paint);
                y += rowH;
            }
        } else {
            paint.setColor(Color.rgb(36, 17, 47));
            canvas.drawText("No items added", itemX, y, paint);
            y += rowH;
        }

        y += 24f * scale;
        float summaryW = imageMode ? 430f : 235f;
        float summaryX = pageWidth - margin - summaryW;
        drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Subtotal", formatMoney(bill.optDouble("subtotal", 0)), false, scale); y += 30f * scale;
        drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Discount", formatMoney(bill.optDouble("discount", 0)), false, scale); y += 30f * scale;
        double tax = bill.optDouble("tax", 0);
        if (tax > 0.001) {
            drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Tax " + tax + "%", formatMoney(bill.optDouble("taxAmount", 0)), false, scale);
            y += 30f * scale;
        }
        drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Grand Total", formatMoney(bill.optDouble("grand", 0)), true, scale);

        float bottomY = imageMode ? pageHeight - 210f : pageHeight - 130f;
        paint.setColor(Color.rgb(36, 17, 47));
        paint.setTextSize(12f * scale);
        paint.setFakeBoldText(true);
        canvas.drawText("Payment:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(bill.optString("payment", "Cash"), margin + 70f * scale, bottomY, paint);
        bottomY += 26f * scale;
        paint.setFakeBoldText(true);
        canvas.drawText("Staff:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("staff", "-"), 35), margin + 70f * scale, bottomY, paint);
        bottomY += 26f * scale;
        paint.setFakeBoldText(true);
        canvas.drawText("Notes:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("notes", "-"), imageMode ? 58 : 64), margin + 70f * scale, bottomY, paint);

        Bitmap qrBitmap = decodeDataUri(shop.optString("qr", ""));
        if (qrBitmap != null) {
            float qrSize = imageMode ? 150f : 75f;
            RectF qrRect = new RectF(margin, pageHeight - margin - qrSize, margin + qrSize, pageHeight - margin);
            canvas.drawBitmap(qrBitmap, null, qrRect, paint);
            paint.setTextSize(10f * scale);
            canvas.drawText("Scan to pay", margin, pageHeight - margin + (imageMode ? 24f : 12f), paint);
        }

        paint.setTextSize(12f * scale);
        paint.setColor(Color.rgb(115, 92, 122));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Thank you. Visit again.", pageWidth / 2f, pageHeight - margin, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSummaryRow(Canvas canvas, Paint paint, float x, float y, float width, String label, String value, boolean grand, float scale) {
        paint.setColor(grand ? Color.rgb(123, 36, 143) : Color.rgb(251, 246, 255));
        canvas.drawRect(x, y - 18f * scale, x + width, y + 8f * scale, paint);
        paint.setColor(grand ? Color.WHITE : Color.rgb(36, 17, 47));
        paint.setTextSize((grand ? 13f : 12f) * scale);
        paint.setFakeBoldText(grand);
        canvas.drawText(label, x + 8f * scale, y, paint);
        canvas.drawText(value, x + width - 122f * scale, y, paint);
        paint.setFakeBoldText(false);
    }

    private Bitmap loadBannerBitmap() {
        if (cachedBannerBitmap != null) return cachedBannerBitmap;
        try {
            InputStream input = getAssets().open("www/banner.svg");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            input.close();
            String svg = new String(out.toByteArray(), StandardCharsets.UTF_8);
            int start = svg.indexOf("base64,");
            if (start < 0) return null;
            start += 7;
            int end = svg.indexOf('"', start);
            if (end < 0) return null;
            byte[] bytes = Base64.decode(svg.substring(start, end), Base64.DEFAULT);
            cachedBannerBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            return cachedBannerBitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void drawBitmapCrop(Canvas canvas, Bitmap bitmap, RectF dst, Paint paint) {
        float srcRatio = bitmap.getWidth() / (float) bitmap.getHeight();
        float dstRatio = dst.width() / dst.height();
        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        int left = 0;
        int top = 0;
        if (srcRatio > dstRatio) {
            srcW = Math.round(srcH * dstRatio);
            left = (bitmap.getWidth() - srcW) / 2;
        } else {
            srcH = Math.round(srcW / dstRatio);
            top = (bitmap.getHeight() - srcH) / 2;
        }
        android.graphics.Rect src = new android.graphics.Rect(left, top, left + srcW, top + srcH);
        canvas.drawBitmap(bitmap, src, dst, paint);
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

    private String whatsappJid(String mobile) {
        if (mobile == null) return "";
        String digits = mobile.replaceAll("[^0-9]", "");
        if (digits.length() == 10) digits = "91" + digits;
        if (digits.length() < 11) return "";
        return digits + "@s.whatsapp.net";
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
