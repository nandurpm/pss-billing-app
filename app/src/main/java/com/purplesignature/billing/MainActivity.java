package com.purplesignature.billing;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.util.Iterator;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PDF_WIDTH = 595;
    private static final int PDF_HEIGHT = 842;

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
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new PdfBridge(), "NativePdf");
        webView.addJavascriptInterface(new ShareBridge(), "NativeShare");
        webView.addJavascriptInterface(new ReportBridge(), "NativeReport");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    public class PdfBridge {
        @JavascriptInterface
        public void savePdf(final String payloadJson) {
            runOnUiThread(() -> saveBillPdfAndOpenPrint(payloadJson));
        }
    }

    public class ShareBridge {
        @JavascriptInterface
        public void shareBill(final String payloadJson) {
            runOnUiThread(() -> shareBillImage(payloadJson));
        }
    }

    public class ReportBridge {
        @JavascriptInterface
        public void saveReport(final String reportJson) {
            runOnUiThread(() -> saveReportPdfAndOpenPrint(reportJson));
        }
    }

    private void saveBillPdfAndOpenPrint(String payloadJson) {
        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject bill = root.getJSONObject("bill");
            String fileName = cleanFileName("Purple_Signature_" + bill.optString("invoiceNo", "Bill") + ".pdf");
            byte[] pdfBytes = buildBillPdfBytes(root);
            savePdfToDownloads(pdfBytes, fileName, "Purple Signature");
            openPrintScreen(pdfBytes, fileName);
            Toast.makeText(this, "PDF saved. Print screen opened.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "PDF failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveReportPdfAndOpenPrint(String reportJson) {
        try {
            JSONObject report = new JSONObject(reportJson);
            String fileName = cleanFileName("PSS_Report_" + report.optString("from", "start") + "_to_" + report.optString("to", "end") + ".pdf");
            byte[] pdfBytes = buildReportPdfBytes(report);
            savePdfToDownloads(pdfBytes, fileName, "Purple Signature/Reports");
            openPrintScreen(pdfBytes, fileName);
            Toast.makeText(this, "Report PDF saved. Print screen opened.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Report PDF failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private byte[] buildBillPdfBytes(JSONObject root) throws Exception {
        PdfDocument document = new PdfDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, 1).create());
            drawBill(page.getCanvas(), root, PDF_WIDTH, PDF_HEIGHT);
            document.finishPage(page);
            document.writeTo(output);
            return output.toByteArray();
        } finally {
            document.close();
            output.close();
        }
    }

    private byte[] buildReportPdfBytes(JSONObject report) throws Exception {
        PdfDocument document = new PdfDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            drawReportSummaryPage(document, report, 1);
            drawReportServicePage(document, report, 2);
            drawReportPeriodPage(document, report, 3);
            document.writeTo(output);
            return output.toByteArray();
        } finally {
            document.close();
            output.close();
        }
    }

    private void drawReportSummaryPage(PdfDocument document, JSONObject report, int pageNumber) throws Exception {
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create());
        Canvas canvas = page.getCanvas();
        Paint paint = whitePage(canvas);
        float y = drawReportHeader(canvas, paint, report, "Summary & Staff Report");

        JSONObject summary = report.optJSONObject("summary");
        if (summary == null) summary = new JSONObject();
        String[][] cards = new String[][]{
                {"Total Bills", String.valueOf(summary.optInt("bills", 0))},
                {"Revenue", formatMoney(summary.optDouble("revenue", 0))},
                {"Cash", formatMoney(summary.optDouble("cash", 0))},
                {"UPI / GPay", formatMoney(summary.optDouble("upi", 0))},
                {"Card", formatMoney(summary.optDouble("card", 0))},
                {"Credit", formatMoney(summary.optDouble("credit", 0))},
                {"Services", formatQty(summary.optDouble("services", 0))},
                {"Average Bill", formatMoney(summary.optDouble("average", 0))}
        };

        for (int index = 0; index < cards.length; index++) {
            int column = index % 2;
            int row = index / 2;
            float x = 32 + column * 265;
            float top = y + row * 55;
            paint.setColor(Color.rgb(250, 245, 252));
            canvas.drawRoundRect(new RectF(x, top, x + 245, top + 43), 8, 8, paint);
            paint.setColor(Color.rgb(115, 92, 122));
            paint.setTextSize(10);
            canvas.drawText(cards[index][0], x + 10, top + 15, paint);
            paint.setColor(Color.rgb(95, 23, 110));
            paint.setFakeBoldText(true);
            paint.setTextSize(15);
            canvas.drawText(cards[index][1], x + 10, top + 34, paint);
            paint.setFakeBoldText(false);
        }

        y += 235;
        y = drawSectionTitle(canvas, paint, "Staff Work Report", y);
        float[] widths = {80, 42, 55, 75, 65, 65, 110};
        y = drawTableHeader(canvas, paint, new String[]{"Staff", "Bills", "Services", "Revenue", "Cash", "UPI", "Top Service"}, widths, y);
        JSONArray rows = report.optJSONArray("staffRows");
        if (rows != null) {
            for (int index = 0; index < rows.length() && index < 18; index++) {
                JSONObject row = rows.getJSONObject(index);
                y = drawTableRow(canvas, paint, new String[]{
                        row.optString("staff", "-"),
                        String.valueOf(row.optInt("bills", 0)),
                        formatQty(row.optDouble("services", 0)),
                        formatMoney(row.optDouble("revenue", 0)),
                        formatMoney(row.optDouble("cash", 0)),
                        formatMoney(row.optDouble("upi", 0)),
                        limit(row.optString("topService", "-"), 18)
                }, widths, y, index % 2 == 0);
            }
        }

        drawPageFooter(canvas, paint, pageNumber);
        document.finishPage(page);
    }

    private void drawReportServicePage(PdfDocument document, JSONObject report, int pageNumber) throws Exception {
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create());
        Canvas canvas = page.getCanvas();
        Paint paint = whitePage(canvas);
        float y = drawReportHeader(canvas, paint, report, "Service Analysis");

        float[] widths = {165, 48, 48, 80, 190};
        y = drawTableHeader(canvas, paint, new String[]{"Service", "Count", "Qty", "Revenue", "Staff Breakdown"}, widths, y);
        JSONArray rows = report.optJSONArray("serviceRows");
        if (rows != null) {
            for (int index = 0; index < rows.length() && index < 24; index++) {
                JSONObject row = rows.getJSONObject(index);
                String breakdown = row.optString("staffBreakdown", buildStaffBreakdown(row.optJSONObject("staffCounts")));
                y = drawTableRow(canvas, paint, new String[]{
                        limit(row.optString("service", "-"), 27),
                        String.valueOf(row.optInt("count", 0)),
                        formatQty(row.optDouble("qty", 0)),
                        formatMoney(row.optDouble("revenue", 0)),
                        limit(breakdown, 35)
                }, widths, y, index % 2 == 0);
            }
        }

        drawPageFooter(canvas, paint, pageNumber);
        document.finishPage(page);
    }

    private void drawReportPeriodPage(PdfDocument document, JSONObject report, int pageNumber) throws Exception {
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create());
        Canvas canvas = page.getCanvas();
        Paint paint = whitePage(canvas);
        float y = drawReportHeader(canvas, paint, report, "Daily & Weekly Analysis");

        y = drawSectionTitle(canvas, paint, "Daily Analysis", y);
        float[] dailyWidths = {75, 40, 50, 65, 65, 55, 55, 75};
        y = drawTableHeader(canvas, paint, new String[]{"Date", "Bills", "Services", "Cash", "UPI", "Card", "Credit", "Total"}, dailyWidths, y);
        JSONArray dailyRows = report.optJSONArray("dailyRows");
        if (dailyRows != null) {
            for (int index = 0; index < dailyRows.length() && index < 14; index++) {
                JSONObject row = dailyRows.getJSONObject(index);
                y = drawTableRow(canvas, paint, new String[]{
                        row.optString("date", ""),
                        String.valueOf(row.optInt("bills", 0)),
                        formatQty(row.optDouble("services", 0)),
                        formatMoney(row.optDouble("cash", 0)),
                        formatMoney(row.optDouble("upi", 0)),
                        formatMoney(row.optDouble("card", 0)),
                        formatMoney(row.optDouble("credit", 0)),
                        formatMoney(row.optDouble("total", 0))
                }, dailyWidths, y, index % 2 == 0);
            }
        }

        y += 18;
        y = drawSectionTitle(canvas, paint, "Weekly Analysis", y);
        float[] weeklyWidths = {135, 42, 55, 65, 65, 55, 55, 60};
        y = drawTableHeader(canvas, paint, new String[]{"Week", "Bills", "Services", "Cash", "UPI", "Card", "Credit", "Total"}, weeklyWidths, y);
        JSONArray weeklyRows = report.optJSONArray("weeklyRows");
        if (weeklyRows != null) {
            for (int index = 0; index < weeklyRows.length() && index < 8; index++) {
                JSONObject row = weeklyRows.getJSONObject(index);
                y = drawTableRow(canvas, paint, new String[]{
                        limit(row.optString("week", ""), 22),
                        String.valueOf(row.optInt("bills", 0)),
                        formatQty(row.optDouble("services", 0)),
                        formatMoney(row.optDouble("cash", 0)),
                        formatMoney(row.optDouble("upi", 0)),
                        formatMoney(row.optDouble("card", 0)),
                        formatMoney(row.optDouble("credit", 0)),
                        formatMoney(row.optDouble("total", 0))
                }, weeklyWidths, y, index % 2 == 0);
            }
        }

        drawPageFooter(canvas, paint, pageNumber);
        document.finishPage(page);
    }

    private String buildStaffBreakdown(JSONObject counts) {
        if (counts == null) return "-";
        StringBuilder builder = new StringBuilder();
        Iterator<String> keys = counts.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (builder.length() > 0) builder.append(", ");
            builder.append(key).append(": ").append(formatQty(counts.optDouble(key, 0)));
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private Paint whitePage(Canvas canvas) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, PDF_WIDTH, PDF_HEIGHT, paint);
        return paint;
    }

    private float drawReportHeader(Canvas canvas, Paint paint, JSONObject report, String subtitle) {
        Bitmap banner = loadBannerBitmap();
        RectF bannerRect = new RectF(32, 20, 563, 118);
        if (banner != null) {
            drawBitmapFill(canvas, banner, bannerRect, paint);
        } else {
            paint.setColor(Color.rgb(42, 6, 60));
            canvas.drawRect(bannerRect, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(23);
            paint.setFakeBoldText(true);
            canvas.drawText("Purple Signature Salon", 48, 72, paint);
            paint.setFakeBoldText(false);
        }

        paint.setColor(Color.rgb(95, 23, 110));
        paint.setTextSize(18);
        paint.setFakeBoldText(true);
        canvas.drawText(subtitle, 32, 148, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(11);
        paint.setColor(Color.rgb(90, 72, 98));
        canvas.drawText("Date: " + report.optString("from", "") + " to " + report.optString("to", ""), 32, 167, paint);
        canvas.drawText("Staff: " + report.optString("staff", "ALL") + "   Payment: " + report.optString("payment", "ALL"), 310, 167, paint);
        return 184;
    }

    private float drawSectionTitle(Canvas canvas, Paint paint, String title, float y) {
        paint.setColor(Color.rgb(42, 6, 60));
        paint.setTextSize(14);
        paint.setFakeBoldText(true);
        canvas.drawText(title, 32, y, paint);
        paint.setFakeBoldText(false);
        return y + 18;
    }

    private float drawTableHeader(Canvas canvas, Paint paint, String[] headers, float[] widths, float y) {
        float x = 32;
        paint.setColor(Color.rgb(42, 6, 60));
        canvas.drawRect(32, y, 563, y + 24, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(8.5f);
        paint.setFakeBoldText(true);
        for (int index = 0; index < headers.length; index++) {
            canvas.drawText(headers[index], x + 4, y + 16, paint);
            x += widths[index];
        }
        paint.setFakeBoldText(false);
        return y + 24;
    }

    private float drawTableRow(Canvas canvas, Paint paint, String[] values, float[] widths, float y, boolean shaded) {
        if (shaded) {
            paint.setColor(Color.rgb(250, 245, 252));
            canvas.drawRect(32, y, 563, y + 23, paint);
        }
        float x = 32;
        paint.setColor(Color.rgb(36, 17, 47));
        paint.setTextSize(8.2f);
        for (int index = 0; index < values.length; index++) {
            int maxCharacters = Math.max(4, (int) (widths[index] / 5.2f));
            canvas.drawText(limit(values[index], maxCharacters), x + 4, y + 15, paint);
            x += widths[index];
        }
        return y + 23;
    }

    private void drawPageFooter(Canvas canvas, Paint paint, int pageNumber) {
        paint.setColor(Color.rgb(115, 92, 122));
        paint.setTextSize(10);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Purple Signature Salon · Page " + pageNumber, PDF_WIDTH / 2f, PDF_HEIGHT - 18, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void savePdfToDownloads(byte[] bytes, String fileName, String relativeFolder) throws Exception {
        OutputStream output = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeFolder);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("Cannot create PDF file");
                output = getContentResolver().openOutputStream(uri);
                if (output == null) throw new Exception("Cannot open PDF file");
                output.write(bytes);
                output.flush();
                output.close();
                output = null;
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativeFolder);
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create Downloads folder");
                output = new FileOutputStream(new File(directory, fileName));
                output.write(bytes);
                output.flush();
                output.close();
                output = null;
            }
        } finally {
            if (output != null) output.close();
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
        } catch (Exception error) {
            Toast.makeText(this, "Print screen failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancellationSignal, WriteResultCallback callback) {
            FileOutputStream output = null;
            try {
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                output = new FileOutputStream(destination.getFileDescriptor());
                output.write(pdfBytes);
                output.flush();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception error) {
                callback.onWriteFailed(error.getMessage());
            } finally {
                try { if (output != null) output.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void shareBillImage(String payloadJson) {
        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject bill = root.getJSONObject("bill");
            String mobile = normalizeIndianMobile(bill.optString("mobile", ""));
            if (mobile.isEmpty()) throw new Exception("Enter a valid 10-digit customer mobile number");
            bill.put("mobile", mobile);

            Bitmap bitmap = Bitmap.createBitmap(1080, 1528, Bitmap.Config.ARGB_8888);
            drawBill(new Canvas(bitmap), root, 1080, 1528);
            String imageName = cleanFileName("Purple_Signature_" + bill.optString("invoiceNo", "Bill") + ".jpg");
            Uri uri = saveImageToMediaStore(bitmap, imageName);
            if (uri == null) throw new Exception("Cannot create bill image");

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra("jid", "91" + mobile + "@s.whatsapp.net");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setPackage("com.whatsapp");

            try {
                startActivity(intent);
            } catch (ActivityNotFoundException first) {
                intent.setPackage("com.whatsapp.w4b");
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException second) {
                    intent.removeExtra("jid");
                    intent.setPackage(null);
                    startActivity(Intent.createChooser(intent, "Share bill image"));
                }
            }
        } catch (Exception error) {
            Toast.makeText(this, "Share failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
            OutputStream output = getContentResolver().openOutputStream(uri);
            if (output == null) throw new Exception("Cannot write image");
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output);
            output.flush();
            output.close();
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        }
        String saved = MediaStore.Images.Media.insertImage(getContentResolver(), bitmap, fileName, "Purple Signature bill");
        return saved == null ? null : Uri.parse(saved);
    }

    private void drawBill(Canvas canvas, JSONObject root, int pageWidth, int pageHeight) throws Exception {
        JSONObject bill = root.getJSONObject("bill");
        JSONObject shop = root.getJSONObject("settings");
        float scale = pageWidth / 595f;
        float margin = 32f * scale;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, pageWidth, pageHeight, paint);

        Bitmap banner = loadBannerBitmap();
        float bannerTop = 20f * scale;
        float bannerHeight = 120f * scale;
        RectF bannerRect = new RectF(margin, bannerTop, pageWidth - margin, bannerTop + bannerHeight);
        if (banner != null) {
            drawBitmapFill(canvas, banner, bannerRect, paint);
        } else {
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
        canvas.drawText(limit(bill.optString("customer", "Walk-in Customer"), 36), margin + 82f * scale, y, paint);
        paint.setFakeBoldText(true);
        canvas.drawText("Mobile", pageWidth - margin - 180f * scale, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("mobile", ""), 18), pageWidth - margin - 130f * scale, y, paint);

        y += 25f * scale;
        paint.setFakeBoldText(true);
        canvas.drawText("Staff", margin, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("staff", "-"), 24), margin + 82f * scale, y, paint);
        paint.setFakeBoldText(true);
        canvas.drawText("Payment", pageWidth - margin - 180f * scale, y, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(paymentLabelNative(bill.optString("payment", "CASH")), 18), pageWidth - margin - 120f * scale, y, paint);

        y += 40f * scale;
        float tableLeft = margin;
        float tableRight = pageWidth - margin;
        float itemX = tableLeft + 8f * scale;
        float qtyX = tableLeft + 268f * scale;
        float rateX = tableLeft + 333f * scale;
        float totalX = tableLeft + 433f * scale;
        float rowHeight = 28f * scale;

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
        if (itemArray != null && itemArray.length() > 0) {
            for (int index = 0; index < itemArray.length() && index < 14; index++) {
                JSONObject item = itemArray.getJSONObject(index);
                paint.setColor(index % 2 == 0 ? Color.rgb(251, 246, 255) : Color.WHITE);
                canvas.drawRect(tableLeft, y - 18f * scale, tableRight, y + 8f * scale, paint);
                paint.setColor(Color.rgb(36, 17, 47));
                paint.setTextSize(12f * scale);
                double quantity = item.optDouble("qty", 1);
                double rate = item.optDouble("rate", 0);
                canvas.drawText(limit(item.optString("name", "Item"), 32), itemX, y, paint);
                canvas.drawText(formatQty(quantity), qtyX, y, paint);
                canvas.drawText(formatMoney(rate), rateX, y, paint);
                canvas.drawText(formatMoney(quantity * rate), totalX, y, paint);
                y += rowHeight;
            }
        } else {
            canvas.drawText("No items added", itemX, y, paint);
            y += rowHeight;
        }

        y += 24f * scale;
        float summaryWidth = 235f * scale;
        float summaryX = pageWidth - margin - summaryWidth;
        drawSummaryRow(canvas, paint, summaryX, y, summaryWidth, "Subtotal", formatMoney(bill.optDouble("subtotal", 0)), false, scale);
        y += 30f * scale;
        drawSummaryRow(canvas, paint, summaryX, y, summaryWidth, "Discount", formatMoney(bill.optDouble("discount", 0)), false, scale);
        y += 30f * scale;
        double tax = bill.optDouble("tax", 0);
        if (shop.optBoolean("gstEnabled", false) && tax > 0.001) {
            drawSummaryRow(canvas, paint, summaryX, y, summaryWidth, "GST " + tax + "%", formatMoney(bill.optDouble("taxAmount", 0)), false, scale);
            y += 30f * scale;
        }
        drawSummaryRow(canvas, paint, summaryX, y, summaryWidth, "Grand Total", formatMoney(bill.optDouble("grand", 0)), true, scale);

        float bottomY = pageHeight - 105f * scale;
        paint.setColor(Color.rgb(36, 17, 47));
        paint.setTextSize(12f * scale);
        paint.setFakeBoldText(true);
        canvas.drawText("Notes:", margin, bottomY, paint);
        paint.setFakeBoldText(false);
        canvas.drawText(limit(bill.optString("notes", "-"), 64), margin + 55f * scale, bottomY, paint);

        Bitmap qrBitmap = decodeDataUri(shop.optString("qr", ""));
        if (qrBitmap != null) {
            float qrSize = 75f * scale;
            RectF qrRect = new RectF(margin, pageHeight - margin - qrSize, margin + qrSize, pageHeight - margin);
            canvas.drawBitmap(qrBitmap, null, qrRect, paint);
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
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            input.close();
            String svg = new String(output.toByteArray(), StandardCharsets.UTF_8);
            int start = svg.indexOf("base64,");
            if (start < 0) return null;
            start += 7;
            int end = svg.indexOf('"', start);
            if (end < 0) return null;
            byte[] bytes = Base64.decode(svg.substring(start, end), Base64.DEFAULT);
            cachedBannerBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            return cachedBannerBitmap;
        } catch (Exception error) {
            return null;
        }
    }

    private void drawBitmapFill(Canvas canvas, Bitmap bitmap, RectF destination, Paint paint) {
        float sourceRatio = bitmap.getWidth() / (float) bitmap.getHeight();
        float destinationRatio = destination.width() / destination.height();
        Rect source;
        if (sourceRatio > destinationRatio) {
            int sourceWidth = Math.round(bitmap.getHeight() * destinationRatio);
            int left = (bitmap.getWidth() - sourceWidth) / 2;
            source = new Rect(left, 0, left + sourceWidth, bitmap.getHeight());
        } else {
            int sourceHeight = Math.round(bitmap.getWidth() / destinationRatio);
            int top = (bitmap.getHeight() - sourceHeight) / 2;
            source = new Rect(0, top, bitmap.getWidth(), top + sourceHeight);
        }
        canvas.drawBitmap(bitmap, source, destination, paint);
    }

    private Bitmap decodeDataUri(String dataUri) {
        try {
            if (dataUri == null || !dataUri.startsWith("data:image")) return null;
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception error) {
            return null;
        }
    }

    private String normalizeIndianMobile(String mobile) {
        String digits = mobile == null ? "" : mobile.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        return digits.matches("[0-9]{10}") ? digits : "";
    }

    private String paymentLabelNative(String payment) {
        if ("UPI".equals(payment)) return "UPI / GPay";
        if ("CARD".equals(payment)) return "Card";
        if ("CREDIT".equals(payment)) return "Credit";
        return "Cash";
    }

    private String formatMoney(double value) {
        return "Rs. " + String.format(Locale.US, "%.2f", value);
    }

    private String formatQty(double value) {
        return Math.abs(value - Math.round(value)) < 0.001
                ? String.valueOf((int) Math.round(value))
                : String.format(Locale.US, "%.2f", value);
    }

    private String limit(String text, int max) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    private String cleanFileName(String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
