package kr.ledoa.cut.aitest;

/** A cut is a hard boundary: optical flow and interpolation never cross it.
 * Combines spatial luminance change with histogram change; ordinary camera
 * pans with the same luminance distribution do not trigger the second gate. */
public final class SceneCuts {
    private byte[] previous;
    private int[] previousHistogram;
    private long previousMs=-1;
    public void reset(){previous=null;previousHistogram=null;previousMs=-1;}
    public boolean observe(long ms,byte[] gray,int w,int h){
        if(gray==null||gray.length!=w*h||w<1||h<1)return false;
        byte[] grid=new byte[32*24];int[] hist=new int[16];
        for(int y=0;y<24;y++)for(int x=0;x<32;x++){
            int value=gray[Math.min(h-1,(2*y+1)*h/48)*w+Math.min(w-1,(2*x+1)*w/64)]&255;
            grid[y*32+x]=(byte)value;hist[value>>4]++;
        }
        boolean cut=false;
        if(previous!=null&&ms>previousMs&&ms-previousMs<=300){
            int delta=0,large=0;double distance=0;
            for(int i=0;i<grid.length;i++){int d=Math.abs((grid[i]&255)-(previous[i]&255));delta+=d;if(d>40)large++;}
            for(int i=0;i<16;i++)distance+=Math.abs(hist[i]-previousHistogram[i]);
            double mad=delta/(double)grid.length,changed=large/(double)grid.length;
            distance/=2.0*grid.length;
            cut=(mad>30&&distance>.26&&changed>.40)||(mad>65&&changed>.78);
        }
        previous=grid;previousHistogram=hist;previousMs=ms;
        return cut;
    }
}
