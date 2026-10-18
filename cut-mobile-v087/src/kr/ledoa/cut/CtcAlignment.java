package kr.ledoa.cut;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transcript-constrained CTC Viterbi alignment. No interpolation or text-length timing. */
public final class CtcAlignment {
    public interface Check { void check() throws InterruptedException; }
    public static final class Word {
        public final String text; public final int cue, first, last; public final boolean unsupported;
        public double start, end, confidence; public boolean review;
        Word(String t,int c,int f,int l,boolean u){text=t;cue=c;first=f;last=l;unsupported=u;}
    }
    public static final class Transcript {
        public final int[] labels; public final List<Word> words;
        Transcript(int[] l,List<Word> w){labels=l;words=w;}
    }
    private static final String[] DIGITS={"영","일","이","삼","사","오","육","칠","팔","구"};
    private static final String[] LETTERS={"에이","비","씨","디","이","에프","지","에이치","아이","제이","케이","엘","엠","엔","오","피","큐","알","에스","티","유","브이","더블유","엑스","와이","지"};
    static String spoken(String s){
        s=Normalizer.normalize(s,Normalizer.Form.NFKC);
        Matcher numbers=Pattern.compile("[0-9]+").matcher(s);StringBuffer out=new StringBuffer();
        while(numbers.find()){
            String n=numbers.group();StringBuilder v=new StringBuilder();
            if(n.length()<=4&&!n.startsWith("0")){
                String[] units={"","십","백","천"};for(int i=0;i<n.length();i++){int d=n.charAt(i)-'0',place=n.length()-i-1;if(d>0){if(d!=1||place==0)v.append(DIGITS[d]);v.append(units[place]);}}
            }else for(int i=0;i<n.length();i++)v.append(DIGITS[n.charAt(i)-'0']);
            numbers.appendReplacement(out,Matcher.quoteReplacement(v.toString()));
        }numbers.appendTail(out);
        StringBuilder result=new StringBuilder();
        for(int i=0;i<out.length();i++){char c=out.charAt(i);if(c>='a'&&c<='z')c=(char)(c-32);if(c>='A'&&c<='Z')result.append(LETTERS[c-'A']);else if(Character.isLetter(c))result.append(c);}
        return result.toString();
    }
    public static Transcript tokenize(List<String> cues,Map<String,Integer> vocab){
        ArrayList<Integer> labels=new ArrayList<Integer>();ArrayList<Word> words=new ArrayList<Word>();
        int delimiter=vocab.get("|"),unknown=vocab.get("[UNK]");
        for(int cue=0;cue<cues.size();cue++){
            int before=words.size();Matcher m=Pattern.compile("\\S+").matcher(cues.get(cue));
            while(m.find()){
                String raw=m.group(),normal=spoken(raw);if(normal.isEmpty())continue;
                if(!labels.isEmpty())labels.add(delimiter);int first=labels.size();boolean unsupported=raw.matches(".*[A-Za-z0-9].*");
                for(int i=0;i<normal.length();i++){Integer id=vocab.get(normal.substring(i,i+1));if(id==null){id=unknown;unsupported=true;}labels.add(id);}
                words.add(new Word(raw,cue,first,labels.size()-1,unsupported));
            }
            if(before==words.size())throw new IllegalArgumentException((cue+1)+"번 문단에 읽을 수 있는 글자가 없습니다.");
        }
        if(labels.isEmpty()||labels.size()>1800)throw new IllegalArgumentException("한 번에 정렬할 문장을 더 짧게 나눠 주세요.");
        int[] ids=new int[labels.size()];for(int i=0;i<ids.length;i++)ids[i]=labels.get(i);return new Transcript(ids,words);
    }
    public static List<Word> align(float[][] logp,Transcript transcript,int blank,double step,double duration,Check check) throws InterruptedException {
        int[] labels=transcript.labels;int frames=logp.length,states=labels.length*2+1;
        if(frames<labels.length)throw new IllegalArgumentException("영상 길이에 비해 자막이 너무 깁니다. 원본 음성과 문장을 확인해 주세요.");
        byte[][] back=new byte[frames][states];float[] previous=new float[states],next=new float[states];Arrays.fill(previous,Float.NEGATIVE_INFINITY);
        previous[0]=logp[0][blank];previous[1]=logp[0][labels[0]];
        for(int t=1;t<frames;t++){
            if(t%32==0)check.check();Arrays.fill(next,Float.NEGATIVE_INFINITY);
            int maximum=Math.min(states-1,2*t+1);
            for(int s=0;s<=maximum;s++){
                float best=previous[s];byte move=0;
                if(s>0&&previous[s-1]>best){best=previous[s-1];move=1;}
                if(s>1&&(s&1)==1&&labels[s/2]!=labels[s/2-1]&&previous[s-2]>best){best=previous[s-2];move=2;}
                next[s]=best+logp[t][(s&1)==0?blank:labels[s/2]];back[t][s]=move;
            }float[] swap=previous;previous=next;next=swap;
        }
        int state=previous[states-1]>previous[states-2]?states-1:states-2;
        if(!Float.isFinite(previous[state]))throw new IllegalArgumentException("이 문장을 음성에 맞출 수 없습니다. 오타와 인식 구간을 확인해 주세요.");
        int[] first=new int[labels.length],last=new int[labels.length],count=new int[labels.length];double[] sum=new double[labels.length];Arrays.fill(first,-1);
        for(int t=frames-1;t>=0;t--){
            if((state&1)==1){int token=state/2;first[token]=t;if(last[token]==0)last[token]=t;sum[token]+=logp[t][labels[token]];count[token]++;}
            if(t>0)state-=back[t][state];
        }
        for(Word word:transcript.words){
            double score=0;int n=0;word.start=duration;word.end=0;
            for(int token=word.first;token<=word.last;token++){
                if(first[token]<0)throw new IllegalArgumentException("일부 단어를 정렬하지 못했습니다. 문장과 음성을 확인해 주세요.");
                word.start=Math.min(word.start,first[token]*step);word.end=Math.max(word.end,Math.min(duration,(last[token]+1)*step));
                score+=sum[token]/Math.max(1,count[token]);n++;
            }
            word.confidence=Math.exp(score/Math.max(1,n));word.review=word.unsupported||word.confidence<.12||word.end<=word.start;
        }
        return transcript.words;
    }
    public static void logSoftmax(float[] values){
        float max=-Float.MAX_VALUE;for(float v:values)max=Math.max(max,v);double sum=0;for(float v:values)sum+=Math.exp(v-max);double z=max+Math.log(sum);for(int i=0;i<values.length;i++)values[i]=(float)(values[i]-z);
    }
}
