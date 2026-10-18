package kr.ledoa.cut.aitest;
/** Reviewed-only 1-2 frame gap linking. No new identity is asserted. */
public final class SafeGapV086 {
    private SafeGapV086(){}
    public static boolean supported(FacePath.Point before,FacePath.Point after,
                                    FacePath.Point realAfter,int missing){
        if(before==null||after==null||realAfter==null||
           before.box==null||after.box==null||realAfter.box==null)return false;
        if(missing<1||missing>2||after.ms-before.ms>320 ||
           realAfter.ms-after.ms>320)return false;
        if(after.status!=FacePath.Status.DETECTION_BRIDGED)return false;
        if(realAfter.status!=FacePath.Status.TRACKED&&
           realAfter.status!=FacePath.Status.VERIFIED)return false;
        if(before.status!=FacePath.Status.FLOW_ESTIMATED &&
           before.status!=FacePath.Status.TRACKED &&
           before.status!=FacePath.Status.DETECTION_BRIDGED &&
           before.status!=FacePath.Status.VERIFIED)return false;
        return ContinuityBridge.agrees(before.box,after.box) &&
               ContinuityBridge.agrees(after.box,realAfter.box);
    }
    public static FacePath.Box interpolate(FacePath.Box left,FacePath.Box right,float f){
        if(left==null||right==null||f<=0||f>=1)return null;
        float x=left.x+(right.x-left.x)*f,y=left.y+(right.y-left.y)*f;
        float w=left.w+(right.w-left.w)*f,h=left.h+(right.h-left.h)*f;
        float x2=Math.min(1f,x+w),y2=Math.min(1f,y+h);
        x=Math.max(0f,x);y=Math.max(0f,y);
        if(x2-x<.04f||y2-y<.04f)return null;
        return new FacePath.Box(x,y,x2-x,y2-y,-1,null,false,false);
    }
}