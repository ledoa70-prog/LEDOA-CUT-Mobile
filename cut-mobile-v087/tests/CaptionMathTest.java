package kr.ledoa.cut;
import java.io.ByteArrayOutputStream;
public class CaptionMathTest {
 private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 public static void main(String[] args)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();CaptionMath.Resampler r=new CaptionMath.Resampler(bytes,16000);
  for(int i=0;i<48000;i++)r.accept(.5f,i/3.0);r.finish();byte[] pcm=bytes.toByteArray();check(pcm.length==32000,"one second output");
  for(int i=0;i<pcm.length;i+=2){short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));check(Math.abs(sample-16384)<=1,"amplitude");}
  bytes=new ByteArrayOutputStream();r=new CaptionMath.Resampler(bytes,16000);
  for(int i=0;i<48000;i++)r.accept(.5f,i/3.0-8000);r.finish();pcm=bytes.toByteArray();check(pcm.length==32000,"trim/pad duration");
  for(int i=16000;i<pcm.length;i++)check(pcm[i]==0,"tail silence");
  bytes=new ByteArrayOutputStream();r=new CaptionMath.Resampler(bytes,100);r.accept(Float.NaN,50);r.finish();for(byte b:bytes.toByteArray())check(b==0,"missing audio silence");
  check(CaptionMath.timeline(10,5,2,9)==12,"trim/speed time mapping");
  boolean failed=false;try{CaptionMath.timeline(0,0,0,1);}catch(IllegalArgumentException expected){failed=true;}check(failed,"reject zero speed");
  System.out.println("PASS audio resampling, trim, silence, invalid speed");
 }
}
