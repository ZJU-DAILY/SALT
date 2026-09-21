package compression.kangaroo;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class KangarooTest {
    private static void roundTrip(long[] values,Options options) {
        KangarooEncoder encoder=new KangarooEncoder(options);
        for(long v:values) encoder.addRaw(v);
        byte[] block=encoder.finish();
        assertArrayEquals(block,encoder.finish());
        KangarooDecoder decoder=new KangarooDecoder(block);
        assertEquals(values.length,decoder.size());
        for(int i=0;i<values.length;i++) assertEquals("record "+i,values[i],decoder.readRaw());
        assertFalse(decoder.hasNext());
        try {decoder.readRaw();fail("read beyond count");} catch(NoSuchElementException expected) { }
    }
    private static long[] bits(double... values) {
        long[] b=new long[values.length];for(int i=0;i<b.length;i++)b[i]=Double.doubleToRawLongBits(values[i]);return b;
    }

    @Test public void paperExamplesAndPrecisionFormulaCorrection() {
        double[] original={1.25,2.87,11.44};
        double[] transformed={1.25,2.8671875,11.46875};
        int[] tags={Erasure.UNCHANGED,Erasure.ERASED,Erasure.FLIPPED};
        for(int sign:new int[]{1,-1}) for(int i=0;i<original.length;i++) {
            long raw=Double.doubleToRawLongBits(sign*original[i]);
            Erasure.Result r=Erasure.encode(raw,true);
            assertEquals(tags[i],r.kind);assertEquals(Double.doubleToRawLongBits(sign*transformed[i]),r.stored);
            assertEquals(raw,Erasure.decode(r.stored,r.kind));
        }
        assertEquals(1,(int)Math.floor((52-3-44)*Math.log10(2)));
        assertEquals(2,Erasure.inferAlpha(3,44));
    }

    @Test public void specialValuesAndReportedFailures() {
        long[] data={0L,Long.MIN_VALUE,0x7ff0000000000000L,0xfff0000000000000L,0x7ff8000000000000L,
                0x7ff8000000001234L,0x7ff0000000000001L,0xfff8000000001234L,1L,Long.MIN_VALUE|1L,
                0x000fffffffffffffL,0x0010000000000000L,0x7fefffffffffffffL,0xffefffffffffffffL};
        long[] failures=bits(0.1234567890123456,1e-20,1.2345678901234567,88.51789,1.99175981e-6,1.99404736e-6,11.44,-11.44);
        long[] joined=Arrays.copyOf(data,data.length+failures.length);
        System.arraycopy(failures,0,joined,data.length,failures.length);
        for(Options.Search mode:Options.Search.values()) for(int w:new int[]{1,2,32,128})
            roundTrip(joined,new Options(w,mode,true,true));
    }

    @Test public void randomRawBitsAndDecimalValues() {
        Random rng=new Random(5986);
        long[] raw=new long[25000],decimal=new long[25000];
        for(int i=0;i<raw.length;i++) {
            raw[i]=rng.nextLong();
            decimal[i]=Double.doubleToRawLongBits(java.math.BigDecimal.valueOf(rng.nextInt(2000000)-1000000).scaleByPowerOfTen(-rng.nextInt(18)).doubleValue());
        }
        for(Options.Search mode:Options.Search.values()) {
            roundTrip(raw,new Options(32,mode,true,true));roundTrip(decimal,new Options(32,mode,true,true));
        }
    }

    @Test public void powersAndAdjacentRepresentableValues() {
        List<Long> list=new ArrayList<>();
        for(int e=-1074;e<=1023;e++) {
            double v=Math.scalb(1.0,e);
            for(double x:new double[]{v,Math.nextDown(v),Math.nextUp(v),-v,-Math.nextDown(v),-Math.nextUp(v)})
                list.add(Double.doubleToRawLongBits(x));
        }
        long[] data=new long[list.size()];for(int i=0;i<data.length;i++)data[i]=list.get(i);
        for(Options.Search mode:Options.Search.values()) roundTrip(data,new Options(32,mode,true,true));
    }

    @Test public void repetitionBoundariesAndNoRle() {
        long[] data=new long[20000];
        for(int i=0;i<data.length;i++) data[i]=i<18000?0x7ff8000000001234L:(i<19000?Long.MIN_VALUE:0L);
        for(Options.Search mode:Options.Search.values()) for(boolean rle:new boolean[]{true,false})
            for(boolean erase:new boolean[]{true,false}) roundTrip(data,new Options(2,mode,erase,rle));
        for(int n:new int[]{0,1,2,127,128,129,16384,16385}) roundTrip(new long[n],Options.defaults());
    }

    @Test public void compactMatchesExhaustiveSearchAfterWindowEviction() {
        Random rng=new Random(20260916);
        for(int window:new int[]{1,2,8,32,128}) {
            History compact=new History(window,Options.Search.COMPACT);
            History fast=new History(window,Options.Search.FAST);
            List<Long> seen=new ArrayList<>();
            for(int i=0;i<10000;i++) {
                long v=i%13==0 && !seen.isEmpty()?seen.get(rng.nextInt(seen.size())):(rng.nextLong()|1L)<<rng.nextInt(60);
                int optimal=-1,newestLead=-1;
                for(int j=Math.max(0,seen.size()-window);j<seen.size();j++) {
                    long h=seen.get(j);
                    if(Long.numberOfTrailingZeros(h)==Long.numberOfTrailingZeros(v)) {
                        newestLead=Long.numberOfLeadingZeros(v^h);optimal=Math.max(optimal,newestLead);
                    }
                }
                long ref=compact.select(v),fastRef=fast.select(v);
                assertEquals(optimal,ref<0?-1:Long.numberOfLeadingZeros(v^compact.value(ref)));
                if(optimal<0) assertEquals(-1,fastRef);
                else assertTrue(Long.numberOfLeadingZeros(v^fast.value(fastRef))>=newestLead);
                compact.add(v);fast.add(v);seen.add(v);
            }
        }
    }

    @Test public void bitWidthsIncludingZeroAnd64() {
        BitStream.Writer out=new BitStream.Writer();Random rng=new Random(1);long[] values=new long[65];
        for(int w=0;w<=64;w++){values[w]=rng.nextLong();out.write(values[w],w);}
        BitStream.Reader in=new BitStream.Reader(out.finish(),0,out.bits);
        for(int w=0;w<=64;w++) assertEquals(values[w]&BitStream.lowMask(w),in.read(w));
        assertEquals(in.limit,in.position);
    }

    private static void reject(byte[] data) {
        try {KangarooDecoder d=new KangarooDecoder(data);while(d.hasNext())d.readRaw();fail("Accepted malformed block");}
        catch(IllegalArgumentException expected) { }
    }
    @Test public void malformedAndTruncatedBlocks() {
        byte[] good=KangarooCodec.compress(new double[]{2.87,11.44,11.44,0.0,-0.0},Options.defaults());
        for(int n=0;n<good.length;n++) reject(Arrays.copyOf(good,n));
        byte[] bad=good.clone();bad[0]=0;reject(bad);
        bad=good.clone();bad[4]=2;reject(bad);
        bad=good.clone();ByteBuffer.wrap(bad).putInt(8,1);reject(bad);
        bad=good.clone();ByteBuffer.wrap(bad).putInt(8,100);reject(bad);
        reject(Arrays.copyOf(good,good.length+1));
        BitStream.Writer b=new BitStream.Writer();b.write(6,3);b.write(1,8);
        byte[] payload=b.finish();ByteBuffer block=ByteBuffer.allocate(20+payload.length);
        block.putInt(KangarooEncoder.MAGIC).put((byte)1).put((byte)7).put((byte)5).put((byte)0).putInt(1).putLong(b.bits).put(payload);
        reject(block.array());
    }

    @Test public void csvHeaderPolicyDoesNotDropFirstNumericValue() throws Exception {
        Path file=Files.createTempFile("kangaroo-csv-",".csv");
        try {
            Files.write(file,"0,1.25\n1,2.87\n".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(bits(1.25,2.87),KangarooCli.readCsv(file,1,"auto"));
            Files.write(file,"time,value\n0,1.25\n1,2.87\n".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(bits(1.25,2.87),KangarooCli.readCsv(file,1,"auto"));
        } finally {Files.delete(file);}
    }
}
