package kr.ledoa.cut.aitest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** One monotonic hardware decode, not a key-frame seek for each AI sample.
 * Converts only requested samples, at <=640px. Honors crop, plane strides,
 * pixel strides and display rotation. No full-resolution RGB frame is made.
 * Caller falls back once to MediaMetadataRetriever if a codec lacks Image output.
 */
final class SequentialFrames implements AutoCloseable {
    static final class Frame {
        final Bitmap bitmap; final long ms;
        Frame(Bitmap b,long ms){this.bitmap=b;this.ms=ms;}
    }
    private final MediaExtractor extractor=new MediaExtractor();
    private MediaCodec codec;
    private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    private boolean inputEnd=false,outputEnd=false;
    private int rotation;
    private boolean fullRange=false,bt709=true;
    private int[] colors;
    private final byte[][] planeBytes=new byte[3][];
    private final AtomicBoolean cancel;
    int decodedFrames=0,convertedFrames=0;
    SequentialFrames(Context context,Uri uri,long startMs,AtomicBoolean cancel)throws IOException {
        this.cancel=cancel;
        try{
            extractor.setDataSource(context,uri,null);
            MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")){format=f;extractor.selectTrack(i);break;}
            }
            if(format==null)throw new IOException("No video track");
            if(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)){
                int transfer=format.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                if(transfer==6||transfer==7)throw new IOException("HDR uses the color-managed retriever");
            }
            readColor(format);
            rotation=format.containsKey(MediaFormat.KEY_ROTATION)?format.getInteger(MediaFormat.KEY_ROTATION):0;
            rotation=((rotation%360)+360)%360;
            if(rotation%90!=0)throw new IOException("Unsupported video rotation");
            // Rotation is applied once in our converter; ByteBuffer output is coded orientation.
            format.setInteger(MediaFormat.KEY_ROTATION,0);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format,null,null,0);codec.start();
            extractor.seekTo(Math.max(0,startMs)*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }catch(IOException|RuntimeException e){close();throw e;}
    }
    Frame at(long targetMs)throws IOException {return at(targetMs,640);}
    Frame at(long targetMs,int maxEdge)throws IOException {
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(!outputEnd&&!cancel.get()&&!Thread.currentThread().isInterrupted()){
            if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Video decoder stalled");
            if(!inputEnd){
                int input=codec.dequeueInputBuffer(0);
                if(input>=0){
                    ByteBuffer b=codec.getInputBuffer(input);
                    if(b==null)throw new IOException("Missing decoder input");
                    b.clear();int n=extractor.readSampleData(b,0);
                    if(n<0){inputEnd=true;codec.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);}
                    else{long pts=extractor.getSampleTime();codec.queueInputBuffer(input,0,n,pts,0);extractor.advance();}
                }
            }
            int output=codec.dequeueOutputBuffer(info,10000);
            if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){readColor(codec.getOutputFormat());continue;}
            if(output<0)continue;
            boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            Image image=null;
            try{
                if(info.size>0){
                    decodedFrames++;
                    long pts=Math.max(0,info.presentationTimeUs/1000);
                    if(pts>=targetMs){
                        image=codec.getOutputImage(output);
                        if(image==null)throw new IOException("Codec has no YUV Image output");
                        Bitmap bitmap=convert(image,maxEdge);convertedFrames++;
                        return new Frame(bitmap,pts);
                    }
                }
            }finally{
                if(image!=null)image.close();
                codec.releaseOutputBuffer(output,false);
                if(eos)outputEnd=true;
            }
        }
        return null;
    }
    private void readColor(MediaFormat format){
        if(format.containsKey(MediaFormat.KEY_COLOR_RANGE))fullRange=format.getInteger(MediaFormat.KEY_COLOR_RANGE)==1;
        if(format.containsKey(MediaFormat.KEY_COLOR_STANDARD))bt709=format.getInteger(MediaFormat.KEY_COLOR_STANDARD)==1;
    }
    private Bitmap convert(Image image,int maxEdge)throws IOException {
        Image.Plane[] planes=image.getPlanes();
        if(planes.length!=3)throw new IOException("Unsupported decoded image planes");
        Rect crop=image.getCropRect();int sw=crop.width(),sh=crop.height();
        if(sw<=0||sh<=0)throw new IOException("Empty decoded image");
        boolean quarter=rotation==90||rotation==270;
        int displayW=quarter?sh:sw,displayH=quarter?sw:sh;
        float factor=Math.min(1f,maxEdge/(float)Math.max(displayW,displayH));
        int w=Math.max(1,Math.round(displayW*factor)),h=Math.max(1,Math.round(displayH*factor));
        if(colors==null||colors.length!=w*h)colors=new int[w*h];
        ByteBuffer yy=planes[0].getBuffer(),uu=planes[1].getBuffer(),vv=planes[2].getBuffer();
        ByteBuffer[] buffers={yy,uu,vv};
        for(int k=0;k<3;k++){
            ByteBuffer src=buffers[k].duplicate();int n=src.remaining();
            if(planeBytes[k]==null||planeBytes[k].length<n)planeBytes[k]=new byte[n];
            src.get(planeBytes[k],0,n);
        }
        byte[] ya=planeBytes[0],ua=planeBytes[1],va=planeBytes[2];
        int yo=0,uo=0,vo=0;
        int yr=planes[0].getRowStride(),ur=planes[1].getRowStride(),vr=planes[2].getRowStride();
        int yp=planes[0].getPixelStride(),up=planes[1].getPixelStride(),vp=planes[2].getPixelStride();
        int rv=bt709?(fullRange?403:459):(fullRange?359:409);
        int gu=bt709?(fullRange?48:55):(fullRange?88:100);
        int gv=bt709?(fullRange?120:136):(fullRange?183:208);
        int bu=bt709?(fullRange?475:541):(fullRange?454:516);
        int[] xMap=new int[w],yMap=new int[h];
        for(int x=0;x<w;x++)xMap[x]=Math.min(displayW-1,(2*x+1)*displayW/(2*w));
        for(int y=0;y<h;y++)yMap[y]=Math.min(displayH-1,(2*y+1)*displayH/(2*h));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int dx=xMap[x];
            int dy=yMap[y];
            int sx,sy;
            if(rotation==90){sx=dy;sy=sh-1-dx;}
            else if(rotation==180){sx=sw-1-dx;sy=sh-1-dy;}
            else if(rotation==270){sx=sw-1-dy;sy=dx;}
            else{sx=dx;sy=dy;}
            sx+=crop.left;sy+=crop.top;
            int l=(ya[yo+sy*yr+sx*yp]&255)-(fullRange?0:16);
            int u=(ua[uo+(sy/2)*ur+(sx/2)*up]&255)-128;
            int v=(va[vo+(sy/2)*vr+(sx/2)*vp]&255)-128;
            l=Math.max(0,l)*(fullRange?256:298);
            int r=bound((l+rv*v+128)>>8),g=bound((l-gu*u-gv*v+128)>>8),b=bound((l+bu*u+128)>>8);
            colors[y*w+x]=0xff000000|(r<<16)|(g<<8)|b;
        }
        return Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888);
    }
    private static int bound(int v){return Math.max(0,Math.min(255,v));}
    @Override public void close(){
        if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}try{codec.release();}catch(RuntimeException ignored){}codec=null;}
        try{extractor.release();}catch(RuntimeException ignored){}
        colors=null;
    }
}
