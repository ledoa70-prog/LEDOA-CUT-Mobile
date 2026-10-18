package kr.ledoa.cut.aitest;
import java.util.*;

/** Geometry-only, deterministic helper for on-device ROI face detection.
 *  Outputs regions, never claims a person identity and never draws a mask.
 */
public final class FaceRoi {
    private FaceRoi(){}
    public static final class Region {
        public final float left,top,right,bottom;
        public Region(float l,float t,float r,float b){
            left=clamp(l);top=clamp(t);right=clamp(r);bottom=clamp(b);
        }
        public float width(){return right-left;}
        public float height(){return bottom-top;}
        public boolean valid(){return width()>=.22f && height()>=.18f;}
        public int[] pixels(int width,int height){
            if(!valid()||width<=0||height<=0)return null;
            int l=Math.max(0,Math.min(width-1,(int)Math.floor(left*width)));
            int t=Math.max(0,Math.min(height-1,(int)Math.floor(top*height)));
            int r=Math.max(l+1,Math.min(width,(int)Math.ceil(right*width)));
            int b=Math.max(t+1,Math.min(height,(int)Math.ceil(bottom*height)));
            if(r-l<96||b-t<96)return null;
            return new int[]{l,t,r,b};
        }
    }
    private static float clamp(float n){return Math.max(0f,Math.min(1f,n));}
    private static Region around(float x,float y,float halfW,float halfH){
        float l=Math.max(0,x-halfW),r=Math.min(1,x+halfW);
        float t=Math.max(0,y-halfH),b=Math.min(1,y+halfH);
        // If clipped at an edge, don't shift the ROI onto a different person.
        return new Region(l,t,r,b);
    }
    public static Region faceRegion(FacePath.Box face){
        if(face==null)return null;
        // Close-up faces must be assessed with a smaller ROI rather than feeding
        // the same full image back to the ML model.
        float hx=Math.min(.40f,Math.max(.20f,face.w*.67f));
        float hy=Math.min(.39f,Math.max(.19f,face.h*.81f));
        return around(face.cx(),face.cy(),hx,hy);
    }
    public static Region bodyHeadRegion(BodyClothing.Assist body){
        if(body==null||!body.confirmed||body.confidence<.50f)return null;
        float hx=Math.max(.17f,Math.min(.38f,body.shoulderWidth*1.08f));
        float hy=Math.max(.17f,Math.min(.30f,body.shoulderWidth*.86f));
        return around(body.headX,body.headY,hx,hy);
    }
    public static boolean plausible(FacePath.Box previous,FacePath.Box candidate,BodyClothing.Assist body){
        if(candidate==null||!candidate.faceEvidence)return false;
        if(body!=null&&body.confirmed&&body.confidence>=.65f&&body.near(candidate))return true;
        if(previous==null)return false;
        float ratio=candidate.area()/Math.max(.0001f,previous.area());
        return candidate.dist(previous)<.33f && ratio>.34f && ratio<4.5f;
    }
    public static boolean needRescue(List<FacePath.Box> full,FacePath.Box last,
                                      BodyClothing.Assist body){
        if(last==null)return false;
        if(full==null||full.isEmpty())return true;
        if(full.size()>5)return true;
        for(FacePath.Box c:full)if(plausible(last,c,body))return false;
        return true;
    }
    public static boolean acceptCrop(FacePath.Box candidate,FacePath.Box last,
                                      BodyClothing.Assist body) {
        if(candidate==null||!candidate.faceEvidence||candidate.appearance==null)return false;
        if(last!=null){
            float ratio=candidate.area()/Math.max(.0001f,last.area());
            if(ratio<.22f||ratio>5.5f)return false;
            if(candidate.area()<.003f && last.area()>.018f)return false;
        }
        if(body!=null&&body.confirmed&&body.confidence>=.55f &&
           candidate.w/Math.max(.03f,body.shoulderWidth)<.30f)return false;
        if(body!=null&&body.confidence>=.55f&&body.confirmed&&body.near(candidate))
            return true;
        if(last==null)return false;
        // An actual cropped ML Kit face must remain near the last face; the
        // FacePath engine applies appearance checks and multi-frame confirmation.
        return candidate.dist(last)<=.54f;
    }
    public static List<Region> regions(FacePath.Box face,BodyClothing.Assist body){
        ArrayList<Region> regions=new ArrayList<>();
        Region faceArea=faceRegion(face);
        Region bodyArea=bodyHeadRegion(body);
        if(faceArea!=null&&faceArea.valid())regions.add(faceArea);
        if(bodyArea!=null&&bodyArea.valid()){
            if(faceArea==null || Math.hypot(
                (faceArea.left+faceArea.right-bodyArea.left-bodyArea.right)/2,
                (faceArea.top+faceArea.bottom-bodyArea.top-bodyArea.bottom)/2)>.12)
                regions.add(bodyArea);
        }
        return regions;
    }
}
