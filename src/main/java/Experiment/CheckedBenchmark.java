package Experiment;

import algorithms.AlgorithmsManager;
import algorithms.Encoder;
import algorithms.Decoder;
import enums.AlgorithmEnums;
import enums.DataTypeEnums;
import org.apache.commons.csv.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

/** Common file-I/O benchmark for all registered codecs; verification is never timed or suppressed. */
public final class CheckedBenchmark {
    private static final class Pass {
        long encodeNs,decodeNs,bytes,zeroDifferences,mismatches,decoded;
        double blockRatioSum;
        String firstMismatch="",error="";
        String status() {return !error.isEmpty()?"ERROR":mismatches>0?"FAIL_ROUNDTRIP":zeroDifferences>0?"PASS_ZERO_EQ":"PASS_RAW_BITS";}
        boolean valid() {return error.isEmpty() && mismatches==0;}
    }
    private static final class State {
        final String method;
        final List<Double> encodeMs=new ArrayList<>(),decodeMs=new ArrayList<>();
        Pass last;
        State(String method) {this.method=method;}
    }

    static double[][] read(Path file,int column,String header,int blockSize) throws IOException {
        if(column<0 || !(header.equals("yes") || header.equals("no")))throw new IllegalArgumentException("Explicit header yes/no and column required");
        List<Double> values=new ArrayList<>();
        try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8);
            CSVParser parser=CSVFormat.DEFAULT.withIgnoreEmptyLines(false).parse(reader)) {
            for(CSVRecord record:parser) {
                if(record.getRecordNumber()==1 && header.equals("yes"))continue;
                if(column>=record.size())throw new IllegalArgumentException("Missing column at CSV record "+record.getRecordNumber());
                String text=record.get(column).trim();
                if(record.getRecordNumber()==1 && text.startsWith("\ufeff"))text=text.substring(1);
                if(text.isEmpty())throw new IllegalArgumentException("Missing value at CSV record "+record.getRecordNumber());
                try {values.add(Double.parseDouble(text));}
                catch(NumberFormatException e) {throw new IllegalArgumentException("Invalid value at CSV record "+record.getRecordNumber(),e);}
            }
        }
        if(values.isEmpty())throw new IllegalArgumentException("Empty dataset");
        int length=blockSize==0?values.size():blockSize;
        double[][] blocks=new double[(int)((values.size()+(long)length-1)/length)][];
        for(int b=0,start=0;b<blocks.length;b++) {
            blocks[b]=new double[Math.min(length,values.size()-start)];
            for(int i=0;i<blocks[b].length;i++)blocks[b][i]=values.get(start++);
        }
        return blocks;
    }

    private static Pass execute(String method,double[][] blocks,Path stream) {
        Pass p=new Pass();
        try {
            for(double[] block:blocks) {
                // Reset only our own temporary output; a failed codec must not expose an old stream.
                Files.write(stream,new byte[0]);
                long begin=System.nanoTime();
                Encoder encoder=AlgorithmsManager.getEncoder(DataTypeEnums.DOUBLE.getType(),method,stream.toString());
                for(double v:block)encoder.encode(v);
                encoder.close();encoder.flush();
                p.encodeNs+=System.nanoTime()-begin;
                long bytes=Files.size(stream);
                if(bytes==0)throw new IllegalStateException("Encoder wrote no data");
                p.bytes+=bytes;p.blockRatioSum+=bytes/(8.0*block.length);
                begin=System.nanoTime();
                Decoder decoder=AlgorithmsManager.getDecoder(DataTypeEnums.DOUBLE.getType(),method,stream.toString());
                double[] restored=new double[block.length];
                for(int i=0;i<restored.length;i++)restored[i]=decoder.decodeDouble();
                p.decodeNs+=System.nanoTime()-begin;
                for(int i=0;i<block.length;i++) {
                    long expected=Double.doubleToRawLongBits(block[i]),actual=Double.doubleToRawLongBits(restored[i]);
                    p.decoded++;
                    if(expected!=actual) {
                        if(block[i]==0.0 && restored[i]==0.0)p.zeroDifferences++;
                        else {
                            p.mismatches++;
                            if(p.firstMismatch.isEmpty())p.firstMismatch="record="+p.decoded+" expectedBits="+Long.toHexString(expected)+" actualBits="+Long.toHexString(actual);
                        }
                    }
                }
            }
        } catch(Exception e) {
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
            p.error=cause.getClass().getSimpleName()+": "+cause.getMessage();
        }
        return p;
    }

    public static void main(String[] args) throws Exception {
        if(args.length<2 || args.length>8) {
            System.out.println("CheckedBenchmark <csv-file-or-directory> <NEW-output-directory> [block-size=1000;0=whole] [warmups=3] [rounds=7] [methods=SALTE,DeXOR,Kangaroo,KangarooCompact] [column=1] [header=no|yes]");
            return;
        }
        int blockSize=args.length>2?Integer.parseInt(args[2]):1000;
        int warmups=args.length>3?Integer.parseInt(args[3]):3,rounds=args.length>4?Integer.parseInt(args[4]):7;
        int column=args.length>6?Integer.parseInt(args[6]):1;String header=args.length>7?args[7]:"no";
        if(blockSize<0 || warmups<0 || rounds<1 || column<0 || !(header.equals("no")||header.equals("yes")))throw new IllegalArgumentException("Invalid protocol arguments");
        List<String> methods=new ArrayList<>();
        for(String name:(args.length>5?args[5]:"SALTE,DeXOR,Kangaroo,KangarooCompact").split(",")) {
            AlgorithmEnums algorithm=AlgorithmEnums.CheckName(name.trim());
            if(algorithm==null)throw new IllegalArgumentException("Unknown method: "+name);
            if(methods.contains(algorithm.getName()))throw new IllegalArgumentException("Duplicate method");
            methods.add(algorithm.getName());
        }
        Path input=Paths.get(args[0]),output=Paths.get(args[1]);
        List<Path> files;
        if(Files.isDirectory(input)) {
            try(Stream<Path> stream=Files.list(input)) {files=stream.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".csv")).sorted().collect(Collectors.toList());}
        } else files=Collections.singletonList(input);
        if(files.isEmpty())throw new IllegalArgumentException("No CSV inputs");
        Files.createDirectory(output); // A fresh directory prevents overwriting earlier experiments.
        Path streams=Files.createDirectory(output.resolve("streams"));
        Files.write(output.resolve("protocol.txt"),Arrays.asList(
                "Common AlgorithmsManager + original StreamWriter/StreamReader; disk I/O included (OS page cache not controlled).",
                "Kangaroo is independent Java v0.2 with KGRJ v1 framing; no DeXOR preprocessing.",
                "Java="+System.getProperty("java.version")+"; block_size="+blockSize+"; warmups="+warmups+"; rounds="+rounds,
                "column="+column+"; header="+header+"; tail=include; missing=error; methods="+methods,
                "Timing includes construction, allocation, encoding/decoding, close, flush and file I/O; excludes CSV loading and verification.",
                "MB=1000000 bytes; raw-bit comparison with signed-zero equivalence for all methods; failed roundtrips excluded from timings.",
                "Serialized size includes each codec's headers and padding; original per-record bit counters are not used."),StandardCharsets.UTF_8);
        try(CSVPrinter report=new CSVPrinter(Files.newBufferedWriter(output.resolve("summary.csv"),StandardCharsets.UTF_8),CSVFormat.DEFAULT);
            CSVPrinter raw=new CSVPrinter(Files.newBufferedWriter(output.resolve("rounds.csv"),StandardCharsets.UTF_8),CSVFormat.DEFAULT)) {
            report.printRecord("dataset","source_sha256","method","records","block_size","blocks","compressed_bytes","file_bpv","mean_block_ratio","encode_ms","decode_ms","encode_MBps","decode_MBps","measured_rounds","zero_sign_differences","mismatches","decoded_records","status","first_mismatch","error");
            raw.printRecord("dataset","method","round","encode_ms","decode_ms","status");
            for(Path file:files) {
                String sha=sha256(file);double[][] blocks;
                try {blocks=read(file,column,header,blockSize);}
                catch(Exception e) {
                    for(String method:methods)report.printRecord(file.getFileName(),sha,method,"",blockSize,"","","","","","","","",0,"","","","INPUT_ERROR","",e.toString());
                    report.flush();continue;
                }
                long records=0;for(double[] block:blocks)records+=block.length;
                List<State> states=new ArrayList<>();
                for(String method:methods) {
                    State s=new State(method);s.last=execute(method,blocks,streams.resolve(method+".bin"));states.add(s);
                }
                for(int round=-warmups;round<rounds;round++)for(int turn=0;turn<states.size();turn++) {
                    State s=states.get(Math.floorMod(round+turn,states.size()));
                    if(!s.last.valid())continue;
                    Pass p=execute(s.method,blocks,streams.resolve(s.method+".bin"));
                    if(p.valid() && p.bytes!=s.last.bytes)p.error="Non-deterministic encoded size";
                    s.last=p;
                    if(round>=0) {
                        raw.printRecord(file.getFileName(),s.method,round+1,p.encodeNs/1e6,p.decodeNs/1e6,p.status());
                        if(p.valid()){s.encodeMs.add(p.encodeNs/1e6);s.decodeMs.add(p.decodeNs/1e6);}
                    }
                }
                for(State s:states) {
                    Pass p=s.last;boolean valid=p.valid() && s.encodeMs.size()==rounds;
                    double ems=valid?median(s.encodeMs):0,dms=valid?median(s.decodeMs):0;
                    report.printRecord(file.getFileName(),sha,s.method,records,blockSize,blocks.length,p.bytes,
                            p.error.isEmpty()?p.bytes*8.0/records:"",p.error.isEmpty()?p.blockRatioSum/blocks.length:"",
                            valid?ems:"",valid?dms:"",valid?records*8.0/1000/ems:"",valid?records*8.0/1000/dms:"",
                            s.encodeMs.size(),p.zeroDifferences,p.mismatches,p.decoded,p.status(),p.firstMismatch,p.error);
                    System.out.println(file.getFileName()+" "+s.method+" "+p.status()+" decoded="+p.decoded+"/"+records);
                }
                report.flush();raw.flush();
            }
        }
    }
    private static double median(List<Double> samples) {
        List<Double> sorted=new ArrayList<>(samples);Collections.sort(sorted);int n=sorted.size();
        return n%2==1?sorted.get(n/2):(sorted.get(n/2-1)+sorted.get(n/2))/2;
    }
    private static String sha256(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file)) {
            byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);
        }
        StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private CheckedBenchmark() { }
}
