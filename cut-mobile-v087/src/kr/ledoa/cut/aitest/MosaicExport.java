package kr.ledoa.cut.aitest;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.*;
import android.net.Uri;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hardware surface decode -> shared mosaic shader -> H.264 encoder. No RGB
 * readback, no full-video bitmap cache, no cloud. AAC audio is copied losslessly.
 * Work is written to a private temporary file; the caller publishes only success. */
final class MosaicExport {
    interface Progress {void update(int percent);}
    static final class Result {final int width,height,frames;final boolean audio;Result(int w,int h,int f,boolean a){width=w;height=h;frames=f;audio=a;}}
    static Result run(Context context,Uri input,File destination,MosaicTimeline masks,
                      AtomicBoolean cancel,Progress progress)throws Exception {
        MediaExtractor video=new MediaExtractor(),audio=new MediaExtractor();
        MediaCodec decoder=null,encoder=null;MediaMuxer muxer=null;
        Surface encodeSurface=null,decodeSurface=null;SurfaceTexture texture=null;
        HandlerThread callbackThread=new HandlerThread("mosaic-export-frames");
        Egl egl=null;MosaicGl gl=null;boolean muxStarted=false,success=false;
        int frames=0,outW=0,outH=0;boolean hasAudio=false;
        try{
            video.setDataSource(context,input,null);audio.setDataSource(context,input,null);
            MediaFormat vf=null,af=null;int audioIndex=-1;
            for(int i=0;i<video.getTrackCount();i++){
                MediaFormat f=video.getTrackFormat(i);String type=f.getString(MediaFormat.KEY_MIME);
                if(vf==null&&type!=null&&type.startsWith("video/")){vf=f;video.selectTrack(i);}
                else if(af==null&&type!=null&&type.startsWith("audio/")){af=f;audioIndex=i;}
            }
            if(vf==null)throw new IOException("영상 트랙을 읽지 못했습니다.");
            if(af!=null&&!"audio/mp4a-latm".equals(af.getString(MediaFormat.KEY_MIME)))
                throw new IOException("이 영상의 오디오는 AAC가 아닙니다. AAC 오디오가 있는 MP4를 사용해 주세요.");
            if(vf.containsKey(MediaFormat.KEY_COLOR_TRANSFER)){
                int transfer=vf.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                if(transfer==6||transfer==7)throw new IOException("HDR 영상은 현재 저장을 지원하지 않습니다. SDR 영상으로 변환해 주세요.");
            }
            int rotation=vf.containsKey(MediaFormat.KEY_ROTATION)?vf.getInteger(MediaFormat.KEY_ROTATION):0;
            rotation=(rotation%360+360)%360;
            if(rotation%90!=0)throw new IOException("지원하지 않는 영상 회전 각도입니다.");
            int codedW=vf.getInteger(MediaFormat.KEY_WIDTH),codedH=vf.getInteger(MediaFormat.KEY_HEIGHT);
            int dw=rotation%180==0?codedW:codedH,dh=rotation%180==0?codedH:codedW;
            float factor=Math.min(1f,1920f/Math.max(dw,dh));
            outW=Math.max(2,Math.round(dw*factor)/2*2);outH=Math.max(2,Math.round(dh*factor)/2*2);
            long duration=vf.containsKey(MediaFormat.KEY_DURATION)?vf.getLong(MediaFormat.KEY_DURATION):masks.endMs*1000;
            MediaFormat ef=MediaFormat.createVideoFormat("video/avc",outW,outH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(2000000,Math.min(12000000,outW*outH*5)));
            ef.setInteger(MediaFormat.KEY_FRAME_RATE,30);ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            encoder=MediaCodec.createEncoderByType("video/avc");encoder.configure(ef,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            encodeSurface=encoder.createInputSurface();encoder.start();
            egl=new Egl(encodeSurface);gl=new MosaicGl();texture=new SurfaceTexture(gl.create());
            Object frameLock=new Object();AtomicBoolean available=new AtomicBoolean(false);
            callbackThread.start();
            texture.setOnFrameAvailableListener(t->{synchronized(frameLock){available.set(true);frameLock.notifyAll();}},new Handler(callbackThread.getLooper()));
            decodeSurface=new Surface(texture);
            // Apply metadata rotation exactly once, in the shared GL shader.
            vf.setInteger(MediaFormat.KEY_ROTATION,0);
            decoder=MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));decoder.configure(vf,decodeSurface,null,0);decoder.start();
            muxer=new MediaMuxer(destination.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int audioTrack=-1,videoTrack=-1;
            if(af!=null){audioTrack=muxer.addTrack(af);audio.selectTrack(audioIndex);hasAudio=true;}
            boolean inputEnd=false,decoderEnd=false,encoderEnd=false,signalled=false;
            MediaCodec.BufferInfo decoded=new MediaCodec.BufferInfo(),encoded=new MediaCodec.BufferInfo();
            float[] transform=new float[16];long lastProgress=0,lastEncoded=-1,lastActivity=SystemClock.elapsedRealtime();
            GLES20.glViewport(0,0,outW,outH);
            while(!encoderEnd){
                if(cancel.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("저장을 취소했습니다.");
                if(SystemClock.elapsedRealtime()-lastActivity>30000)throw new IOException("영상 저장 장치의 응답이 지연됐습니다.");
                // Drain encoder first to avoid decoder/GL backpressure deadlock.
                int eo=encoder.dequeueOutputBuffer(encoded,0);
                if(eo==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    if(muxStarted)throw new IOException("인코더 형식이 중복 변경됐습니다.");
                    videoTrack=muxer.addTrack(encoder.getOutputFormat());muxer.start();muxStarted=true;
                }else if(eo>=0){
                    ByteBuffer data=encoder.getOutputBuffer(eo);
                    if((encoded.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)encoded.size=0;
                    if(encoded.size>0){
                        if(!muxStarted||data==null)throw new IOException("영상 저장 준비가 완료되지 않았습니다.");
                        data.position(encoded.offset);data.limit(encoded.offset+encoded.size);
                        if(encoded.presentationTimeUs<=lastEncoded)throw new IOException("영상 시간 순서가 올바르지 않습니다.");
                        muxer.writeSampleData(videoTrack,data,encoded);lastEncoded=encoded.presentationTimeUs;
                    }
                    encoderEnd=(encoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.releaseOutputBuffer(eo,false);lastActivity=SystemClock.elapsedRealtime();
                }
                if(!inputEnd){
                    int in=decoder.dequeueInputBuffer(0);
                    if(in>=0){ByteBuffer data=decoder.getInputBuffer(in);data.clear();int n=video.readSampleData(data,0);
                        if(n<0){inputEnd=true;decoder.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);}
                        else{decoder.queueInputBuffer(in,0,n,video.getSampleTime(),0);video.advance();}
                    }
                }
                if(!decoderEnd){
                    int d=decoder.dequeueOutputBuffer(decoded,1000);
                    if(d>=0){
                        boolean render=decoded.size>0;long pts=Math.max(0,decoded.presentationTimeUs);
                        decoderEnd=(decoded.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        decoder.releaseOutputBuffer(d,render);
                        if(render){
                            long deadline=SystemClock.elapsedRealtime()+5000;
                            synchronized(frameLock){while(!available.get()&&!cancel.get()){
                                long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)throw new IOException("영상 프레임 준비 시간 초과");frameLock.wait(Math.min(remaining,100));}
                                available.set(false);
                            }
                            if(cancel.get())throw new InterruptedException("저장을 취소했습니다.");
                            texture.updateTexImage();texture.getTransformMatrix(transform);
                            gl.draw(transform,rotation,outW,outH,masks.boxesAt(pts/1000),masks.strength,masks.margin);
                            EGLExt.eglPresentationTimeANDROID(egl.display,egl.surface,pts*1000);
                            if(!EGL14.eglSwapBuffers(egl.display,egl.surface))throw new IOException("영상 프레임 쓰기 오류");
                            frames++;lastActivity=SystemClock.elapsedRealtime();
                            if(lastActivity-lastProgress>250){progress.update((int)Math.min(95,95*pts/Math.max(1,duration)));lastProgress=lastActivity;}
                        }
                    }
                }
                if(decoderEnd&&!signalled){encoder.signalEndOfInputStream();signalled=true;}
            }
            if(frames==0||!muxStarted)throw new IOException("저장할 영상 프레임이 없습니다.");
            // Explicit final duration prevents fractional last frames from being lost.
            if(duration>lastEncoded){MediaCodec.BufferInfo end=new MediaCodec.BufferInfo();end.set(0,0,duration,MediaCodec.BUFFER_FLAG_END_OF_STREAM);muxer.writeSampleData(videoTrack,ByteBuffer.allocateDirect(0),end);}
            if(hasAudio){
                int capacity=af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)?af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE):262144;
                ByteBuffer data=ByteBuffer.allocateDirect(Math.max(262144,capacity));MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                int writtenAudioSamples=0;
                // AAC may expose a negative timestamp for its encoder priming packet
                // (Android 16 does this for MP4 edit lists). A negative timestamp is
                // not EOF: only readSampleData() returning -1 ends extraction.
                while(true){
                    if(cancel.get())throw new InterruptedException("저장을 취소했습니다.");
                    data.clear();int n=audio.readSampleData(data,0);if(n<0)break;
                    long pts=audio.getSampleTime();
                    if(pts>=0&&n>0){
                        int flags=audio.getSampleFlags();
                        if((flags&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("암호화된 오디오는 저장할 수 없습니다.");
                        // Extractor flags and codec flags are different enums.
                        info.set(0,n,pts,(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                        data.position(0);data.limit(n);muxer.writeSampleData(audioTrack,data,info);writtenAudioSamples++;
                    }
                    audio.advance();
                }
                if(writtenAudioSamples==0)throw new IOException("오디오 트랙을 저장하지 못했습니다. 원본 영상은 변경되지 않았습니다.");
            }
            muxer.stop();muxStarted=false;success=true;progress.update(100);
            return new Result(outW,outH,frames,hasAudio);
        }finally{
            if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}
            if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}
            if(texture!=null)texture.release();if(decodeSurface!=null)decodeSurface.release();
            if(gl!=null)gl.release();if(egl!=null)egl.close();if(encodeSurface!=null)encodeSurface.release();
            callbackThread.quitSafely();
            if(muxer!=null){if(muxStarted)try{muxer.stop();}catch(Exception ignored){}muxer.release();}
            video.release();audio.release();if(!success)destination.delete();
        }
    }
    private static final class Egl implements AutoCloseable {
        final EGLDisplay display;final EGLContext context;final EGLSurface surface;
        Egl(Surface window){
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] versions=new int[2];
            if(!EGL14.eglInitialize(display,versions,0,versions,1))throw new IllegalStateException("EGL 초기화 실패");
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0);if(count[0]==0)throw new IllegalStateException("EGL 설정 실패");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreateWindowSurface(display,configs[0],window,new int[]{EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("EGL 화면 생성 실패");
        }
        public void close(){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,context);EGL14.eglReleaseThread();EGL14.eglTerminate(display);}
    }
}
