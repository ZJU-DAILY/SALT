package compression.kangaroo;

import org.apache.commons.csv.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** CLI for raw-bit files and checked CSV benchmarks; see README for measurement limits. */
public final class KangarooCli {
    public static void main(String[] args) throws Exception {
        if(args.length==0) { usage(); return; }
        if("encode".equals(args[0]) && args.length>=3 && args.length<=5) {
            Options.Search mode=args.length>3?mode(args[3]):Options.Search.FAST;
            int window=args.length>4?Integer.parseInt(args[4]):32;
            Path input=Paths.get(args[1]),output=Paths.get(args[2]);
            different(input,output);
            if(Files.size(input)%8!=0) throw new IllegalArgumentException("Input length must be a multiple of 8");
            KangarooEncoder encoder=new KangarooEncoder(new Options(window,mode,true,true));
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(input)))) {
                long n=Files.size(input)/8;
                for(long i=0;i<n;i++) encoder.addRaw(in.readLong());
            }
            writeNew(output,encoder.finish());
            System.out.println("Encoded "+encoder.size()+" values");
        } else if("decode".equals(args[0]) && args.length==3) {
            Path input=Paths.get(args[1]),output=Paths.get(args[2]); different(input,output);
            KangarooDecoder decoder=new KangarooDecoder(Files.readAllBytes(input));
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)))) {
                while(decoder.hasNext()) out.writeLong(decoder.readRaw());
            }
            System.out.println("Decoded "+decoder.size()+" values");
        } else if("paper-benchmark".equals(args[0]) && args.length>=5 && args.length<=6) {
            PaperBenchmark.run(Paths.get(args[1]),Paths.get(args[2]),Integer.parseInt(args[3]),args[4],
                    args.length==6?mode(args[5]):Options.Search.FAST,3,7);
        } else if("benchmark".equals(args[0]) && args.length>=3 && args.length<=7) {
            benchmark(Paths.get(args[1]),Paths.get(args[2]),args.length>3?args[3]:"both",
                    args.length>4?Integer.parseInt(args[4]):1000,args.length>5?Integer.parseInt(args[5]):1,args.length>6?args[6]:"auto");
        } else throw new IllegalArgumentException("Invalid command; run without arguments for usage");
    }

    static long[] readCsv(Path path,int column,String header) throws IOException {
        if(column<0 || !("auto".equals(header)||"yes".equals(header)||"no".equals(header)))
            throw new IllegalArgumentException("Invalid column/header option");
        List<Long> values=new ArrayList<>();
        try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8);CSVParser parser=CSVFormat.DEFAULT.withIgnoreEmptyLines(false).parse(reader)) {
            boolean first=true;
            for(CSVRecord record:parser) {
                if(first && "yes".equals(header)) {first=false;continue;}
                if(column>=record.size()) throw new IllegalArgumentException(path+": missing column at CSV record "+record.getRecordNumber());
                String cell=record.get(column).trim();
                if(first && cell.startsWith("\ufeff")) cell=cell.substring(1);
                if(cell.isEmpty()) throw new IllegalArgumentException(path+": missing value at CSV record "+record.getRecordNumber());
                try {values.add(Double.doubleToRawLongBits(Double.parseDouble(cell)));}
                catch(NumberFormatException ex) {
                    if(!(first && "auto".equals(header))) throw new IllegalArgumentException(path+": invalid numeric value at CSV record "+record.getRecordNumber(),ex);
                }
                first=false;
            }
        }
        long[] result=new long[values.size()];
        for(int i=0;i<result.length;i++) result[i]=values.get(i);
        return result;
    }

    private static void benchmark(Path input,Path output,String selected,int blockSize,int column,String header) throws Exception {
        if(blockSize<=0) throw new IllegalArgumentException("Block size must be positive");
        Options.Search[] modes="both".equalsIgnoreCase(selected)?Options.Search.values():new Options.Search[]{mode(selected)};
        List<Path> files;
        if(Files.isDirectory(input)) {
            try(Stream<Path> stream=Files.list(input)) {files=stream.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".csv")).sorted().collect(Collectors.toList());}
        } else files=Collections.singletonList(input);
        for(Path file:files) different(file,output);
        try(CSVPrinter report=new CSVPrinter(Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW),CSVFormat.DEFAULT)) {
            report.printRecord("dataset","mode","records","block_size","blocks","window","header_policy","payload_bits","file_bytes","payload_bpv","file_bpv","encode_ms","decode_ms","unchanged","erased","flipped","rejected_erasure","rle_repeats","status");
            for(Path file:files) {
                long[] data=readCsv(file,column,header);
                for(Options.Search search:modes) {
                    long bytes=0,bits=0,encodeNs=0,decodeNs=0,unchanged=0,erased=0,flipped=0,rejected=0,repeated=0;
                    int blocks=0;
                    for(int start=0;start<data.length;) {
                        int end=start+Math.min(blockSize,data.length-start);
                        long begin=System.nanoTime();
                        KangarooEncoder encoder=new KangarooEncoder(new Options(32,search,true,true));
                        for(int i=start;i<end;i++) encoder.addRaw(data[i]);
                        byte[] compressed=encoder.finish();
                        encodeNs+=System.nanoTime()-begin;
                        long[] decoded=new long[end-start];
                        begin=System.nanoTime();
                        KangarooDecoder decoder=new KangarooDecoder(compressed);
                        for(int i=0;i<decoded.length;i++) decoded[i]=decoder.readRaw();
                        decodeNs+=System.nanoTime()-begin;
                        for(int i=0;i<decoded.length;i++) if(decoded[i]!=data[start+i])
                            throw new IllegalStateException(file+" "+search+" mismatch at "+(start+i+1));
                        if(decoder.hasNext()) throw new IllegalStateException("Extra decoded values");
                        KangarooEncoder.Statistics s=encoder.statistics();
                        bytes+=compressed.length;bits+=s.payloadBits;unchanged+=s.unchanged;erased+=s.erased;flipped+=s.flipped;rejected+=s.rejectedErasure;repeated+=s.repeated;
                        blocks++;start=end;
                    }
                    report.printRecord(file.getFileName(),search,data.length,blockSize,blocks,32,header,bits,bytes,data.length==0?0:(double)bits/data.length,
                            data.length==0?0:8.0*bytes/data.length,encodeNs/1e6,decodeNs/1e6,unchanged,erased,flipped,rejected,repeated,"PASS_RAW_BITS");
                    report.flush();
                    System.out.printf(Locale.ROOT,"%s %s n=%d payload=%.4f file=%.4f bpv PASS_RAW_BITS%n",file.getFileName(),search,data.length,data.length==0?0:(double)bits/data.length,data.length==0?0:8.0*bytes/data.length);
                }
            }
        }
    }
    private static Options.Search mode(String name) { return Options.Search.valueOf(name.toUpperCase(Locale.ROOT)); }
    private static void different(Path input,Path output) throws IOException {
        if(input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()) || (Files.exists(output)&&Files.isSameFile(input,output)))
            throw new IllegalArgumentException("Input and output must differ");
    }
    private static void writeNew(Path file,byte[] data) throws IOException { Files.write(file,data,StandardOpenOption.CREATE_NEW); }
    private static void usage() {
        System.out.println("Independent Kangaroo Java reimplementation; not author's code or wire format.\n"
                +"encode <input.f64be> <output.kgr> [fast|compact] [window=32]\n"
                +"decode <input.kgr> <output.f64be>\n"
                +"benchmark <csv-file-or-directory> <new-report.csv> [both|fast|compact] [block-size=1000] [column=1] [header=auto|yes|no]\n"
                +"paper-benchmark <csv-file> <new-report.csv> <column> <header=yes|no> [fast|compact]\n"
                +"Paper protocol: W=32, blocks=1000, short final block included, block means and weighted metrics; 3 warmups, 7 rounds.\n"
                +"CSV column is zero-based. Raw binary commands use big-endian IEEE-754 bits. Existing outputs are never overwritten.\n"
                +"Benchmark timings are single-pass diagnostics, not publication-quality throughput measurements.");
    }
    private KangarooCli() { }
}
