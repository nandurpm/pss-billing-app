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
    private static final int PDF_W = 595;
    private static final int PDF_H = 842;
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
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PDF_W, PDF_H, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            drawBillAtPdfScale(page.getCanvas(), root, 1f);
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
            int imageWidth = 1080;
            int imageHeight = Math.round(imageWidth * (PDF_H / (float) PDF_W));
            Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawBillAtPdfScale(canvas, root, imageWidth / (float) PDF_W);

            String imageName = cleanFileName("Purple_Signature_" + bill.optString("invoiceNo", "Bill") + ".jpg");
            Uri uri = saveImageToMediaStore(bitmap, imageName);
            if (uri == null) throw new Exception("Cannot create bill image");

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

    private Uri saveImageToMediaStore(Bitmap bitmap, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Purple Signature");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Cannot create image");
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("Cannot write image");
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } else {
            String saved = MediaStore.Images.Media.insertImage(getContentResolver(), bitmap, fileName, "Purple Signature bill");
            if (saved == null) return null;
            return Uri.parse(saved);
        }
    }

    private void drawBillAtPdfScale(Canvas canvas, JSONObject root, float scale) throws Exception {
        canvas.save();
        canvas.scale(scale, scale);
        drawBillBase(canvas, root);
        canvas.restore();
    }

    private void drawBillBase(Canvas canvas, JSONObject root) throws Exception {
        JSONObject bill = root.getJSONObject("bill");
        JSONObject shop = root.getJSONObject("settings");
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, PDF_W, PDF_H, paint);

        float margin = 32f;
        Bitmap banner = loadBannerBitmap();
        RectF bannerRect = new RectF(margin, 20f, PDF_W - margin, 140f);
        if (banner != null) {
            drawBitmapCrop(canvas, banner, bannerRect, paint);
        } else {
            paint.setColor(Color.rgb(42, 6, 60));
            canvas.drawRect(bannerRect, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(24f);
            paint.setFakeBoldText(true);
            canvas.drawText("Purple Signature Salon", margin + 10f, 78f, paint);
            paint.setFakeBoldText(false);
        }

        float y = 178f;
        paint.setColor(Color.rgb(36, 17, 47));
        paint.setTextSize(13f);
        paint.setFakeBoldText(true);
        canvas.drawText("Invoice", margin, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(bill.optString("invoiceNo", ""), margin + 70f, y, paint);
        paint.setFakeBoldText(true);
        canvas.drawText("Date", 390f, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(bill.optString("billDate", ""), 438f, y, paint);

        y += 28f;
        paint.setFakeBoldText(true);
        canvas.drawText("Customer", margin, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("customer", "Walk-in Customer"), 36), margin + 82f, y, paint);
        paint.setFakeBoldText(true);
        canvas.drawText("Mobile", 390f, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("mobile", ""), 18), 438f, y, paint);

        y += 42f;
        float tableLeft = margin;
        float tableRight = PDF_W - margin;
        float itemX = tableLeft + 8f;
        float qtyX = 300f;
        float rateX = 365f;
        float totalX = 465f;
        float rowH = 28f;

        paint.setColor(Color.rgb(42, 6, 60));
        canvas.drawRect(tableLeft, y - 20f, tableRight, y + 8f, paint);
        paint.setColor(Color.WHITE);
        paint.setFakeBoldText(true);
        paint.setTextSize(12f);
        canvas.drawText("Item", itemX, y, paint);
        canvas.drawText("Qty", qtyX, y, paint);
        canvas.drawText("Rate", rateX, y, paint);
        canvas.drawText("Total", totalX, y, paint);
        paint.setFakeBoldText(false);

        y += 32f;
        JSONArray itemArray = bill.optJSONArray("items");
        int maxRows = 12;
        if (itemArray != null && itemArray.length() > 0) {
            for (int i = 0; i < itemArray.length() && i < maxRows; i++) {
                JSONObject item = itemArray.getJSONObject(i);
                paint.setColor(i % 2 == 0 ? Color.rgb(251, 246, 255) : Color.WHITE);
                canvas.drawRect(tableLeft, y - 18f, tableRight, y + 8f, paint);
                paint.setColor(Color.rgb(36, 17, 47));
                paint.setTextSize(12f);
                String name = limit(item.optString("name", "Item"), 32);
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
                paint.setTextSize(11f);
                canvas.drawText("More items available in saved bill view...", itemX, y, paint);
                y += rowH;
            }
        } else {
            paint.setColor(Color.rgb(36, 17, 47));
            canvas.drawText("No items added", itemX, y, paint);
            y += rowH;
        }

        y += 24f;
        float summaryW = 235f;
        float summaryX = PDF_W - margin - summaryW;
        drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Subtotal", formatMoney(bill.optDouble("subtotal", 0)), false); y += 30f;
        drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Discount", formatMoney(bill.optDouble("discount", 0)), false); y += 30f;
        double tax = bill.optDouble("tax", 0);
        if (tax > 0.001) {
            drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Tax " + tax + "%", formatMoney(bill.optDouble("taxAmount", 0)), false);
            y += 30f;
        }
        drawSummaryRow(canvas, paint, summaryX, y, summaryW, "Grand Total", formatMoney(bill.optDouble("grand", 0)), true);

        float bottomY = 712f;
        paint.setColor(Color.rgb(36, 17, 47));
        paint.setTextSize(12f);
        paint.setFakeBoldText(true);
        canvas.drawText("Payment:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(bill.optString("payment", "Cash"), margin + 70f, bottomY, paint);
        bottomY += 26f;
        paint.setFakeBoldText(true);
        canvas.drawText("Staff:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("staff", "-"), 35), margin + 70f, bottomY, paint);
        bottomY += 26f;
        paint.setFakeBoldText(true);
        canvas.drawText("Notes:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("notes", "-"), 64), margin + 70f, bottomY, paint);

        Bitmap qrBitmap = decodeDataUri(shop.optString("qr", ""));
        if (qrBitmap != null) {
            RectF qrRect = new RectF(margin, 770f, margin + 62f, 832f);
            canvas.drawBitmap(qrBitmap, null, qrRect, paint);
            paint.setTextSize(8f);
            canvas.drawText("Scan to pay", margin, 839f, paint);
        }

        paint.setTextSize(12f);
        paint.setColor(Color.rgb(115, 92, 122));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Thank you. Visit again.", PDF_W / 2f, 824f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSummaryRow(Canvas canvas, Paint paint, float x, float y, float width, String label, String value, boolean grand) {
        paint.setColor(grand ? Color.rgb(123, 36, 143) : Color.rgb(251, 246, 255));
        canvas.drawRect(x, y - 18f, x + width, y + 8f, paint);
        paint.setColor(grand ? Color.WHITE : Color.rgb(36, 17, 47));
        paint.setTextSize(grand ? 13f : 12f);
        paint.setFakeBoldText(grand);
        canvas.drawText(label, x + 8f, y, paint);
        canvas.drawText(value, x + width - 118f, y, paint);
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
