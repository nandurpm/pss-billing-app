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
import java.util.Locale;

public class MainActivity extends Activity {
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
        } catch (Exception e) {
            Toast.makeText(this, "PDF failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveReportPdfAndOpenPrint(String reportJson) {
        try {
            JSONObject report = new JSONObject(reportJson);
            String from = report.optString("from", "start");
            String to = report.optString("to", "end");
            String fileName = cleanFileName("PSS_Report_" + from + "_to_" + to + ".pdf");
            byte[] pdfBytes = buildReportPdfBytes(report);
            savePdfToDownloads(pdfBytes, fileName, "Purple Signature/Reports");
            openPrintScreen(pdfBytes, fileName);
            Toast.makeText(this, "Report PDF saved. Print screen opened.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Report PDF failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private byte[] buildBillPdfBytes(JSONObject root) throws Exception {
        PdfDocument document = new PdfDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            drawBill(page.getCanvas(), root, 595, 842);
            document.finishPage(page);
            document.writeTo(out);
            return out.toByteArray();
        } finally {
            document.close();
            out.close();
        }
    }

    private byte[] buildReportPdfBytes(JSONObject report) throws Exception {
        PdfDocument document = new PdfDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            drawReportSummaryPage(document, report, 1);
            drawReportDetailPage(document, report, 2);
            document.writeTo(out);
            return out.toByteArray();
        } finally {
            document.close();
            out.close();
        }
    }

    private void drawReportSummaryPage(PdfDocument document, JSONObject report, int pageNo) throws Exception {
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo).create());
        Canvas canvas = page.getCanvas();
        Paint paint = basePage(canvas, 595, 842);
        float y = drawReportHeader(canvas, paint, report, "Summary & Staff Report");
        JSONObject summary = report.optJSONObject("summary");
        if (summary == null) summary = new JSONObject();
        String[][] cards = new String[][]{{"Total Bills", String.valueOf(summary.optInt("bills", 0))},{"Revenue", formatMoney(summary.optDouble("revenue", 0))},{"Cash", formatMoney(summary.optDouble("cash", 0))},{"UPI / GPay", formatMoney(summary.optDouble("upi", 0))},{"Card", formatMoney(summary.optDouble("card", 0))},{"Credit", formatMoney(summary.optDouble("credit", 0))},{"Services", String.valueOf(summary.optInt("services", 0))},{"Average Bill", formatMoney(summary.optDouble("average", 0))}};
        for (int i = 0; i < cards.length; i++) {
            int col = i % 2;
            int row = i / 2;
            float x = 32 + col * 265;
            float top = y + row * 55;
            paint.setColor(Color.rgb(250, 245, 252));
            canvas.drawRoundRect(new RectF(x, top, x + 245, top + 43), 8, 8, paint);
            paint.setColor(Color.rgb(115, 92, 122));
            paint.setTextSize(10);
            canvas.drawText(cards[i][0], x + 10, top + 15, paint);
            paint.setColor(Color.rgb(95, 23, 110));
            paint.setFakeBoldText(true);
            paint.setTextSize(15);
            canvas.drawText(cards[i][1], x + 10, top + 34, paint);
            paint.setFakeBoldText(false);
        }
        y += 235;
        y = drawSectionTitle(canvas, paint, "Staff Work Report", y);
        String[] headers = {"Staff", "Bills", "Services", "Revenue", "Cash", "UPI", "Top Service"};
        float[] widths = {80, 42, 55, 75, 65, 65, 110};
        y = drawTableHeader(canvas, paint, headers, widths, y);
        JSONArray rows = report.optJSONArray("staffRows");
        if (rows != null) {
            for (int i = 0; i < rows.length() && i < 10; i++) {
                JSONObject r = rows.getJSONObject(i);
                String[] values = {r.optString("staff", "-"),String.valueOf(r.optInt("bills", 0)),String.valueOf(r.optInt("services", 0)),formatMoney(r.optDouble("revenue", 0)),formatMoney(r.optDouble("cash", 0)),formatMoney(r.optDouble("upi", 0)),limit(r.optString("topService", "-"), 17)};
                y = drawTableRow(canvas, paint, values, widths, y, i % 2 == 0);
            }
        }
        drawPageFooter(canvas, paint, 595, 842, pageNo);
        document.finishPage(page);
    }

    private void drawReportDetailPage(PdfDocument document, JSONObject report, int pageNo) throws Exception {
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo).create());
        Canvas canvas = page.getCanvas();
        Paint paint = basePage(canvas, 595, 842);
        float y = drawReportHeader(canvas, paint, report, "Service, Daily & Weekly Analysis");
        y = drawSectionTitle(canvas, paint, "Service Analysis", y);
        float[] serviceWidths = {180, 55, 55, 85, 65, 65};
        y = drawTableHeader(canvas, paint, new String[]{"Service", "Count", "Qty", "Revenue", "ABITHA", "NIVEDA"}, serviceWidths, y);
        JSONArray serviceRows = report.optJSONArray("serviceRows");
        if (serviceRows != null) {
            for (int i = 0; i < serviceRows.length() && i < 10; i++) {
                JSONObject r = serviceRows.getJSONObject(i);
                y = drawTableRow(canvas, paint, new String[]{limit(r.optString("service", "-"), 28),String.valueOf(r.optInt("count", 0)),String.valueOf(r.optDouble("qty", 0)),formatMoney(r.optDouble("revenue", 0)),String.valueOf(r.optDouble("ABITHA", 0)),String.valueOf(r.optDouble("NIVEDA", 0))}, serviceWidths, y, i % 2 == 0);
            }
        }
        y += 18;
        y = drawSectionTitle(canvas, paint, "Daily Analysis", y);
        float[] dailyWidths = {75, 40, 50, 65, 65, 55, 55, 75};
        y = drawTableHeader(canvas, paint, new String[]{"Date", "Bills", "Services", "Cash", "UPI", "Card", "Credit", "Total"}, dailyWidths, y);
        JSONArray dailyRows = report.optJSONArray("dailyRows");
        if (dailyRows != null) {
            for (int i = 0; i < dailyRows.length() && i < 8; i++) {
                JSONObject r = dailyRows.getJSONObject(i);
                y = drawTableRow(canvas, paint, new String[]{r.optString("date", ""),String.valueOf(r.optInt("bills", 0)),String.valueOf(r.optInt("services", 0)),formatMoney(r.optDouble("cash", 0)),formatMoney(r.optDouble("upi", 0)),formatMoney(r.optDouble("card", 0)),formatMoney(r.optDouble("credit", 0)),formatMoney(r.optDouble("total", 0))}, dailyWidths, y, i % 2 == 0);
            }
        }
        y += 18;
        y = drawSectionTitle(canvas, paint, "Weekly Analysis", y);
        float[] weeklyWidths = {170, 55, 65, 80, 80, 90};
        y = drawTableHeader(canvas, paint, new String[]{"Week", "Bills", "Services", "Cash", "UPI", "Total"}, weeklyWidths, y);
        JSONArray weeklyRows = report.optJSONArray("weeklyRows");
        if (weeklyRows != null) {
            for (int i = 0; i < weeklyRows.length() && i < 5; i++) {
                JSONObject r = weeklyRows.getJSONObject(i);
                y = drawTableRow(canvas, paint, new String[]{limit(r.optString("week", ""), 25),String.valueOf(r.optInt("bills", 0)),String.valueOf(r.optInt("services", 0)),formatMoney(r.optDouble("cash", 0)),formatMoney(r.optDouble("upi", 0)),formatMoney(r.optDouble("total", 0))}, weeklyWidths, y, i % 2 == 0);
            }
        }
        drawPageFooter(canvas, paint, 595, 842, pageNo);
        document.finishPage(page);
    }

    private Paint basePage(Canvas canvas, int width, int height) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, width, height, paint);
        return paint;
    }

    private float drawReportHeader(Canvas canvas, Paint paint, JSONObject report, String subtitle) {
        Bitmap banner = loadBannerBitmap();
        RectF rect = new RectF(32, 20, 563, 118);
        if (banner != null) drawBitmapFit(canvas, banner, rect, paint);
        else {
            paint.setColor(Color.rgb(42, 6, 60));
            canvas.drawRect(rect, paint);
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
        paint.setTextSize(9);
        paint.setFakeBoldText(true);
        for (int i = 0; i < headers.length; i++) {
            canvas.drawText(headers[i], x + 4, y + 16, paint);
            x += widths[i];
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
        paint.setTextSize(8.5f);
        for (int i = 0; i < values.length; i++) {
            canvas.drawText(limit(values[i], Math.max(5, (int) (widths[i] / 5.5f))), x + 4, y + 15, paint);
            x += widths[i];
        }
        return y + 23;
    }

    private void drawPageFooter(Canvas canvas, Paint paint, int width, int height, int pageNo) {
        paint.setColor(Color.rgb(115, 92, 122));
        paint.setTextSize(10);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Purple Signature Salon · Page " + pageNo, width / 2f, height - 18, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void savePdfToDownloads(byte[] pdfBytes, String fileName, String relativeFolder) throws Exception {
        OutputStream outputStream = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeFolder);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("Cannot create PDF file");
                outputStream = getContentResolver().openOutputStream(uri);
                if (outputStream == null) throw new Exception("Cannot open PDF file");
                outputStream.write(pdfBytes);
                outputStream.flush();
                outputStream.close();
                outputStream = null;
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativeFolder);
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create Downloads folder");
                outputStream = new FileOutputStream(new File(dir, fileName));
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
            PrintAttributes attributes = new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setColorMode(PrintAttributes.COLOR_MODE_COLOR).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();
            printManager.print(fileName, new PdfBytesPrintAdapter(pdfBytes, fileName), attributes);
        } catch (Exception e) {
            Toast.makeText(this, "Print screen failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private class PdfBytesPrintAdapter extends PrintDocumentAdapter {
        private final byte[] pdfBytes;
        private final String fileName;
        PdfBytesPrintAdapter(byte[] pdfBytes, String fileName) { this.pdfBytes = pdfBytes; this.fileName = fileName; }
        @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal.isCanceled()) { callback.onLayoutCancelled(); return; }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(fileName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build();
            callback.onLayoutFinished(info, true);
        }
        @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancellationSignal, WriteResultCallback callback) {
            FileOutputStream out = null;
            try {
                if (cancellationSignal.isCanceled()) { callback.onWriteCancelled(); return; }
                out = new FileOutputStream(destination.getFileDescriptor());
                out.write(pdfBytes);
                out.flush();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) { callback.onWriteFailed(e.getMessage()); }
            finally { try { if (out != null) out.close(); } catch (Exception ignored) {} }
        }
    }

    private void shareBillImage(String payloadJson) {
        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject bill = root.getJSONObject("bill");
            Bitmap bitmap = Bitmap.createBitmap(1080, 1528, Bitmap.Config.ARGB_8888);
            drawBill(new Canvas(bitmap), root, 1080, 1528);
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
            try { startActivity(intent); }
            catch (ActivityNotFoundException first) {
                intent.setPackage("com.whatsapp.w4b");
                try { startActivity(intent); }
                catch (ActivityNotFoundException second) { intent.setPackage(null); startActivity(Intent.createChooser(intent, "Share bill image")); }
            }
        } catch (Exception e) { Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
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
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out);
            out.flush(); out.close();
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
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
        paint.setColor(Color.WHITE); canvas.drawRect(0, 0, pageWidth, pageHeight, paint);
        Bitmap banner = loadBannerBitmap();
        float bannerTop = 20f * scale, bannerHeight = 120f * scale;
        RectF bannerRect = new RectF(margin, bannerTop, pageWidth - margin, bannerTop + bannerHeight);
        if (banner != null) drawBitmapFit(canvas, banner, bannerRect, paint);
        else { paint.setColor(Color.rgb(42,6,60)); canvas.drawRect(bannerRect, paint); paint.setColor(Color.WHITE); paint.setTextSize(24f*scale); paint.setFakeBoldText(true); canvas.drawText("Purple Signature Salon", margin+10f*scale, bannerTop+56f*scale, paint); paint.setFakeBoldText(false); }
        float y = bannerRect.bottom + 30f * scale;
        paint.setColor(Color.rgb(36,17,47)); paint.setTextSize(13f*scale);
        paint.setFakeBoldText(true); canvas.drawText("Invoice", margin, y, paint); paint.setFakeBoldText(false); canvas.drawText(bill.optString("invoiceNo", ""), margin+70f*scale, y, paint);
        paint.setFakeBoldText(true); canvas.drawText("Date", pageWidth-margin-180f*scale, y, paint); paint.setFakeBoldText(false); canvas.drawText(bill.optString("billDate", ""), pageWidth-margin-130f*scale, y, paint);
        y += 28f*scale;
        paint.setFakeBoldText(true); canvas.drawText("Customer", margin, y, paint); paint.setFakeBoldText(false); canvas.drawText(limit(bill.optString("customer", "Walk-in Customer"),36), margin+82f*scale, y, paint);
        paint.setFakeBoldText(true); canvas.drawText("Mobile", pageWidth-margin-180f*scale, y, paint); paint.setFakeBoldText(false); canvas.drawText(limit(bill.optString("mobile", ""),18), pageWidth-margin-130f*scale, y, paint);
        y += 25f*scale;
        paint.setFakeBoldText(true); canvas.drawText("Staff", margin, y, paint); paint.setFakeBoldText(false); canvas.drawText(limit(bill.optString("staff", "-"),24), margin+82f*scale, y, paint);
        paint.setFakeBoldText(true); canvas.drawText("Payment", pageWidth-margin-180f*scale, y, paint); paint.setFakeBoldText(false); canvas.drawText(limit(paymentLabelNative(bill.optString("payment", "CASH")),18), pageWidth-margin-120f*scale, y, paint);
        y += 40f*scale;
        float tableLeft=margin,tableRight=pageWidth-margin,itemX=tableLeft+8f*scale,qtyX=tableLeft+268f*scale,rateX=tableLeft+333f*scale,totalX=tableLeft+433f*scale,rowH=28f*scale;
        paint.setColor(Color.rgb(42,6,60)); canvas.drawRect(tableLeft,y-20f*scale,tableRight,y+8f*scale,paint);
        paint.setColor(Color.WHITE); paint.setFakeBoldText(true); paint.setTextSize(12f*scale); canvas.drawText("Item",itemX,y,paint); canvas.drawText("Qty",qtyX,y,paint); canvas.drawText("Rate",rateX,y,paint); canvas.drawText("Total",totalX,y,paint); paint.setFakeBoldText(false); y+=32f*scale;
        JSONArray itemArray=bill.optJSONArray("items");
        if(itemArray!=null&&itemArray.length()>0){for(int i=0;i<itemArray.length()&&i<12;i++){JSONObject item=itemArray.getJSONObject(i);paint.setColor(i%2==0?Color.rgb(251,246,255):Color.WHITE);canvas.drawRect(tableLeft,y-18f*scale,tableRight,y+8f*scale,paint);paint.setColor(Color.rgb(36,17,47));paint.setTextSize(12f*scale);double qty=item.optDouble("qty",1),rate=item.optDouble("rate",0);canvas.drawText(limit(item.optString("name","Item"),32),itemX,y,paint);canvas.drawText(formatQty(qty),qtyX,y,paint);canvas.drawText(formatMoney(rate),rateX,y,paint);canvas.drawText(formatMoney(qty*rate),totalX,y,paint);y+=rowH;}}else{canvas.drawText("No items added",itemX,y,paint);y+=rowH;}
        y+=24f*scale;float summaryW=235f*scale,summaryX=pageWidth-margin-summaryW;drawSummaryRow(canvas,paint,summaryX,y,summaryW,"Subtotal",formatMoney(bill.optDouble("subtotal",0)),false,scale);y+=30f*scale;drawSummaryRow(canvas,paint,summaryX,y,summaryW,"Discount",formatMoney(bill.optDouble("discount",0)),false,scale);y+=30f*scale;double tax=bill.optDouble("tax",0);if(shop.optBoolean("gstEnabled",false)&&tax>0.001){drawSummaryRow(canvas,paint,summaryX,y,summaryW,"GST "+tax+"%",formatMoney(bill.optDouble("taxAmount",0)),false,scale);y+=30f*scale;}drawSummaryRow(canvas,paint,summaryX,y,summaryW,"Grand Total",formatMoney(bill.optDouble("grand",0)),true,scale);
        float bottomY=pageHeight-105f*scale;paint.setColor(Color.rgb(36,17,47));paint.setTextSize(12f*scale);paint.setFakeBoldText(true);canvas.drawText("Notes:",margin,bottomY,paint);paint.setFakeBoldText(false);canvas.drawText(limit(bill.optString("notes","-"),64),margin+55f*scale,bottomY,paint);
        Bitmap qrBitmap=decodeDataUri(shop.optString("qr",""));if(qrBitmap!=null){float qrSize=75f*scale;RectF qrRect=new RectF(margin,pageHeight-margin-qrSize,margin+qrSize,pageHeight-margin);canvas.drawBitmap(qrBitmap,null,qrRect,paint);}paint.setTextSize(12f*scale);paint.setColor(Color.rgb(115,92,122));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("Thank you. Visit again.",pageWidth/2f,pageHeight-margin,paint);paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSummaryRow(Canvas canvas, Paint paint, float x, float y, float width, String label, String value, boolean grand, float scale) { paint.setColor(grand?Color.rgb(123,36,143):Color.rgb(251,246,255));canvas.drawRect(x,y-18f*scale,x+width,y+8f*scale,paint);paint.setColor(grand?Color.WHITE:Color.rgb(36,17,47));paint.setTextSize((grand?13f:12f)*scale);paint.setFakeBoldText(grand);canvas.drawText(label,x+8f*scale,y,paint);canvas.drawText(value,x+width-122f*scale,y,paint);paint.setFakeBoldText(false); }
    private Bitmap loadBannerBitmap(){if(cachedBannerBitmap!=null)return cachedBannerBitmap;try{InputStream input=getAssets().open("www/banner.svg");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int read;while((read=input.read(buffer))!=-1)out.write(buffer,0,read);input.close();String svg=new String(out.toByteArray(),StandardCharsets.UTF_8);int start=svg.indexOf("base64,");if(start<0)return null;start+=7;int end=svg.indexOf('"',start);if(end<0)return null;byte[] bytes=Base64.decode(svg.substring(start,end),Base64.DEFAULT);cachedBannerBitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);return cachedBannerBitmap;}catch(Exception e){return null;}}
    private void drawBitmapFit(Canvas canvas,Bitmap bitmap,RectF dst,Paint paint){float srcRatio=bitmap.getWidth()/(float)bitmap.getHeight(),dstRatio=dst.width()/dst.height();Rect src;if(srcRatio>dstRatio){int srcW=Math.round(bitmap.getHeight()*dstRatio),left=(bitmap.getWidth()-srcW)/2;src=new Rect(left,0,left+srcW,bitmap.getHeight());}else{int srcH=Math.round(bitmap.getWidth()/dstRatio),top=(bitmap.getHeight()-srcH)/2;src=new Rect(0,top,bitmap.getWidth(),top+srcH);}canvas.drawBitmap(bitmap,src,dst,paint);}
    private Bitmap decodeDataUri(String dataUri){try{if(dataUri==null||!dataUri.startsWith("data:image"))return null;int comma=dataUri.indexOf(',');if(comma<0)return null;byte[] bytes=Base64.decode(dataUri.substring(comma+1),Base64.DEFAULT);return BitmapFactory.decodeByteArray(bytes,0,bytes.length);}catch(Exception e){return null;}}
    private String whatsappJid(String mobile){String digits=mobile==null?"":mobile.replaceAll("[^0-9]","");if(digits.length()==10)digits="91"+digits;return digits.length()<11?"":digits+"@s.whatsapp.net";}
    private String paymentLabelNative(String payment){if("UPI".equals(payment))return "UPI / GPay";if("CARD".equals(payment))return "Card";if("CREDIT".equals(payment))return "Credit";return "Cash";}
    private String formatMoney(double value){return "Rs. "+String.format(Locale.US,"%.2f",value);}private String formatQty(double value){return Math.abs(value-Math.round(value))<0.001?String.valueOf((int)Math.round(value)):String.format(Locale.US,"%.2f",value);}private String limit(String text,int max){if(text==null)return "";String clean=text.replace('\n',' ').replace('\r',' ').trim();return clean.length()<=max?clean:clean.substring(0,Math.max(0,max-3))+"...";}private String cleanFileName(String text){return text.replaceAll("[^A-Za-z0-9._-]","_");}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
}
