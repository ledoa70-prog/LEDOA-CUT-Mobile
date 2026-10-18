package kr.ledoa.cut.aitest;
import android.graphics.Bitmap;

/** Small, disposable grayscale frame for the on-device optical bridge.
 * No frames or appearance patches are written to the exported JSON.
 */
final class MotionFrames {
    static final class Gray {
        final byte[] pixels;
        final int w,h;
        Gray(byte[] pixels,int w,int h){this.pixels=pixels;this.w=w;this.h=h;}
    }
    private MotionFrames(){}
    static Gray grayscale(Bitmap frame){
        if(frame==null)return null;
        int fw=frame.getWidth(),fh=frame.getHeight();
        if(fw<=0||fh<=0)return null;
        float scale=Math.min(1f,224f/Math.max(fw,fh));
        int w=Math.max(48,Math.round(fw*scale)),h=Math.max(48,Math.round(fh*scale));
        Bitmap tiny=null;
        try{
            tiny=Bitmap.createScaledBitmap(frame,w,h,true);
            int[] colors=new int[w*h];
            tiny.getPixels(colors,0,w,0,0,w,h);
            byte[] gray=new byte[w*h];
            for(int i=0;i<gray.length;i++){
                int p=colors[i];
                int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
                gray[i]=(byte)((77*r+150*g+29*b+128)>>8);
            }
            return new Gray(gray,w,h);
        }finally{
            if(tiny!=null&&tiny!=frame&&!tiny.isRecycled())tiny.recycle();
        }
    }
}