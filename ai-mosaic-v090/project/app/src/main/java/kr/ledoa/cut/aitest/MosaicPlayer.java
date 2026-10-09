package kr.ledoa.cut.aitest;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Audio/video use MediaPlayer's clock, not repeated thumbnail seeks. */
final class MosaicPlayer extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Events {void ready();void finished();void failed(String message);}
    private final Uri uri;private final MosaicTimeline masks;private final long start;
    private final Events events;private final int displayWidth,displayHeight;
    private final AtomicBoolean frameAvailable=new AtomicBoolean();
    private final float[] transform=new float[16];
    private SurfaceTexture texture;private Surface surface;private MosaicGl gl;
    private volatile MediaPlayer player;private volatile boolean released=false;
    private int width,height;
    MosaicPlayer(Context context,Uri uri,MosaicTimeline timeline,long start,int w,int h,Events events){
        super(context);this.uri=uri;this.masks=timeline;this.start=start;this.events=events;displayWidth=w;displayHeight=h;
        setEGLContextClientVersion(2);setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);
    }
    public void onSurfaceCreated(GL10 unused,EGLConfig config){
        try{
            gl=new MosaicGl();texture=new SurfaceTexture(gl.create());
            texture.setOnFrameAvailableListener(t->{frameAvailable.set(true);requestRender();});surface=new Surface(texture);
            post(()->{
                if(released)return;
                try{
                    MediaPlayer p=new MediaPlayer();player=p;p.setDataSource(getContext(),uri);p.setSurface(surface);
                    p.setOnPreparedListener(mp->{if(released)return;mp.seekTo(start,MediaPlayer.SEEK_CLOSEST);});
                    p.setOnSeekCompleteListener(mp->{if(!released){mp.start();events.ready();}});
                    p.setOnCompletionListener(mp->{if(!released)events.finished();});
                    p.setOnErrorListener((mp,a,b)->{if(!released)events.failed("영상 재생 오류 ("+a+", "+b+")");return true;});
                    p.prepareAsync();
                }catch(Exception e){events.failed(e.getMessage());}
            });
        }catch(Exception e){post(()->events.failed(e.getMessage()));}
    }
    public void onSurfaceChanged(GL10 unused,int w,int h){width=w;height=h;}
    public void onDrawFrame(GL10 unused){
        if(released||texture==null||!frameAvailable.getAndSet(false))return;
        try{
            texture.updateTexImage();texture.getTransformMatrix(transform);
            float scale=Math.min(width/(float)Math.max(1,displayWidth),height/(float)Math.max(1,displayHeight));
            int w=Math.round(displayWidth*scale),h=Math.round(displayHeight*scale);
            GLES20.glViewport((width-w)/2,(height-h)/2,w,h);
            // MediaPlayer applies source rotation in its SurfaceTexture transform.
            gl.draw(transform,0,displayWidth,displayHeight,masks.boxesAt(position()),masks.strength,masks.margin);
        }catch(Exception e){post(()->events.failed(e.getMessage()));}
    }
    long position(){MediaPlayer p=player;if(p==null)return start;try{return p.getCurrentPosition();}catch(Exception ignored){return start;}}
    void release(){
        released=true;MediaPlayer p=player;player=null;if(p!=null){try{p.stop();}catch(Exception ignored){}p.release();}
        queueEvent(()->{if(texture!=null){texture.release();texture=null;}if(surface!=null){surface.release();surface=null;}if(gl!=null){gl.release();gl=null;}});
    }
}
