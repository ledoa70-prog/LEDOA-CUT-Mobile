package kr.ledoa.cut.aitest;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
/** Emulator evidence survives UTP uninstalling its test application after the run. */
final class MosaicTestImages {
 static void save(Context context,Bitmap image,String name)throws IOException{
  ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LEDOA");
  Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Test image URI unavailable");
  try(OutputStream out=context.getContentResolver().openOutputStream(uri)){if(out==null||!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Test image unavailable");}
 }
}
