package kr.ledoa.cut.aitest;
import java.util.*;
public final class FaceBodyV076Test {
    private static int checks=0;
    private static void ok(boolean yes,String reason){checks++;if(!yes)throw new AssertionError(reason);}
    private static float[] descriptor(int rgb){
        int[] pixels=new int[576];for(int y=0;y<24;y++)for(int x=0;x<24;x++){
            int d=((x*3+y*7+x*y)%27)-13;
            int r=Math.max(0,Math.min(255,((rgb>>16)&255)+d));
            int g=Math.max(0,Math.min(255,((rgb>>8)&255)+d));
            int b=Math.max(0,Math.min(255,(rgb&255)+d));
            pixels[y*24+x]=0xff000000 | r<<16 | g<<8 | b;
        }
        return FaceAppearance.describe(pixels,24,24);
    }
    private static FacePath.Box face(float x,float y,float w,int id,float[] texture) {
        return new FacePath.Box(x,y,w,w*.64f,id,texture,true,false);
    }
    private static BodyClothing.Assist body(float hx,float hy,float confidence) {
        BodyClothing.Observation pose=new BodyClothing.Observation(hx,hy,hx,hy+.25f,
                .35f,.95f,descriptor(0x224D80));
        return new BodyClothing.Assist(pose,confidence,true);
    }
    public static void main(String[] args) {
        float[] chosen=descriptor(0xAB835F),wrong=descriptor(0x1064C8);
        FacePath f=new FacePath();
        f.anchor(0,face(.11f,.17f,.20f,7,chosen));
        BodyClothing.Assist assist=body(.71f,.27f,.95f);
        // Target body moves away from the last confirmed face after a long occlusion.
        for(int ms=100;ms<=900;ms+=100){
            FacePath.Point p=f.step(ms,Collections.emptyList(),assist);
            ok(p.status==FacePath.Status.BODY_HELD && p.box==null,"body-only never creates a fake face "+ms);
        }
        FacePath.Point t=f.step(1000,Collections.singletonList(face(.61f,.18f,.20f,42,chosen)),assist);
        ok(t.status==FacePath.Status.UNCERTAIN,"long-gap return requires a confirmation frame");
        t=f.step(1100,Collections.singletonList(face(.615f,.175f,.20f,100,chosen)),assist);
        ok(t.status==FacePath.Status.TRACKED && t.reason.equals("BODY_ASSISTED_REACQUIRED"),
            "body reconnects changed face ID after second stable observation");
        ok(f.bodyRecoveredCount()==1,"rescue counted separately from ordinary tracking");
        t=f.step(1200,Collections.singletonList(face(.62f,.17f,.21f,99,chosen)),assist);
        ok(t.status==FacePath.Status.TRACKED && t.reason.equals("FACE_BODY_CORROBORATED"),
            "body and face match logged as corroboration, not as a rescue");
        ok(f.bodyValidatedCount()==1,"corroboration counter");
        f.reset();f.anchor(0,face(.11f,.17f,.20f,7,chosen));
        ok(f.bodyRecoveredCount()==0&&f.bodyValidatedCount()==0,"new person resets identity metrics");
        for(int ms=100;ms<=900;ms+=100)f.step(ms,Collections.emptyList(),assist);
        t=f.step(1000,Collections.singletonList(face(.61f,.18f,.20f,22,wrong)),assist);
        ok(t.status!=FacePath.Status.TRACKED,"matching shirt does not mean different face is same person");
        t=f.step(1100,Collections.singletonList(face(.62f,.18f,.20f,22,wrong)),assist);
        ok(t.status!=FacePath.Status.TRACKED,"repeated shirt but wrong face must never be accepted");
        f.reset();f.anchor(0,face(.0f,.14f,.24f,3,chosen));
        FacePath.Box partial1=new FacePath.Box(.012f,.14f,.20f,.128f,99,chosen,false,true);
        FacePath.Box partial2=new FacePath.Box(.019f,.14f,.20f,.128f,101,chosen,false,true);
        t=f.step(100,Collections.singletonList(partial1));
        ok(t.status==FacePath.Status.UNCERTAIN,"cropped face first frame cannot auto-mask");
        t=f.step(200,Collections.singletonList(partial2));
        ok(t.status==FacePath.Status.TRACKED && t.reason.equals("EDGE_FACE_REACQUIRED"),
           "stable cropped face resumes at frame edge");
        ok(f.edgeRecoveredCount()==1,"partial face edge rescue counted");
        f.reset();f.anchor(0,face(.0f,.14f,.24f,3,chosen));
        t=f.step(100,Collections.singletonList(new FacePath.Box(.012f,.14f,.20f,.128f,99,wrong,false,true)));
        ok(t.status!=FacePath.Status.TRACKED,"face appearance mismatch at edge rejected");
        t=f.step(200,Collections.singletonList(new FacePath.Box(.019f,.14f,.20f,.128f,99,wrong,false,true)));
        ok(t.status!=FacePath.Status.TRACKED,"same wrong-looking edge box never accepted");
        f.reset();f.anchor(0,face(.25f,.16f,.20f,3,chosen));
        t=f.step(100,Collections.emptyList(),body(.5f,.24f,.9f));
        ok(t.status==FacePath.Status.BODY_HELD&&f.interpolated(100)==null,
           "when face absent, preview cannot fabricate facial mask");
        t=f.step(200,Collections.singletonList(face(.8f,.22f,.13f,40,chosen)),body(.5f,.24f,.95f));
        ok(t.status!=FacePath.Status.TRACKED,"wrong face outside selected person's body is not masked");
        System.out.println("PASS: "+checks+" v0.7.6 cropped-edge and torso-guided face relink checks");
    }
}