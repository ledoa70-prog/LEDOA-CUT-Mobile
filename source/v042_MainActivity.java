package kr.ledoa.cut;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 701;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;

                Intent intent = buildFreshFilePickerIntent(params);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception first) {
                    try {
                        startActivityForResult(buildDocumentFallback(params), FILE_CHOOSER_REQUEST);
                    } catch (Exception second) {
                        fileChooserCallback.onReceiveValue(null);
                        fileChooserCallback = null;
                        Toast.makeText(MainActivity.this, "미디어 선택 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private Intent buildFreshFilePickerIntent(WebChromeClient.FileChooserParams params) {
        PickerRequest request = PickerRequest.from(params);

        // Android 13+ visual media: use the system Photo Picker instead of WebView's cached/recent file chooser.
        // Photo Picker reads the current media catalog and excludes MediaStore trash/pending entries from normal browsing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && request.visualOnly) {
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (request.imageOnly) intent.setType("image/*");
            else if (request.videoOnly) intent.setType("video/*");
            // For image + video together, leaving MIME unset lets Photo Picker show both.

            if (request.multiple) {
                int max = Math.min(50, MediaStore.getPickImagesMaxLimit());
                intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
            }
            return intent;
        }

        return buildDocumentFallback(params);
    }

    private Intent buildDocumentFallback(WebChromeClient.FileChooserParams params) {
        PickerRequest request = PickerRequest.from(params);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.multiple);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

        if (request.acceptTypes.length == 1) {
            intent.setType(request.acceptTypes[0]);
        } else if (request.acceptTypes.length > 1) {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, request.acceptTypes);
        } else {
            intent.setType("*/*");
        }
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }

            if (!uris.isEmpty()) result = uris.toArray(new Uri[0]);
        }

        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            // Let the editor close an open bottom sheet first. If none is open, normal back behavior continues.
            webView.evaluateJavascript(
                    "(function(){try{if(window.LEDOA_CLOSE_TOP_SHEET&&window.LEDOA_CLOSE_TOP_SHEET())return 'closed';}catch(e){}return 'none';})()",
                    value -> {
                        if ("\"closed\"".equals(value)) return;
                        if (webView.canGoBack()) webView.goBack();
                        else MainActivity.super.onBackPressed();
                    });
        } else {
            super.onBackPressed();
        }
    }

    private static class PickerRequest {
        final String[] acceptTypes;
        final boolean multiple;
        final boolean visualOnly;
        final boolean imageOnly;
        final boolean videoOnly;

        PickerRequest(String[] acceptTypes, boolean multiple, boolean visualOnly, boolean imageOnly, boolean videoOnly) {
            this.acceptTypes = acceptTypes;
            this.multiple = multiple;
            this.visualOnly = visualOnly;
            this.imageOnly = imageOnly;
            this.videoOnly = videoOnly;
        }

        static PickerRequest from(WebChromeClient.FileChooserParams params) {
            Set<String> normalized = new LinkedHashSet<>();
            boolean hasImage = false;
            boolean hasVideo = false;
            boolean hasAudio = false;
            boolean hasOther = false;

            String[] raw = params != null ? params.getAcceptTypes() : null;
            if (raw != null) {
                for (String block : raw) {
                    if (block == null) continue;
                    for (String part : block.split(",")) {
                        String type = part.trim().toLowerCase();
                        if (type.isEmpty()) continue;
                        normalized.add(type);
                        if (type.startsWith("image/")) hasImage = true;
                        else if (type.startsWith("video/")) hasVideo = true;
                        else if (type.startsWith("audio/")) hasAudio = true;
                        else if (!"*/*".equals(type)) hasOther = true;
                    }
                }
            }

            boolean noTypes = normalized.isEmpty();
            if (noTypes) normalized.add("*/*");
            boolean visualOnly = !noTypes && (hasImage || hasVideo) && !hasAudio && !hasOther;
            boolean imageOnly = visualOnly && hasImage && !hasVideo;
            boolean videoOnly = visualOnly && hasVideo && !hasImage;
            boolean multiple = params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;

            return new PickerRequest(normalized.toArray(new String[0]), multiple, visualOnly, imageOnly, videoOnly);
        }
    }

    public static class AndroidBridge {
        private final Activity activity;
        private Uri currentUri;
        private OutputStream currentOutput;

        AndroidBridge(Activity activity) { this.activity = activity; }

        @JavascriptInterface
        public synchronized void beginFile(String name, String mime, long totalBytes) {
            closeQuietly();
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri collection;
                if (mime != null && mime.startsWith("image/")) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LEDOA CUT");
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if (mime != null && mime.startsWith("video/")) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LEDOA CUT");
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LEDOA CUT");
                    collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                }

                currentUri = activity.getContentResolver().insert(collection, values);
                if (currentUri == null) throw new IllegalStateException("저장 위치 생성 실패");
                currentOutput = activity.getContentResolver().openOutputStream(currentUri, "w");
                if (currentOutput == null) throw new IllegalStateException("저장 스트림 생성 실패");
            } catch (Exception e) {
                closeQuietly();
                showToast("저장 시작 실패: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public synchronized void appendChunk(String base64Chunk) {
            if (currentOutput == null || base64Chunk == null) return;
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                currentOutput.write(bytes);
            } catch (Exception e) {
                showToast("저장 중 오류: " + e.getMessage());
                closeQuietly();
            }
        }

        @JavascriptInterface
        public synchronized void finishFile() {
            if (currentUri == null) return;
            try {
                if (currentOutput != null) {
                    currentOutput.flush();
                    currentOutput.close();
                    currentOutput = null;
                }
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                activity.getContentResolver().update(currentUri, values, null, null);
                showToast("LEDOA CUT 저장 완료");
            } catch (Exception e) {
                showToast("저장 마무리 실패: " + e.getMessage());
            } finally {
                currentUri = null;
                currentOutput = null;
            }
        }

        private synchronized void closeQuietly() {
            try { if (currentOutput != null) currentOutput.close(); } catch (Exception ignored) {}
            currentOutput = null;
            currentUri = null;
        }

        private void showToast(String message) {
            activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
        }
    }
}
