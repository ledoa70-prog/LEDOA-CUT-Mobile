package kr.ledoa.cut.aitest;
import java.util.*;

public class FaceRoiV078Test {
    static int count=0;
    static void check(boolean condition,String message){count++;if(!condition)throw new AssertionError(message);}
    static FacePath.Box face(float x,float y,float w,float h){
        return new FacePath.Box(x,y,w,h,1,new float[88],true,false);
    }
    static BodyClothing.Assist body(float headX,float headY,float confidence){
        BodyClothing.Observation pose=new BodyClothing.Observation(
             headX,headY,headX,headY+.22f,.28f,.95f,new float[88]);
        return new BodyClothing.Assist(pose,confidence,true);
    }
    public static void main(String[] args){
        FacePath.Box anchor=face(.12f,.12f,.27f,.17f);
        check(!FaceRoi.needRescue(Collections.emptyList(),null,null),"No selected face => no crop search");
        check(FaceRoi.needRescue(Collections.emptyList(),anchor,null),"Missing face triggers crop search");
        check(!FaceRoi.needRescue(Collections.singletonList(
                 face(.13f,.13f,.28f,.18f)),anchor,null),
              "Plausible full-frame detection needs no retry");
        check(FaceRoi.needRescue(Collections.singletonList(
                 face(.50f,.12f,.49f,.37f)),anchor,null),
              "Rapid zoom and lateral jump trigger second detector");
        ArrayList<FacePath.Box> clutter=new ArrayList<>();
        for(int i=0;i<14;i++)clutter.add(face(.001f*i,.12f,.25f,.15f));
        check(FaceRoi.needRescue(clutter,anchor,null),"15 stray face detections must trigger ROI");
        FaceRoi.Region near=FaceRoi.faceRegion(anchor);
        check(near!=null&&near.valid(),"Last selected head has bounded search region");
        int[] pix=near.pixels(540,960);
        check(pix!=null&&pix[2]>pix[0]&&pix[3]>pix[1],"Mapped ROI valid in portrait pixels");
        FacePath.Box edge=face(0f,.08f,.61f,.34f);
        FaceRoi.Region edgeRegion=FaceRoi.faceRegion(edge);
        check(edgeRegion.left==0f,"Edge-truncated ROI must not shift to other subject");
        check(edgeRegion.width()<1f&&edgeRegion.height()<1f,"ROI narrows search to part of frame");
        BodyClothing.Assist assisted=body(.48f,.25f,.91f);
        FaceRoi.Region torsoRegion=FaceRoi.bodyHeadRegion(assisted);
        check(torsoRegion!=null&&torsoRegion.valid(),"Body-confirmed head supplies independent ROI");
        check(FaceRoi.regions(anchor,assisted).size()==2,"Distant torso and last face both searched");
        check(FaceRoi.bodyHeadRegion(body(.5f,.25f,.2f))==null,"Weak torso cannot drive search");
        check(FaceRoi.acceptCrop(face(.17f,.12f,.27f,.17f),anchor,null),
             "ML Kit crop face close to anchor is eligible");
        check(!FaceRoi.acceptCrop(face(.82f,.40f,.12f,.11f),anchor,null),
             "Stranger far away cannot be accepted without body link");
        FacePath.Box noEvidence=new FacePath.Box(.15f,.11f,.25f,.14f,-1,new float[88],false,false);
        check(!FaceRoi.acceptCrop(noEvidence,anchor,null),"Clothing patch without facial landmarks ignored");
        check(!FaceRoi.acceptCrop(new FacePath.Box(.14f,.12f,.26f,.15f,-1,null,true,false),anchor,null),
             "ROI needs actual sampled face pixels, not a fabricated rectangle");
        check(FaceRoi.acceptCrop(face(.5f,.19f,.24f,.16f),anchor,
             body(.61f,.27f,.91f)),"Body head enables face re-link at new location");
        FaceRoi.Region invalid=new FaceRoi.Region(.40f,.30f,.41f,.31f);
        check(invalid.pixels(540,960)==null,"Too-small crop is rejected");
        System.out.println("PASS: "+count+" ROI geometry, zoom-trigger and wrong-target regression cases");
    }
}