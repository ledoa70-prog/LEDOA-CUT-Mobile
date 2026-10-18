package kr.ledoa.cut;
import java.io.OutputStream;
/** Audio-clock arithmetic without Android dependencies, covered by host tests. */
public final class CaptionMath {
    public static final class Resampler {
        private final OutputStream output;private final long count;private long next=0;
        private double previousPosition=Double.NaN;private float previous=0;
        public Resampler(OutputStream output,long count){this.output=output;this.count=count;}
        public void accept(float sample,double position)throws Exception{
            if(!Float.isFinite(sample))sample=0;
            if(!Double.isFinite(position)||position<previousPosition)return;
            while(next<count&&next<=position){
                double value;
                if(Double.isNaN(previousPosition)||position-previousPosition>1600)
                    value=position-next<1?sample:0;
                else if(position==previousPosition)value=sample;
                else value=previous+(sample-previous)*Math.max(0,Math.min(1,(next-previousPosition)/(position-previousPosition)));
                write(value);next++;
            }
            previousPosition=position;previous=sample;
        }
        private void write(double value)throws Exception{int s=(int)Math.round(Math.max(-1,Math.min(1,value))*32767);output.write(s&255);output.write((s>>8)&255);}
        public void finish()throws Exception{while(next<count){write(0);next++;}}
    }
    public static double timeline(double clipStart,double trimStart,double speed,double sourceTime){
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("speed");
        return clipStart+(sourceTime-trimStart)/speed;
    }
}
