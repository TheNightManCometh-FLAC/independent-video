package android.media;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Scripted platform boundary for Exporter drain tests. Never included in the APK. */
public final class MediaCodec {
    public static final int INFO_TRY_AGAIN_LATER=-1, INFO_OUTPUT_FORMAT_CHANGED=-2, INFO_OUTPUT_BUFFERS_CHANGED=-3;
    public static final int BUFFER_FLAG_CODEC_CONFIG=2, BUFFER_FLAG_END_OF_STREAM=4;
    public static final class BufferInfo {
        public int offset,size,flags;public long presentationTimeUs;
        public void set(int offset,int size,long pts,int flags){this.offset=offset;this.size=size;presentationTimeUs=pts;this.flags=flags;}
    }
    public final ArrayDeque<int[]> output=new ArrayDeque<int[]>();
    public int outputPolls,released,inputEos,pcmInputs;
    public boolean eosOnInput;
    public final ByteBuffer data=ByteBuffer.wrap(new byte[]{99,98,1,2,3,4,97,96});
    public MediaCodec event(int status,int size,int flags){output.add(new int[]{status,size,flags});return this;}
    public int dequeueOutputBuffer(BufferInfo info,long timeout){outputPolls++;int[] e=output.poll();if(e==null)return -1;info.set(e[1]>0?2:0,e[1],12345,e[2]);return e[0];}
    public ByteBuffer getOutputBuffer(int index){return data.duplicate();}
    public void releaseOutputBuffer(int index,boolean render){released++;}
    public MediaFormat getOutputFormat(){return new MediaFormat();}
    public int dequeueInputBuffer(long timeout){return 0;}
    public ByteBuffer getInputBuffer(int index){return ByteBuffer.allocate(4096);}
    public void queueInputBuffer(int index,int offset,int size,long pts,int flags){if((flags&4)!=0){inputEos++;if(eosOnInput)event(0,0,4);}else pcmInputs++;}
}
