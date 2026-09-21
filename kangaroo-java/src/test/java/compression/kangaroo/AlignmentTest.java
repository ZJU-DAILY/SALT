package compression.kangaroo;

import org.junit.Test;
import org.apache.commons.csv.*;
import static org.junit.Assert.*;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class AlignmentTest {
    @Test public void figure7AndExample6SelectDifferentHopBoundaries() {
        int[] edges={14,16,12,11,19,11,16,13,11,14,13,12,14,15,13};
        int[] queryLeads={11,11,11,11,19,20,11,11,11,13,13,17,12,12,12,12};
        long[] values=new long[16]; values[0]=(1L<<(63-queryLeads[0]))|1;
        for(int i=0;i<15;i++) {
            int ell=edges[i];long bit=1L<<(63-ell);
            values[i+1]=(values[i]&(-1L<<(64-ell)))|((values[i]^bit)&bit)|1;
            if(queryLeads[i+1]>ell)values[i+1]|=1L<<(63-queryLeads[i+1]);
        }
        for(int i=0;i<16;i++) {
            assertEquals(queryLeads[i],Long.numberOfLeadingZeros(values[i]^1L));
            if(i<15)assertEquals(edges[i],Long.numberOfLeadingZeros(values[i]^values[i+1]));
        }
        for(Options.Search mode:Options.Search.values()) {
            History history=new History(32,mode);for(long v:values)history.add(v);
            long ref=history.select(1L);
            assertEquals(mode==Options.Search.COMPACT?5:11,ref);
            assertEquals(mode==Options.Search.COMPACT?20:17,Long.numberOfLeadingZeros(history.value(ref)^1L));
        }
    }

    @Test public void fastTableMatchesIndependentLinearFirstHopOracle() {
        Random random=new Random(20260917);
        for(int window:new int[]{1,2,8,32,128}) {
            History fast=new History(window,Options.Search.FAST);List<Long> sequence=new ArrayList<>();
            for(int i=0;i<20000;i++) {
                long v=(random.nextLong()|1L)<<random.nextInt(64);
                if(i%7==0)v=0; // trail=64, unlike ordinary nonzero test data
                if(i>0 && i%3==0)v=sequence.get(random.nextInt(i)); // identical values / duplicate heads
                List<Integer> group=new ArrayList<>();
                for(int j=Math.max(0,i-window);j<i;j++)
                    if(Long.numberOfTrailingZeros(sequence.get(j))==Long.numberOfTrailingZeros(v))group.add(j);
                long expected=-1;
                if(!group.isEmpty()) {
                    expected=group.get(group.size()-1);int initial=Long.numberOfLeadingZeros(v^sequence.get((int)expected));
                    if(initial!=64)for(int j=group.size()-2;j>=0;j--) {
                        int edge=Long.numberOfLeadingZeros(sequence.get(group.get(j))^sequence.get(group.get(j+1)));
                        if(edge<=initial) {if(edge==initial)expected=group.get(j);break;}
                    }
                }
                assertEquals("window="+window+" record="+i,expected,fast.select(v));
                fast.add(v);sequence.add(v);
            }
        }
    }

    @Test public void exactInverseCoversAllNormalExponentsAndValidIntervals() {
        int checked=0;
        for(int exponent=-1022;exponent<=1023;exponent++)for(int alpha=0;alpha<=324;alpha++) {
            int lower=Erasure.length(exponent,alpha),upper=Erasure.length(exponent,alpha-1);
            for(int trailing=lower;trailing<Math.min(64,upper);trailing++) {
                assertEquals(alpha,Erasure.inferAlpha(exponent,trailing));checked++;
            }
        }
        assertTrue(checked>60000);
        for(int[] invalid:new int[][]{{-1023,1},{1024,1},{0,-1},{0,64},{1023,0}}) {
            try {Erasure.inferAlpha(invalid[0],invalid[1]);fail("invalid precision interval");}
            catch(IllegalArgumentException expected) { }
        }
    }

    @Test public void consecutiveRleAndNonconsecutiveReferenceRemainDistinct() {
        for(Options.Search mode:Options.Search.values()) {
            KangarooEncoder e=new KangarooEncoder(new Options(32,mode,true,true));
            double[] values={1.25,1.25,2.5,1.25};for(double v:values)e.add(v);
            KangarooDecoder d=new KangarooDecoder(e.finish());
            assertEquals(1,e.statistics().repeated);assertEquals(1,e.statistics().sameReference);
            for(double v:values)assertEquals(Double.doubleToRawLongBits(v),d.readRaw());
            assertFalse(d.hasNext());
        }
    }

    @Test public void frozenV01StreamRemainsReadableAndEncodingIdentical() {
        // Generated with the archived 0.1.0 JAR, before making the v0.2 changes.
        byte[] fixture=Base64.getDecoder().decode("S0dSSgEHBQAAAAAKAAAAAAAAAQcgA3gAAAAAAFASb4C7/+/joQf/gAAAAAEjdyB/7yhABwo=");
        long[] raw={0x4006f5c28f5c28f6L,0x4026e147ae147ae1L,0x4026e147ae147ae1L,0x4026e147ae147ae1L,
                0L,Long.MIN_VALUE,0x7ff8000000001234L,0x3ff4000000000000L,0x4004000000000000L,0x3ff4000000000000L};
        KangarooDecoder d=new KangarooDecoder(fixture);KangarooEncoder e=new KangarooEncoder();
        for(long v:raw){assertEquals(v,d.readRaw());e.addRaw(v);}
        assertFalse(d.hasNext());assertArrayEquals(fixture,e.finish());
    }

    @Test public void csvMissingValuesAreNeverSilentlyDropped() throws Exception {
        Path input=Files.createTempFile("kangaroo-missing-",".csv");
        try {
            for(String text:new String[]{"1.25\n\n2.5\n","\n1.25\n","1.25\n   \n2.5\n"}) {
                Files.write(input,text.getBytes(StandardCharsets.UTF_8));
                try {KangarooCli.readCsv(input,0,"auto");fail("missing record accepted");}
                catch(IllegalArgumentException expected) { }
            }
        } finally {Files.delete(input);}
    }

    @Test public void protocolUsesBlockMeansAndRecordsShortTail() throws Exception {
        Path dir=Files.createTempDirectory("kangaroo-protocol-");
        Path input=dir.resolve("input.csv"),output=dir.resolve("output.csv");
        try {
            StringBuilder text=new StringBuilder();for(int i=0;i<1000;i++)text.append("1.25\n");text.append("2.87\n");
            Files.write(input,text.toString().getBytes(StandardCharsets.UTF_8));
            try {PaperBenchmark.run(input,output,0,"auto",Options.Search.FAST,0,1);fail("implicit header accepted");}
            catch(IllegalArgumentException expected) { }
            PaperBenchmark.run(input,output,0,"no",Options.Search.FAST,0,1);
            try(Reader reader=Files.newBufferedReader(output,StandardCharsets.UTF_8);
                CSVParser parser=CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader)) {
                List<CSVRecord> rows=parser.getRecords();assertEquals(3,rows.size());
                assertEquals("1000",rows.get(0).get("records"));assertEquals("1",rows.get(1).get("records"));
                CSVRecord all=rows.get(2);assertEquals("ALL",all.get("row"));assertEquals("2",all.get("blocks"));
                double a=Double.parseDouble(rows.get(0).get("mean_block_payload_ratio"));
                double b=Double.parseDouble(rows.get(1).get("mean_block_payload_ratio"));
                assertEquals((a+b)/2,Double.parseDouble(all.get("mean_block_payload_ratio")),1e-14);
                assertEquals((1000*a+b)/1001,Double.parseDouble(all.get("weighted_payload_ratio")),1e-14);
                assertEquals("INCLUDE",all.get("tail_policy"));assertEquals("ERROR",all.get("missing_policy"));
                assertEquals(64,all.get("source_sha256").length());assertEquals("PASS_RAW_BITS",all.get("status"));
            }
        } finally {Files.deleteIfExists(output);Files.deleteIfExists(input);Files.delete(dir);}
    }
}
