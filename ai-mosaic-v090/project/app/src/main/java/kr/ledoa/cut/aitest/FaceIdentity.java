package kr.ledoa.cut.aitest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceRecognizerSF;
import java.io.*;

/** In-memory, per-video identity descriptors. No names, face files, uploads, or
 * descriptor persistence. Apache-2.0 OpenCV SFace int8 model is bundled in APK. */
final class FaceIdentity implements AutoCloseable {
    private FaceRecognizerSF network;
    private final Mat rgba=new Mat(),bgr=new Mat(),aligned=new Mat(),feature=new Mat(),landmarks=new Mat(1,15,CvType.CV_32F);
    // Android AAR exports opencv_java4, while Core.NATIVE_LIBRARY_NAME is the desktop name.
    static {System.loadLibrary("opencv_java4");}
    FaceIdentity(Context context)throws IOException{
        Core.setNumThreads(2);
        File model=new File(context.getFilesDir(),"sface-2b0e941e.onnx");
        if(!model.exists()||model.length()!=9896933){
            try(InputStream in=context.getAssets().open("sface_int8.onnx");OutputStream out=new FileOutputStream(model)){
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
        }
        network=FaceRecognizerSF.create(model.getAbsolutePath(),"");
    }
    void begin(Bitmap bitmap){Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,bgr,Imgproc.COLOR_RGBA2BGR);}
    float[] describe(Face face){
        FaceLandmark le=face.getLandmark(FaceLandmark.LEFT_EYE),re=face.getLandmark(FaceLandmark.RIGHT_EYE),nose=face.getLandmark(FaceLandmark.NOSE_BASE),lm=face.getLandmark(FaceLandmark.MOUTH_LEFT),rm=face.getLandmark(FaceLandmark.MOUTH_RIGHT);
        if(le==null||re==null||nose==null||lm==null||rm==null)return crop(face.getBoundingBox());
        PointF a=le.getPosition(),b=re.getPosition(),c=lm.getPosition(),d=rm.getPosition();
        if(a.x>b.x){PointF swap=a;a=b;b=swap;}if(c.x>d.x){PointF swap=c;c=d;d=swap;}
        android.graphics.Rect r=face.getBoundingBox();PointF n=nose.getPosition();
        landmarks.put(0,0,new float[]{r.left,r.top,r.width(),r.height(),a.x,a.y,b.x,b.y,n.x,n.y,c.x,c.y,d.x,d.y,1});
        network.alignCrop(bgr,landmarks,aligned);return extract();
    }
    float[] crop(android.graphics.Rect r){
        int l=Math.max(0,r.left),t=Math.max(0,r.top),right=Math.min(bgr.cols(),r.right),bottom=Math.min(bgr.rows(),r.bottom);
        if(right-l<16||bottom-t<16)return null;
        Mat roi=bgr.submat(new Rect(l,t,right-l,bottom-t));try{Imgproc.resize(roi,aligned,new Size(112,112));return extract();}finally{roi.release();}
    }
    private float[] extract(){
        network.feature(aligned,feature);float[] values=new float[(int)feature.total()];feature.get(0,0,values);
        if(values.length!=128)throw new IllegalStateException("Unexpected SFace feature dimension");
        double norm=0;for(float v:values)norm+=v*v;norm=Math.sqrt(norm);if(norm<1e-9)return null;
        for(int i=0;i<values.length;i++)values[i]/=norm;return values;
    }
    public void close(){rgba.release();bgr.release();aligned.release();feature.release();landmarks.release();network=null;}
}
