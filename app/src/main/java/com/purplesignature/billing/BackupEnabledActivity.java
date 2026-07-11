package com.purplesignature.billing;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class BackupEnabledActivity extends MainActivity {
    private static final int REQUEST_IMPORT_BACKUP = 4401;
    private static final int MAX_BACKUP_BYTES = 50 * 1024 * 1024;

    private WebView backupWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backupWebView = findWebView(findViewById(android.R.id.content));
        if (backupWebView == null) {
            Toast.makeText(this, "Backup setup failed: WebView not found", Toast.LENGTH_LONG).show();
            return;
        }

        backupWebView.addJavascriptInterface(new NativeBackupBridge(), "NativeBackup");
        backupWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNativeBackupController();
            }
        });

        installNativeBackupController();
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView found = findWebView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    public class NativeBackupBridge {
        @JavascriptInterface
        public void exportBackup(final String json, final String requestedFileName) {
            runOnUiThread(() -> saveBackupJson(json, requestedFileName));
        }

        @JavascriptInterface
        public void openImportPicker() {
            runOnUiThread(BackupEnabledActivity.this::openBackupPicker);
        }
    }

    private void saveBackupJson(String json, String requestedFileName) {
        OutputStream output = null;
        try {
            JSONObject parsed = new JSONObject(json);
            if (!parsed.has("bills") || !parsed.has("settings")) {
                throw new Exception("Backup data is incomplete");
            }

            String fileName = cleanBackupFileName(requestedFileName);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Purple Signature/Backups");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("Cannot create backup file");

                output = getContentResolver().openOutputStream(uri);
                if (output == null) throw new Exception("Cannot open backup file");
                output.write(bytes);
                output.flush();
                output.close();
                output = null;

                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            } else {
                File directory = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "Purple Signature/Backups"
                );
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new Exception("Cannot create backup folder");
                }
                output = new FileOutputStream(new File(directory, fileName));
                output.write(bytes);
                output.flush();
                output.close();
                output = null;
            }

            notifyExportFinished(true, "Backup saved in Downloads/Purple Signature/Backups");
            Toast.makeText(this, "Backup exported successfully", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            notifyExportFinished(false, "Backup export failed: " + error.getMessage());
            Toast.makeText(this, "Backup export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            try {
                if (output != null) output.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void openBackupPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/json",
                    "text/json",
                    "text/plain",
                    "application/octet-stream"
            });
            startActivityForResult(Intent.createChooser(intent, "Select Purple Signature backup"), REQUEST_IMPORT_BACKUP);
        } catch (Exception error) {
            Toast.makeText(this, "Cannot open backup picker: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_IMPORT_BACKUP) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            notifyImportCancelled();
            return;
        }

        Uri uri = data.getData();
        try {
            String json = readBackupText(uri);
            JSONObject parsed = new JSONObject(json);
            if (!parsed.has("bills") || !parsed.has("settings")) {
                throw new Exception("Selected file is not a valid Purple Signature backup");
            }

            if (backupWebView != null) {
                String script = "window.receiveNativeBackup(" + JSONObject.quote(json) + ");";
                backupWebView.evaluateJavascript(script, null);
            }
        } catch (Exception error) {
            Toast.makeText(this, "Backup import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            if (backupWebView != null) {
                backupWebView.evaluateJavascript(
                        "window.nativeBackupImportFailed(" + JSONObject.quote(error.getMessage()) + ");",
                        null
                );
            }
        }
    }

    private String readBackupText(Uri uri) throws Exception {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new Exception("Cannot open selected backup");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;

        try {
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BACKUP_BYTES) throw new Exception("Backup file is larger than 50 MB");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
            output.close();
        }
    }

    private void installNativeBackupController() {
        if (backupWebView == null) return;

        String script = "(function(){" +
                "function install(){" +
                "if(window.__nativeBackupInstalled)return;" +
                "var exportButton=document.getElementById('exportBackupBtn');" +
                "var input=document.getElementById('importBackup');" +
                "if(!exportButton||!input||!window.NativeBackup){setTimeout(install,250);return;}" +
                "window.nativeBackupExportFinished=function(ok,message){" +
                "exportButton.disabled=false;exportButton.textContent='Export All Data';" +
                "if(typeof toast==='function')toast(message,ok?'success':'error');" +
                "};" +
                "window.nativeBackupImportFailed=function(message){if(typeof toast==='function')toast('Backup import failed: '+message,'error');};" +
                "window.receiveNativeBackup=async function(jsonText){" +
                "try{" +
                "var data=JSON.parse(jsonText);" +
                "if(!data||typeof data!=='object'||!Array.isArray(data.bills)||!data.settings)throw new Error('Invalid backup structure');" +
                "var imported=0;" +
                "if(data.settings){settings=normalizeSettings(data.settings);saveSettingsLocal();}" +
                "for(var i=0;i<data.bills.length;i++){var bill=data.bills[i];if(!bill||!bill.invoiceNo)continue;await putBill(bill);imported++;}" +
                "fillSettings();renderStaffOptions();renderServices();await renderHistory();await renderTodaySummary();" +
                "if(typeof generateReport==='function')await generateReport();" +
                "if(typeof toast==='function')toast(imported+' bills imported successfully','success');" +
                "}catch(error){if(typeof toast==='function')toast('Backup import failed: '+error.message,'error');}" +
                "};" +
                "exportButton.onclick=async function(){" +
                "try{" +
                "exportButton.disabled=true;exportButton.textContent='Exporting...';" +
                "var bills=await allBills();" +
                "var backup=JSON.stringify({version:4,exportedAt:new Date().toISOString(),settings:settings,bills:bills},null,2);" +
                "window.NativeBackup.exportBackup(backup,'purple-signature-backup-'+today()+'.json');" +
                "}catch(error){exportButton.disabled=false;exportButton.textContent='Export All Data';if(typeof toast==='function')toast('Backup export failed: '+error.message,'error');}" +
                "};" +
                "var openPicker=function(event){if(event){event.preventDefault();event.stopPropagation();}window.NativeBackup.openImportPicker();return false;};" +
                "input.onclick=openPicker;" +
                "if(input.parentElement)input.parentElement.onclick=openPicker;" +
                "window.__nativeBackupInstalled=true;" +
                "}" +
                "install();" +
                "})();";

        backupWebView.evaluateJavascript(script, null);
    }

    private void notifyExportFinished(boolean success, String message) {
        if (backupWebView == null) return;
        backupWebView.evaluateJavascript(
                "window.nativeBackupExportFinished(" + success + "," + JSONObject.quote(message) + ");",
                null
        );
    }

    private void notifyImportCancelled() {
        if (backupWebView == null) return;
        backupWebView.evaluateJavascript(
                "if(typeof toast==='function')toast('Backup import cancelled','error');",
                null
        );
    }

    private String cleanBackupFileName(String requestedFileName) {
        String name = requestedFileName == null ? "" : requestedFileName.trim();
        if (name.isEmpty()) name = "purple-signature-backup.json";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.toLowerCase().endsWith(".json")) name += ".json";
        return name;
    }
}
