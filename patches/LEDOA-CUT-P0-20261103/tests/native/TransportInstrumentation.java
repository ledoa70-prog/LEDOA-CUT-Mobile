package kr.ledoa.cut.p0test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

/** Emulator-only test runner. The target release APK is not modified. */
public final class TransportInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        try {
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    WebView.setWebContentsDebuggingEnabled(true);
                }
            });
            Log.i("LEDOA_P0_TEST", "sdk=" + Build.VERSION.SDK_INT
                    + " webview=" + WebView.getCurrentWebViewPackage().versionName);
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                for (String type : info.getSupportedTypes()) {
                    if (type.equals("video/hevc") || type.equals("video/avc")) {
                        Log.i("LEDOA_P0_TEST", "codec=" + info.getName() + " type=" + type
                                + " encoder=" + info.isEncoder() + " software=" + info.isSoftwareOnly());
                    }
                }
            }
            Intent launch = getTargetContext().getPackageManager()
                    .getLaunchIntentForPackage(getTargetContext().getPackageName());
            if (launch == null) throw new IllegalStateException("Target launch intent missing");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivitySync(launch);
            Bundle result = new Bundle();
            result.putString("status", "target started; WebView debugging enabled for isolated emulator test");
            sendStatus(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Log.e("LEDOA_P0_TEST", "Instrumentation startup failed", failure);
            Bundle result = new Bundle();
            result.putString("failure", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
