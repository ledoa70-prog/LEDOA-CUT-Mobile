package kr.ledoa.cut;
import java.io.*;
import java.util.*;
/** Runs the exact APK alignment class on acoustic logits from real Korean audio. */
public final class AlignmentRealAudioTest {
 public static void main(String[] args)throws Exception{
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))){
   HashMap<String,Integer> vocab=new HashMap<String,Integer>();int size=in.readInt();for(int i=0;i<size;i++)vocab.put(in.readUTF(),in.readInt());
   int cases=in.readInt();System.out.println("{\"passed\":true,\"engine\":\"same CtcAlignment.java as APK\",\"cases\":[");
   for(int c=0;c<cases;c++){
    String name=in.readUTF();double duration=in.readDouble();int rows=in.readInt();ArrayList<String> text=new ArrayList<String>();double[] starts=new double[rows],ends=new double[rows];
    for(int i=0;i<rows;i++){text.add(in.readUTF());starts[i]=in.readDouble();ends[i]=in.readDouble();}
    int frames=in.readInt(),tokens=in.readInt();float[][] p=new float[frames][tokens];for(float[] frame:p)for(int j=0;j<tokens;j++)frame[j]=in.readFloat();
    CtcAlignment.Transcript transcript=CtcAlignment.tokenize(text,vocab);java.util.List<CtcAlignment.Word> words=CtcAlignment.align(p,transcript,1204,.02,duration,()->{});
    double confidence=0,last=-1;int review=0;boolean[] hit=new boolean[rows];
    for(CtcAlignment.Word w:words){if(w.start<last-.001||w.end<=w.start)throw new AssertionError("Word order "+name);last=w.end;hit[w.cue]=true;confidence+=w.confidence;if(w.review)review++;
     if(w.start<starts[w.cue]-.08||w.end>ends[w.cue]+.08)throw new AssertionError("Word outside real speech sample: "+name+" "+w.text+" "+w.start+".."+w.end);
    }
    for(boolean value:hit)if(!value)throw new AssertionError("Lost paragraph");
    if(confidence/words.size()<.025)throw new AssertionError("Low real-audio confidence "+name);
    System.out.print((c>0?",":"")+"{\"name\":\""+name+"\",\"words\":"+words.size()+",\"review_words\":"+review+",\"average_confidence\":"+confidence/words.size()+",\"first_start\":"+words.get(0).start+",\"last_end\":"+words.get(words.size()-1).end+"}");
    boolean cancelled=false;try{CtcAlignment.align(p,transcript,1204,.02,duration,()->{throw new InterruptedException();});}catch(InterruptedException expected){cancelled=true;}if(!cancelled)throw new AssertionError("Cancellation");
   }
   float[][] silence=new float[100][1205];for(float[] frame:silence){Arrays.fill(frame,-25);frame[1204]=0;}
   for(CtcAlignment.Word w:CtcAlignment.align(silence,CtcAlignment.tokenize(Arrays.asList("없는 문장"),vocab),1204,.02,2,()->{}))if(w.confidence>.001||!w.review)throw new AssertionError("Silence accepted as certain");
   if(!CtcAlignment.spoken("24시").equals("이십사시")||!CtcAlignment.spoken("가나").equals("가나"))throw new AssertionError("Normalization");
   System.out.println("],\"silence_low_confidence\":true,\"cancellation\":true,\"numeric_and_jamo_normalization\":true,\"physical_android_test\":false}");
  }
 }
}
