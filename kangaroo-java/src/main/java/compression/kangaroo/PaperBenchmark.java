package compression.kangaroo;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Paper's block protocol; independent framing, JVM and timing choices remain explicit. */
final class PaperBenchmark {
    static final int BLOCK_SIZE = 1000;
    private static final class Block {
        final int start, count;
        final long[] encodeNs, decodeNs;
        long bits, bytes;
        KangarooEncoder.Statistics stats;
        Block(int start, int count, int rounds) {
            this.start=start; this.count=count;
            encodeNs=new long[rounds]; decodeNs=new long[rounds];
        }
    }

    static void run(Path input, Path output, int column, String header, Options.Search mode,
                    int warmups, int rounds) throws Exception {
        if (!("yes".equals(header) || "no".equals(header)))
            throw new IllegalArgumentException("Paper protocol requires explicit header=yes or no");
        if (warmups<0 || rounds<1) throw new IllegalArgumentException("Invalid measurement rounds");
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        String sha=sha256(input);
        long[] values=KangarooCli.readCsv(input,column,header);
        if (values.length==0) throw new IllegalArgumentException("Empty dataset");
        if (!sha.equals(sha256(input))) throw new IOException("Input changed while being read");
        List<Block> blocks=new ArrayList<>();
        for(int start=0;start<values.length;) {
            int n=Math.min(BLOCK_SIZE,values.length-start);
            blocks.add(new Block(start,n,rounds)); start+=n;
        }
        Options options=new Options(32,mode,true,true);
        for(int round=-warmups;round<rounds;round++) {
            for(Block b:blocks) {
                long begin=System.nanoTime();
                KangarooEncoder encoder=new KangarooEncoder(options);
                for(int i=0;i<b.count;i++) encoder.addRaw(values[b.start+i]);
                byte[] encoded=encoder.finish();
                long enc=System.nanoTime()-begin;
                begin=System.nanoTime();
                KangarooDecoder decoder=new KangarooDecoder(encoded);
                long[] decoded=new long[b.count];
                for(int i=0;i<b.count;i++) decoded[i]=decoder.readRaw();
                long dec=System.nanoTime()-begin;
                if(decoder.hasNext()) throw new IllegalStateException("Extra decoded values");
                // Compare outside the timed interval, in every warmup and measured pass.
                for(int i=0;i<b.count;i++) if(decoded[i]!=values[b.start+i])
                    throw new IllegalStateException("Raw-bit mismatch at record "+(b.start+i+1));
                KangarooEncoder.Statistics stats=encoder.statistics();
                if(round>=0) {
                    if(round>0 && (b.bits!=stats.payloadBits || b.bytes!=encoded.length))
                        throw new IllegalStateException("Non-deterministic encoded size");
                    b.encodeNs[round]=enc; b.decodeNs[round]=dec;
                }
                b.bits=stats.payloadBits; b.bytes=encoded.length; b.stats=stats;
            }
        }
        try(CSVPrinter out=new CSVPrinter(Files.newBufferedWriter(output,StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW),CSVFormat.DEFAULT)) {
            out.printRecord("dataset","source_sha256","implementation","mode","window","block_size",
                    "column","header_policy","missing_policy","tail_policy","warmups","rounds",
                    "java_version","os_arch","row","blocks","records","payload_bits","file_bytes",
                    "mean_block_payload_ratio","mean_block_file_ratio","weighted_payload_ratio","weighted_file_ratio",
                    "sum_block_median_encode_ms","sum_block_median_decode_ms",
                    "mean_block_encode_MBps","mean_block_decode_MBps","weighted_encode_MBps","weighted_decode_MBps",
                    "unchanged","erased","flipped","rejected_erasure","rle_repeats",
                    "same_reference","shared_trailing","fallback","status");
            long bits=0,bytes=0; double pr=0,fr=0,encNs=0,decNs=0,encSpeed=0,decSpeed=0;
            long[] totals=new long[8];
            for(int i=0;i<blocks.size();i++) {
                Block b=blocks.get(i);
                double e=median(b.encodeNs),d=median(b.decodeNs);
                double p=b.bits/(64.0*b.count),f=b.bytes/(8.0*b.count);
                double es=8.0*b.count*1000/e,ds=8.0*b.count*1000/d;
                long[] s=statistics(b.stats);
                write(out,input,sha,column,header,mode,warmups,rounds,Integer.toString(i),1,b.count,
                        b.bits,b.bytes,p,f,p,f,e,d,es,ds,es,ds,s);
                bits+=b.bits;bytes+=b.bytes;pr+=p;fr+=f;encNs+=e;decNs+=d;encSpeed+=es;decSpeed+=ds;
                for(int j=0;j<s.length;j++)totals[j]+=s[j];
            }
            int n=blocks.size();
            write(out,input,sha,column,header,mode,warmups,rounds,"ALL",n,values.length,bits,bytes,
                    pr/n,fr/n,bits/(64.0*values.length),bytes/(8.0*values.length),encNs,decNs,
                    encSpeed/n,decSpeed/n,8.0*values.length*1000/encNs,8.0*values.length*1000/decNs,totals);
        }
        System.out.println(input.getFileName()+" "+mode+" blocks="+blocks.size()+" records="+values.length+" PASS_RAW_BITS");
    }

    private static long[] statistics(KangarooEncoder.Statistics s) {
        return new long[]{s.unchanged,s.erased,s.flipped,s.rejectedErasure,s.repeated,s.sameReference,s.sharedTrailing,s.fallback};
    }
    private static void write(CSVPrinter out,Path input,String sha,int column,String header,Options.Search mode,
                              int warmups,int rounds,String row,int blocks,int count,long bits,long bytes,
                              double pr,double fr,double wp,double wf,double e,double d,double es,double ds,
                              double wes,double wds,long[] statistics) throws IOException {
        List<Object> fields=new ArrayList<>(Arrays.<Object>asList(input.toAbsolutePath().normalize(),sha,
                "independent-java-0.2.0/KGRJ1",mode,32,BLOCK_SIZE,column,header,"ERROR","INCLUDE",
                warmups,rounds,System.getProperty("java.version"),System.getProperty("os.arch"),row,
                blocks,count,bits,bytes,pr,fr,wp,wf,e/1e6,d/1e6,es,ds,wes,wds));
        for(long v:statistics)fields.add(v);
        fields.add("PASS_RAW_BITS");out.printRecord(fields);
    }
    private static double median(long[] samples) {
        long[] sorted=samples.clone();Arrays.sort(sorted);
        return sorted.length%2==1?sorted[sorted.length/2]:
                sorted[sorted.length/2-1]/2.0+sorted[sorted.length/2]/2.0;
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        StringBuilder result=new StringBuilder();
        for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
    private PaperBenchmark() { }
}
