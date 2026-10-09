package kr.ledoa.cut.aitest;

/** Lightweight, entirely local coarse appearance descriptor.
 *  Not biometric identity recognition. Requires motion/temporal evidence as well.
 */
public final class FaceAppearance {
    private FaceAppearance(){}
    public static final int SIZE=24;
    private static final int BINS=12;
    private static final int COLOR=2*BINS;
    private static final int TEXTURE=64;
    public static float[] describe(int[] pixels,int width,int height){
        if(pixels==null||width<8||height<8||pixels.length<width*height)return null;
        float[] v=new float[COLOR+TEXTURE];
        float[] samples=new float[TEXTURE];
        int total=0;
        for(int y=height/8;y<height*7/8;y++)for(int x=width/8;x<width*7/8;x++){
            int p=pixels[y*width+x];int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
            float sum=r+g+b+1f;
            int a=Math.min(BINS-1,Math.max(0,(int)(BINS*(r/sum))));
            int c=Math.min(BINS-1,Math.max(0,(int)(BINS*(b/sum))));
            v[a]++;v[BINS+c]++;total++;
        }
        if(total<=0)return null;
        for(int i=0;i<COLOR;i++)v[i]/=total;
        float avg=0;
        for(int gy=0;gy<8;gy++)for(int gx=0;gx<8;gx++){
            int sx=Math.min(width-1,(int)((gx+.5f)*width/8));
            int sy=Math.min(height-1,(int)((gy+.5f)*height/8));
            int p=pixels[sy*width+sx];
            float intensity=.299f*((p>>16)&255)+.587f*((p>>8)&255)+.114f*(p&255);
            samples[gy*8+gx]=intensity;avg+=intensity;
        }
        avg/=TEXTURE;float var=0;
        for(float a:samples)var+=(a-avg)*(a-avg);
        float std=(float)Math.sqrt(var/TEXTURE)+8f;
        for(int i=0;i<TEXTURE;i++)v[COLOR+i]=(samples[i]-avg)/std;
        return v;
    }
    public static float score(float[] a,float[] b){
        if(a==null||b==null||a.length!=COLOR+TEXTURE||b.length!=COLOR+TEXTURE)return -1;
        float intersection=0;
        for(int i=0;i<COLOR;i++)intersection+=Math.min(a[i],b[i]);
        intersection=Math.min(1,Math.max(0,intersection/2));
        float dot=0,sa=0,sb=0;
        for(int i=COLOR;i<a.length;i++){float x=a[i],y=b[i];dot+=x*y;sa+=x*x;sb+=y*y;}
        float cosine=sa*sb<.001f?0:dot/(float)Math.sqrt(sa*sb);
        return .68f*intersection+.32f*(.5f+.5f*Math.max(-1,Math.min(1,cosine)));
    }
}
