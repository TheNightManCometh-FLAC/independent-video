package android.media;
import java.nio.ByteBuffer;
/** Test-only sink records data passed by the real Exporter.write method. */
public final class MediaMuxer {
    public int samples,starts,tracks;public byte[] last;
    public int addTrack(MediaFormat format){return tracks++;}
    public void start(){starts++;}
    public void writeSampleData(int track,ByteBuffer data,MediaCodec.BufferInfo info){samples++;ByteBuffer b=data.duplicate();b.position(info.offset);b.limit(info.offset+info.size);last=new byte[info.size];b.get(last);}
}
