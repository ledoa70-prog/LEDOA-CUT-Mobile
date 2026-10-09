package kr.ledoa.cut.aitest;
public final class TrackWindowV0861Test {
    private static int n=0;
    private static void check(boolean pass,String description){
        n++;if(!pass)throw new AssertionError(description);
    }
    private static void bad(String time){
        try{TrackWindow.parseTime(time);throw new AssertionError("Accepted: "+time);}
        catch(IllegalArgumentException good){n++;}
    }
    private static void badRange(long start,long end,long duration){
        try{new TrackWindow(start,end,duration);throw new AssertionError("Bad range accepted");}
        catch(IllegalArgumentException good){n++;}
    }
    public static void main(String[] args){
        check(TrackWindow.parseTime("00:05.200")==5200,"milliseconds precise");
        check(TrackWindow.parseTime("01:12.345")==72345,"minute format");
        check(TrackWindow.parseTime("1:02:03.007")==3723007,"hours format");
        check(TrackWindow.parseTime("00:05")==5000,"whole seconds");
        check(TrackWindow.parseTime("00:05.5")==5500,"tenths format");
        check(TrackWindow.parseTime("00:05.05")==5050,"hundredths format");
        check(TrackWindow.formatTime(22186).equals("00:22.186"),"preserve precise video end");
        check(TrackWindow.formatTime(3723007).equals("01:02:03.007"),"hours roundtrip");
        bad("asdf");bad("5");bad("00:70");bad("01:20.0001");
        bad("-1:00");bad("00:00.");bad("1:61:00");bad("00:01.abc");
        badRange(5000,5000,18595);
        badRange(0,199,18595);
        badRange(12000,6000,18595);
        badRange(-100,1000,18595);
        badRange(0,19000,18595);
        TrackWindow w=new TrackWindow(5200,9000,18595);
        check(!w.contains(5199),"start before forbidden");
        check(w.contains(5200),"start inclusive");
        check(w.mayAnalyze(6000),"inside may be analysed");
        check(w.contains(8999),"end-1 allowed");
        check(!w.contains(9000),"end exclusive");
        check(!w.contains(14000),"outside future never analysed");
        check(w.durationMs()==3800,"user bounded work");
        TrackWindow whole=new TrackWindow(0,18595,18595);
        check(whole.contains(18594),"last video ms allowed");
        check(!whole.contains(18595),"half-open full clip");
        check(TrackWindow.parseTime(TrackWindow.formatTime(18595))==18595,"exact video end roundtrip");
        System.out.println("PASS: "+n+" range parse, exact end, reject invalid intervals and outside-mask boundary checks");
    }
}