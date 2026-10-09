package kr.ledoa.cut.aitest;

import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import java.nio.*;
import java.util.*;

/** The same pixelation shader is used by the player and MP4 exporter. Coordinates
 * are normalized in displayed orientation, with (0,0) at the upper left. */
final class MosaicGl {
    static final int MAX_FACES=32;
    private int program,texture;
    private final FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] rectangles=new float[MAX_FACES*4];
    private static final String VERTEX="attribute vec2 aPosition; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=(aPosition+1.0)*0.5;}";
    private static final String FRAGMENT="#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uTexture; uniform mat4 uTransform; uniform vec4 uBoxes[32]; uniform int uCount; uniform float uColumns; uniform float uAspect; uniform int uRotation; varying vec2 vUv; void main(){ vec2 p=vec2(vUv.x,1.0-vUv.y); for(int i=0;i<32;i++){ if(i<uCount){vec4 b=uBoxes[i]; if(p.x>=b.x&&p.y>=b.y&&p.x<=b.z&&p.y<=b.w){float sx=max(0.00001,(b.z-b.x)/uColumns);float sy=sx*uAspect;vec2 cell=vec2(sx,sy);p=clamp(b.xy+(floor((p-b.xy)/cell)+0.5)*cell,b.xy,b.zw);break;}}} vec2 q=p; if(uRotation==90)q=vec2(p.y,1.0-p.x);else if(uRotation==180)q=vec2(1.0-p.x,1.0-p.y);else if(uRotation==270)q=vec2(1.0-p.y,p.x);gl_FragColor=texture2D(uTexture,(uTransform*vec4(q.x,1.0-q.y,0.0,1.0)).xy);}";
    int create(){
        quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        int vs=shader(GLES20.GL_VERTEX_SHADER,VERTEX),fs=shader(GLES20.GL_FRAGMENT_SHADER,FRAGMENT);
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
        int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
        int[] t=new int[1];GLES20.glGenTextures(1,t,0);texture=t[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        return texture;
    }
    private static int shader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));return s;}
    void draw(float[] transform,int rotation,int width,int height,List<FacePath.Box> boxes,int strength,int margin){
        if(boxes.size()>MAX_FACES)throw new IllegalArgumentException("한 화면의 얼굴이 32명을 초과합니다.");
        GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glUseProgram(program);
        int count=boxes.size();
        for(int i=0;i<count;i++){FacePath.Box b=boxes.get(i).expanded(margin/100f);int k=i*4;rectangles[k]=b.x;rectangles[k+1]=b.y;rectangles[k+2]=b.x+b.w;rectangles[k+3]=b.y+b.h;}
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uCount"),count);
        if(count>0)GLES20.glUniform4fv(GLES20.glGetUniformLocation(program,"uBoxes"),count,rectangles,0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uColumns"),Math.max(3,30-strength*27/100));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uAspect"),width/(float)Math.max(1,height));
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uRotation"),rotation);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uTransform"),1,false,transform,0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0);
        int position=GLES20.glGetAttribLocation(program,"aPosition");quad.position(0);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(position);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("모자이크 화면 처리 오류");
    }
    void release(){if(program!=0)GLES20.glDeleteProgram(program);if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);program=texture=0;}
}
