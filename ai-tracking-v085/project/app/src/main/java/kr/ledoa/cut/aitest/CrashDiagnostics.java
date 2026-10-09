package kr.ledoa.cut.aitest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Crash analysis breadcrumbs stored strictly inside the isolated test app.
 * No video frames, faces, source file names, or personal identifiers are saved.
 * Works even when Android kills the process and no Java exception is delivered.
 */
final class CrashDiagnostics {
    private static final String PREFS="face_analysis_state_v088";
    private static final String CRASH_FILE="analysis_error_v088.txt";
    private final Context context;
    private final SharedPreferences preferences;
    private final Thread.UncaughtExceptionHandler priorHandler;
    private volatile long ms=0;
    private volatile String stage="IDLE";
    private long lastPersist=-10000;
    private volatile boolean forwarding=false;

    CrashDiagnostics(Context app){
        context=app.getApplicationContext();
        preferences=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        priorHandler=Thread.getDefaultUncaughtExceptionHandler();
    }
    boolean previousWasInterrupted(){
        return preferences.getBoolean("analysisActive",false);
    }
    void install(){
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            if(!forwarding){
                forwarding=true;
                try{recordCrash(error,"UNCAUGHT_"+thread.getName());}
                catch(Throwable ignored){}
            }
            if(priorHandler!=null && priorHandler!=Thread.getDefaultUncaughtExceptionHandler())
                priorHandler.uncaughtException(thread,error);
        });
    }
    void begin(long startMs){
        stage="BEGIN";ms=startMs;lastPersist=-10000;
        preferences.edit()
                .putBoolean("analysisActive",true)
                .putLong("lastProgressMs",startMs)
                .putString("lastStage","BEGIN")
                .putLong("analysisStartedEpochMs",System.currentTimeMillis())
                .commit();
    }
    void progress(long frameMs,String step){
        ms=frameMs;stage=step;
        long now=SystemClock.elapsedRealtime();
        if(now-lastPersist>=950){
            lastPersist=now;
            preferences.edit()
                    .putLong("lastProgressMs",ms)
                    .putString("lastStage",stage)
                    .apply();
        }
    }
    void finished(long endMs,boolean completed){
        ms=endMs;stage=completed?"COMPLETE":"STOPPED";
        preferences.edit()
            .putBoolean("analysisActive",false)
            .putLong("lastProgressMs",endMs)
            .putString("lastStage",stage)
            .commit();
    }
    void recordRecoverable(Throwable error,String where){
        recordCrash(error,"CAUGHT_"+where);
        // Stop the flag after a catch that gracefully recovers rather than a kill.
        finished(ms,false);
    }
    private void recordCrash(Throwable error,String where){
        try{
            StringBuilder b=new StringBuilder();
            b.append("LEDOA AI face diagnostics v0.8.8\n");
            b.append("reason=").append(where).append("\n");
            b.append("lastFrameMs=").append(ms).append("\n");
            b.append("stage=").append(stage).append("\n");
            b.append("epochMs=").append(System.currentTimeMillis()).append("\n");
            b.append("androidApi=").append(Build.VERSION.SDK_INT).append("\n");
            b.append("javaHeapAvailableBytes=").append(Runtime.getRuntime().freeMemory()).append("\n");
            b.append("nativeHeapAllocatedBytes=").append(android.os.Debug.getNativeHeapAllocatedSize()).append("\n");
            b.append("error=").append(error.getClass().getName()).append("\n");
            // Avoid giant Android stack logs, URI paths or raw image data.
            StackTraceElement[] trace=error.getStackTrace();
            for(int i=0;i<Math.min(14,trace.length);i++)
                b.append("at=").append(trace[i].getClassName()).append(".")
                    .append(trace[i].getMethodName()).append(":")
                    .append(trace[i].getLineNumber()).append("\n");
            try(FileOutputStream out=context.openFileOutput(CRASH_FILE,Context.MODE_PRIVATE)){
                out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            }
        }catch(Throwable ignored){}
    }
    String export(){
        StringBuilder b=new StringBuilder();
        b.append("LEDOA AI FACE v0.8.8 DIAGNOSTICS\n");
        b.append("previousAnalysisInterrupted=").append(preferences.getBoolean("analysisActive",false)).append("\n");
        b.append("lastSavedFrameMs=").append(preferences.getLong("lastProgressMs",-1)).append("\n");
        b.append("lastSavedStage=").append(preferences.getString("lastStage","UNKNOWN")).append("\n");
        b.append("analysisStartEpochMs=").append(preferences.getLong("analysisStartedEpochMs",0)).append("\n");
        b.append("nativeHeapAllocatedBytes=").append(android.os.Debug.getNativeHeapAllocatedSize()).append("\n");
        b.append("javaHeapFreeBytes=").append(Runtime.getRuntime().freeMemory()).append("\n");
        try(FileInputStream in=context.openFileInput(CRASH_FILE)){
            b.append("---- LAST CAUGHT/UNCAUGHT ERROR ----\n");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] chunk=new byte[1024];int n;
            while((n=in.read(chunk))!=-1 && out.size()<6000)out.write(chunk,0,n);
            b.append(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }catch(FileNotFoundException notFound){
            b.append("errorLog=none (Android process kill may not invoke crash handler)\n");
        }catch(IOException e){
            b.append("errorLog=unreadable\n");
        }
        return b.toString();
    }
}
