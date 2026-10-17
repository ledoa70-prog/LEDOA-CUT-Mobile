package kr.ledoa.cut.aitest;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One decode plus one latest request; dragging never builds an old-frame queue. */
final class FrameScrubber implements AutoCloseable {
    interface Listener {
        void frame(int session, long ms, Bitmap bitmap);
        void failed(int session, String message);
    }
    private static final class Request {
        final int session; final long ms;
        Request(int session, long ms) { this.session=session; this.ms=ms; }
    }
    private final Context context;
    private final Uri uri;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private MediaMetadataRetriever reader;
    private Request pending;
    private boolean running, closed;
    FrameScrubber(Context context, Uri uri, Listener listener) {
        this.context=context.getApplicationContext(); this.uri=uri; this.listener=listener;
    }
    synchronized void request(int session, long ms) {
        if(closed)return;
        pending=new Request(session,ms);
        if(!running){running=true;decoder.execute(this::decodeOne);}
    }
    synchronized void clear() { pending=null; }
    private void decodeOne() {
        final Request request;
        synchronized(this){
            if(closed||pending==null){running=false;return;}
            request=pending;pending=null;
        }
        Bitmap bitmap=null;String error=null;
        try {
            if(reader==null){reader=new MediaMetadataRetriever();reader.setDataSource(context,uri);}
            bitmap=reader.getScaledFrameAtTime(request.ms*1000,
                MediaMetadataRetriever.OPTION_CLOSEST,480,480);
            if(bitmap==null)throw new IllegalStateException("영상 프레임 없음");
        } catch(Exception e){error=e.getMessage();}
        final Bitmap image=bitmap;final String failure=error;
        // Wait for UI ownership transfer before decoding again: no bitmap backlog.
        main.post(()->{
            try {
                boolean stopped;synchronized(this){stopped=closed;}
                if(stopped){if(image!=null)image.recycle();}
                else if(image!=null)listener.frame(request.session,request.ms,image);
                else listener.failed(request.session,failure);
            } finally {
                synchronized(this){
                    if(!closed&&pending!=null)decoder.execute(this::decodeOne);
                    else running=false;
                }
            }
        });
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;pending=null;
        decoder.execute(()->{if(reader!=null)try{reader.release();}catch(Exception ignored){}reader=null;});
        decoder.shutdown();
    }
}
