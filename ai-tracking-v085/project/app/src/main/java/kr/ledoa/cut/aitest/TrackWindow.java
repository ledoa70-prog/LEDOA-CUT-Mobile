package kr.ledoa.cut.aitest;

/**
 * Half-open tracking interval [startMs,endMs).
 * No inference, face selection, overlay, or JSON sample is authorized outside it.
 */
public final class TrackWindow {
    public final long startMs,endMs;
    public TrackWindow(long startMs,long endMs,long durationMs){
        if(durationMs<=0 || startMs<0 || endMs>durationMs ||
            endMs-startMs<200)throw new IllegalArgumentException(
                "시작은 종료보다 최소 0.2초 앞서야 하며 영상 길이를 초과할 수 없습니다.");
        this.startMs=startMs;this.endMs=endMs;
    }
    public boolean contains(long timestampMs){
        return timestampMs>=startMs && timestampMs<endMs;
    }
    public boolean mayAnalyze(long timestampMs){return contains(timestampMs);}
    public long durationMs(){return endMs-startMs;}
    /** accepts mm:ss.SSS or hh:mm:ss.SSS; no hidden clock rounding */
    public static long parseTime(String input){
        if(input==null)throw new IllegalArgumentException("시간을 입력해 주세요.");
        String value=input.trim();
        if(value.isEmpty())throw new IllegalArgumentException("시간을 입력해 주세요.");
        String[] parts=value.split(":",-1);
        if(parts.length<2||parts.length>3)throw new IllegalArgumentException(
            "시간 형식: 분:초.밀리초 (예: 01:23.500)");
        long hour=0,minute,second,millis=0;
        try{
            if(parts.length==3){
                hour=Long.parseLong(parts[0]);
                minute=Long.parseLong(parts[1]);
            }else minute=Long.parseLong(parts[0]);
            String[] decimal=parts[parts.length-1].split("\\.",-1);
            if(decimal.length>2 || decimal.length==0 ||
                 decimal[0].isEmpty())throw new NumberFormatException();
            second=Long.parseLong(decimal[0]);
            if(decimal.length==2){
                if(decimal[1].length()<1||decimal[1].length()>3)
                    throw new NumberFormatException();
                for(char c:decimal[1].toCharArray())
                    if(c<'0'||c>'9')throw new NumberFormatException();
                millis=Long.parseLong((decimal[1]+"000").substring(0,3));
            }
        }catch(NumberFormatException err){throw new IllegalArgumentException(
            "시간 형식: 분:초.밀리초 (예: 01:23.500)");}
        if(hour<0||minute<0||second<0||second>=60 ||
           (parts.length==3&&minute>=60))
            throw new IllegalArgumentException("분·초 범위를 확인해 주세요.");
        try{
            return Math.addExact(Math.multiplyExact(
                Math.addExact(Math.multiplyExact(hour,3600L),
                    Math.addExact(Math.multiplyExact(minute,60L),second)),1000L),millis);
        }catch(ArithmeticException err){throw new IllegalArgumentException("시간이 너무 큽니다.");}
    }
    public static String formatTime(long millis){
        long safe=Math.max(0,millis);
        long hours=safe/3600000,minutes=safe/60000%60,
             seconds=safe/1000%60,sub=safe%1000;
        if(hours>0)return String.format(java.util.Locale.KOREA,
            "%02d:%02d:%02d.%03d",hours,minutes,seconds,sub);
        return String.format(java.util.Locale.KOREA,
            "%02d:%02d.%03d",safe/60000,seconds,sub);
    }
}