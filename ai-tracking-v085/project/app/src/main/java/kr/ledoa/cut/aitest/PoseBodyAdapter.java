package kr.ledoa.cut.aitest;

import android.graphics.Bitmap;
import android.graphics.PointF;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;

/** Adapter to Google's bundled single-person pose model. Only the upper torso is sampled.
 * The pose model returns the most-prominent person, NOT an authenticated identity.
 */
final class PoseBodyAdapter {
    private PoseBodyAdapter(){}
    static BodyClothing.Observation detect(PoseDetector detector,Bitmap bitmap) throws Exception {
        if(detector==null || bitmap==null)return null;
        Pose p=Tasks.await(detector.process(InputImage.fromBitmap(bitmap,0)));
        PoseLandmark l=p.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark r=p.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        if(l==null||r==null||l.getInFrameLikelihood()<.55f||r.getInFrameLikelihood()<.55f)return null;
        float width=bitmap.getWidth(),height=bitmap.getHeight();
        PointF lp=l.getPosition(),rp=r.getPosition();
        float sx=(lp.x+rp.x)*.5f,sy=(lp.y+rp.y)*.5f;
        float sw=Math.abs(lp.x-rp.x);
        if(sw<width*.055f||sw>width*.92f)return null;
        PoseLandmark nose=p.getPoseLandmark(PoseLandmark.NOSE);
        float hx=sx,hy=sy-sw*.72f;
        float quality=Math.min(l.getInFrameLikelihood(),r.getInFrameLikelihood())*.83f;
        if(nose!=null && nose.getInFrameLikelihood()>.55f){
            hx=nose.getPosition().x;hy=nose.getPosition().y;
            quality=Math.min(1f,quality+.17f);
        }
        // A crop of the CHEST is much safer than sampling the face/skin as "clothing".
        PoseLandmark lh=p.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rh=p.getPoseLandmark(PoseLandmark.RIGHT_HIP);
        float bottom=sy+sw*.92f;
        if(lh!=null&&rh!=null&&lh.getInFrameLikelihood()>.4f&&rh.getInFrameLikelihood()>.4f){
            bottom=Math.min(bottom,(lh.getPosition().y+rh.getPosition().y)*.5f);
        }
        float leftX=Math.min(lp.x,rp.x)+sw*.16f;
        float rightX=Math.max(lp.x,rp.x)-sw*.16f;
        float topY=sy+sw*.14f;
        float bottomY=Math.min(height,bottom);
        int x=(int)Math.max(0,leftX),y=(int)Math.max(0,topY);
        int x2=(int)Math.min(width,rightX),y2=(int)Math.min(height,bottomY);
        if(x2-x<18 || y2-y<18 || (x2-x)*(y2-y)<bitmap.getWidth()*bitmap.getHeight()*.004f)
            return null;
        Bitmap crop=null,small=null;
        try{
            crop=Bitmap.createBitmap(bitmap,x,y,x2-x,y2-y);
            small=Bitmap.createScaledBitmap(crop,FaceAppearance.SIZE,FaceAppearance.SIZE,true);
            int[] pixels=new int[FaceAppearance.SIZE*FaceAppearance.SIZE];
            small.getPixels(pixels,0,FaceAppearance.SIZE,0,0,FaceAppearance.SIZE,FaceAppearance.SIZE);
            float[] color=FaceAppearance.describe(pixels,FaceAppearance.SIZE,FaceAppearance.SIZE);
            return new BodyClothing.Observation(hx/width,hy/height,sx/width,sy/height,
                                               sw/width,quality,color);
        }catch(RuntimeException e){
            return null;
        }finally{
            if(small!=null && !small.isRecycled())small.recycle();
            if(crop!=null && !crop.isRecycled())crop.recycle();
        }
    }
}
