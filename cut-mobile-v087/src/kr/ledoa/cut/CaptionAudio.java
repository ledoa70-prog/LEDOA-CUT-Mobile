package kr.ledoa.cut;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded, seekable audio extraction. Never plays sound into a microphone. */
final class CaptionAudio {
    interface Progress { void update(int percent) throws Exception; }
    static void extract(Context context, Uri uri, File pcm, double start, double end,
                        Progress progress) throws Exception {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end-start > 120.01)
            throw new IllegalArgumentException("한 번에 원본 음성 120초까지 시험할 수 있습니다.");
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat format = null;
            for (int i=0; i<extractor.getTrackCount(); i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if (mime!=null && mime.startsWith("audio/")) {format=f;extractor.selectTrack(i);break;}
            }
            if (format==null) throw new IllegalArgumentException("이 파일에는 음성 트랙이 없습니다.");
            long from=(long)Math.round(start*1000000), to=(long)Math.round(end*1000000);
            extractor.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format,null,null,0);codec.start();
            boolean inputEnd=false, outputEnd=false;
            long deadline=System.nanoTime()+180000000000L;
            int last=-1;
            try (OutputStream output=new BufferedOutputStream(new FileOutputStream(pcm),65536)) {
                CaptionMath.Resampler resampler=new CaptionMath.Resampler(output,(long)Math.round((end-start)*16000));
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                while(!outputEnd) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if(System.nanoTime()>deadline) throw new IllegalStateException("음성 추출 시간이 초과되었습니다. 더 짧은 구간으로 시도해 주세요.");
                    if(!inputEnd) {
                        int ix=codec.dequeueInputBuffer(10000);
                        if(ix>=0) {
                            ByteBuffer data=codec.getInputBuffer(ix);long at=extractor.getSampleTime();
                            int size=at<0||at>=to?-1:extractor.readSampleData(data,0);
                            if(size<0){codec.queueInputBuffer(ix,0,0,Math.max(0,at),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}
                            else {codec.queueInputBuffer(ix,0,size,at,0);extractor.advance();}
                        }
                    }
                    int ix=codec.dequeueOutputBuffer(info,10000);
                    if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                        if(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT)
                            throw new IllegalStateException("지원하지 않는 음성 디코더 출력 형식입니다.");
                    } else if(ix>=0) {
                        ByteBuffer data=codec.getOutputBuffer(ix);
                        if(info.size>0&&data!=null) {
                            data.position(info.offset);data.limit(info.offset+info.size);data.order(ByteOrder.LITTLE_ENDIAN);
                            int width=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2, frame=0;
                            while(data.remaining()>=channels*width) {
                                float mono=0;
                                for(int c=0;c<channels;c++)mono+=width==4?data.getFloat():data.getShort()/32768f;
                                double relative=(info.presentationTimeUs-from)*.016+frame*(16000.0/rate);
                                resampler.accept(mono/channels,relative);frame++;
                            }
                            int percent=(int)Math.max(0,Math.min(99,(info.presentationTimeUs-from)*100/Math.max(1,to-from)));
                            if(percent!=last){last=percent;progress.update(percent);}
                        }
                        outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0||info.presentationTimeUs>=to;
                        codec.releaseOutputBuffer(ix,false);
                    }
                }
                resampler.finish();
            }
            progress.update(100);
        } catch(Exception error) {pcm.delete();throw error;}
        finally {
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}
            extractor.release();
        }
    }
}
