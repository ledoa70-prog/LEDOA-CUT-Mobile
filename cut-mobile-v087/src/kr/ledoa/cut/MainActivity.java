package kr.ledoa.cut;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && request.visualOnly) {
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (request.imageOnly) intent.setType("image/*");
            else if (request.videoOnly) intent.setType("video/*");
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
            boolean hasImage = false, hasVideo = false, hasAudio = false, hasOther = false;
            String[] raw = params != null ? params.getAcceptTypes() : null;
            if (raw != null) {
                for (String block : raw) {
                    if (block == null) continue;
                    for (String part : block.split(",")) {
                        String type = part.trim().toLowerCase(Locale.US);
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
        private OutputStream currentOutput;
        private File currentTemp;
        private String currentName;
        private String currentMime;

        AndroidBridge(Activity activity) { this.activity = activity; }

        @JavascriptInterface
        public synchronized void beginFile(String name, String mime, long totalBytes) {
            closeQuietly();
            try {
                currentName = (name == null || name.trim().isEmpty()) ? ("LEDOA-CUT-" + System.currentTimeMillis()) : name;
                currentMime = normalizeMime(mime, currentName);
                currentTemp = File.createTempFile("ledoa_capture_", ".bin", activity.getCacheDir());
                currentOutput = new FileOutputStream(currentTemp, false);
            } catch (Exception e) {
                closeQuietly();
                showToast("저장 시작 실패: " + safeMessage(e));
            }
        }

        @JavascriptInterface
        public synchronized void appendChunk(String base64Chunk) {
            if (currentOutput == null || base64Chunk == null) return;
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                currentOutput.write(bytes);
            } catch (Exception e) {
                showToast("저장 중 오류: " + safeMessage(e));
                closeQuietly();
            }
        }

        @JavascriptInterface
        public synchronized void finishFile() {
            if (currentTemp == null) return;
            File source = currentTemp;
            File normalizedMp4 = null;
            try {
                if (currentOutput != null) {
                    currentOutput.flush();
                    currentOutput.close();
                    currentOutput = null;
                }

                File fileToSave = source;
                if (isMp4(currentMime, currentName)) {
                    normalizedMp4 = File.createTempFile("ledoa_youtube_", ".mp4", activity.getCacheDir());
                    remuxToStandardMp4(source, normalizedMp4);
                    fileToSave = normalizedMp4;
                    currentMime = "video/mp4";
                    if (!currentName.toLowerCase(Locale.US).endsWith(".mp4")) currentName += ".mp4";
                }

                saveToMediaStore(fileToSave, currentName, currentMime);
                if (isMp4(currentMime, currentName)) {
                    showToast("LEDOA CUT 저장 완료 · 유튜브용 MP4 정리 완료");
                } else {
                    showToast("LEDOA CUT 저장 완료");
                }
            } catch (Exception e) {
                showToast("저장 마무리 실패: " + safeMessage(e));
            } finally {
                if (source != null) source.delete();
                if (normalizedMp4 != null) normalizedMp4.delete();
                currentTemp = null;
                currentOutput = null;
                currentName = null;
                currentMime = null;
            }
        }

        private void remuxToStandardMp4(File input, File output) throws Exception {
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;
            boolean started = false;
            try {
                extractor.setDataSource(input.getAbsolutePath());
                int trackCount = extractor.getTrackCount();
                int[] trackMap = new int[trackCount];
                Arrays.fill(trackMap, -1);

                muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(input.getAbsolutePath());
                    String rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (rotation != null) {
                        int deg = Integer.parseInt(rotation);
                        if (deg == 90 || deg == 180 || deg == 270) muxer.setOrientationHint(deg);
                    }
                    mmr.release();
                } catch (Exception ignored) { }

                int selected = 0;
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) continue;
                    trackMap[i] = muxer.addTrack(format);
                    extractor.selectTrack(i);
                    selected++;
                }
                if (selected == 0) throw new IllegalStateException("영상/오디오 트랙을 찾지 못했습니다.");

                muxer.start();
                started = true;

                int capacity = 4 * 1024 * 1024;
                ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                while (true) {
                    int sourceTrack = extractor.getSampleTrackIndex();
                    if (sourceTrack < 0) break;
                    long sampleSize = extractor.getSampleSize();
                    if (sampleSize > buffer.capacity()) {
                        int next = buffer.capacity();
                        while (next < sampleSize && next < 64 * 1024 * 1024) next *= 2;
                        if (next < sampleSize) throw new IllegalStateException("프레임 크기가 너무 큽니다.");
                        buffer = ByteBuffer.allocateDirect(next);
                    }
                    buffer.clear();
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;

                    int muxTrack = (sourceTrack < trackMap.length) ? trackMap[sourceTrack] : -1;
                    if (muxTrack >= 0) {
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = Math.max(0, extractor.getSampleTime());
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(muxTrack, buffer, info);
                    }
                    if (!extractor.advance()) break;
                }
            } finally {
                try { extractor.release(); } catch (Exception ignored) { }
                if (muxer != null) {
                    if (started) try { muxer.stop(); } catch (Exception ignored) { }
                    try { muxer.release(); } catch (Exception ignored) { }
                }
            }
            if (!output.exists() || output.length() < 1024) throw new IllegalStateException("MP4 정리 파일 생성 실패");
        }

        private void saveToMediaStore(File file, String name, String mime) throws Exception {
            String cleanMime = normalizeMime(mime, name);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, cleanMime);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection;
            if (cleanMime.startsWith("image/")) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LEDOA CUT");
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if (cleanMime.startsWith("video/")) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LEDOA CUT");
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LEDOA CUT");
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            }

            Uri uri = activity.getContentResolver().insert(collection, values);
            if (uri == null) throw new IllegalStateException("저장 위치 생성 실패");
            boolean success = false;
            try (FileInputStream in = new FileInputStream(file);
                 OutputStream out = activity.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("저장 스트림 생성 실패");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
                out.flush();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                activity.getContentResolver().update(uri, done, null, null);
                success = true;
            } finally {
                if (!success) {
                    try { activity.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
            }
        }

        private static boolean isMp4(String mime, String name) {
            String m = mime == null ? "" : mime.toLowerCase(Locale.US);
            String n = name == null ? "" : name.toLowerCase(Locale.US);
            return m.startsWith("video/mp4") || n.endsWith(".mp4");
        }

        private static String normalizeMime(String mime, String name) {
            String m = mime == null ? "" : mime.trim().toLowerCase(Locale.US);
            int semi = m.indexOf(';');
            if (semi >= 0) m = m.substring(0, semi).trim();
            if (m.isEmpty() || "application/octet-stream".equals(m)) {
                String n = name == null ? "" : name.toLowerCase(Locale.US);
                if (n.endsWith(".mp4")) return "video/mp4";
                if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
                if (n.endsWith(".png")) return "image/png";
                return "application/octet-stream";
            }
            return m;
        }

        private synchronized void closeQuietly() {
            try { if (currentOutput != null) currentOutput.close(); } catch (Exception ignored) { }
            currentOutput = null;
            if (currentTemp != null) try { currentTemp.delete(); } catch (Exception ignored) { }
            currentTemp = null;
            currentName = null;
            currentMime = null;
        }

        private static String safeMessage(Exception e) {
            String m = e.getMessage();
            return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
        }

        private void showToast(String message) {
            activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
        }
    }
}
