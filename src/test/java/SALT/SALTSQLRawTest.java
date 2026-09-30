package SALT;
import algorithms.SALTSQL.SALTSQLRawCodec;
import algorithms.SALTSQL.encoder.DoubleSALTSQLEncoder;
import algorithms.SALTSQL.decoder.DoubleSALTSQLDecoder;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;
public class SALTSQLRawTest {
 @Rule public TemporaryFolder folder = new TemporaryFolder();
 static final double[] EDGE = {0.1234567890123456,1e-20,1.2345678901234567,0,-0.0,Double.MIN_VALUE,Double.MIN_NORMAL,Math.nextUp(Double.MIN_NORMAL),Double.MAX_VALUE,-Double.MAX_VALUE,0.1,0.2,1.25};
 void same(double a,double b) { if(a==0&&b==0)return; assertEquals(Double.doubleToRawLongBits(a),Double.doubleToRawLongBits(b)); }
 long sequential(double[] values) throws Exception {
  File f=folder.newFile(); DoubleSALTSQLEncoder enc=new DoubleSALTSQLEncoder(f.getPath());
  for(double v:values)enc.encode(v); enc.flush();
  DoubleSALTSQLDecoder dec=new DoubleSALTSQLDecoder(f.getPath());
  for(double v:values)same(v,dec.decodeDouble()); return enc.getRawCount();
 }
 @Test public void counterexamplesAtFirstAndLaterPositions() throws Exception {
  for(double v:EDGE){ sequential(new double[]{v,1.25,v,0,v}); sequential(new double[]{1.25,v,1.5,v}); }
  assertEquals(1,sequential(new double[]{1e-20}));
  assertEquals(0,sequential(new double[]{1.25,1.5,1.75,1.75}));
 }
 @Test public void userReportedValues() throws Exception {
  for(double input:new double[]{3.4,1e-16,1.2345678901234567,-0.0}) {
   for(boolean first:new boolean[]{true,false}) {
    File file=folder.newFile();
    DoubleSALTSQLEncoder enc=new DoubleSALTSQLEncoder(file.getPath());
    if(!first)enc.encode(3.4);
    long before=enc.getRawCount();
    enc.encode(input);enc.flush();
    DoubleSALTSQLDecoder dec=new DoubleSALTSQLDecoder(file.getPath());
    if(!first)same(3.4,dec.decodeDouble());
    double actual=dec.decodeDouble();
    same(input,actual);
    List<BigDecimal> values=new ArrayList<>();
    if(!first)values.add(BigDecimal.valueOf(3.4));
    values.add(BigDecimal.valueOf(input));
    String bits=SALTSQL.encodeAsOneWindow01String(values,5,2);
    List<BigDecimal> restored=SALTSQL_Decompress.decompressOneWindow01Stream(bits,5,2);
    double windowValue=restored.get(restored.size()-1).doubleValue();
    same(input,windowValue);
    System.out.println("USER_CASE input="+input+" first="+first+" decoded="+actual
      +" windowDecoded="+windowValue+" raw="+(enc.getRawCount()-before)
      +" bitEqual="+(Double.doubleToRawLongBits(input)==Double.doubleToRawLongBits(actual)));
   }
  }
 }
 @Test public void randomBitsAcrossWindows() throws Exception {
  Random r=new Random(9302026); double[] values=new double[10000];
  for(int i=0;i<values.length;i++)values[i]=i%3==0?EDGE[i%EDGE.length]:Double.longBitsToDouble(r.nextLong());
  assertTrue(sequential(values)>0);
  sequential(new double[]{Double.POSITIVE_INFINITY,0.1,Double.NEGATIVE_INFINITY,Double.longBitsToDouble(0x7ff8000000001234L),1.25});
 }
 @Test public void windowsAndAggregates() throws Exception {
  for(int rotation=0;rotation<EDGE.length;rotation++){
   List<BigDecimal> values=new ArrayList<>();
   for(int i=0;i<EDGE.length;i++)values.add(BigDecimal.valueOf(EDGE[(rotation+i)%EDGE.length]));
   String bits=SALTSQL.encodeAsOneWindow01String(values,5,2);
   List<BigDecimal> actual=SALTSQL_Decompress.decompressOneWindow01Stream(bits,5,2);
   assertEquals(values.size(),actual.size());
   for(int i=0;i<values.size();i++)same(values.get(i).doubleValue(),actual.get(i).doubleValue());
   for(int op=0;op<4;op++){
    double expected=op==2?Double.POSITIVE_INFINITY:op==3?Double.NEGATIVE_INFINITY:0;
    for(int i=1;i<values.size();i++){double v=values.get(i).doubleValue();expected=op<2?expected+v:op==2?Math.min(expected,v):Math.max(expected,v);}
    SALTSQL_Decompress.WindowAggregateResult result=SALTSQL_Decompress.aggregateOneWindow01Stream(bits,5,2,1,values.size(),op);
    same(expected,result.aggregate);assertEquals(values.size(),result.decodedCount);assertEquals(values.size()-1,result.selectedCount);
   }
  }
 }
 @Test public void rawFormatAndTruncation() throws Exception {
  String bits=SALTSQLRawCodec.encode(1e-20,null,true,5,2);
  assertEquals(66,bits.length());assertTrue(bits.startsWith("01"));
  assertEquals("11",SALTSQLRawCodec.encode(0,null,true,5,2));
  assertTrue(SALTSQLRawCodec.encode(1.25,null,true,5,2).startsWith("10"));
  try{SALTSQL_Decompress.decompressOneWindow01Stream(bits.substring(0,65),5,2);fail("truncated RAW accepted");}catch(EOFException expected){}
 }

 @Test public void legacySequentialFileStillReads() throws Exception {
  File f=folder.newFile();
  // Legacy header, explicit 1.25, followed by a zero residual.
  String bits="011101010010"+"00"+"0010"+"00000000000"+"0100000"+"1";
  try(OutputStream out=new FileOutputStream(f)){
   for(int i=0;i<bits.length();i+=8){int b=0;for(int j=0;j<8;j++)b=(b<<1)|(i+j<bits.length()?bits.charAt(i+j)-'0':0);out.write(b);}
  }
  DoubleSALTSQLDecoder decoder=new DoubleSALTSQLDecoder(f.getPath());
  same(1.25,decoder.decodeDouble());same(1.25,decoder.decodeDouble());
 }

 @Test public void allRawWindowExceedsOldIndexWidth() throws Exception {
  List<BigDecimal> values=new ArrayList<>();
  for(int i=0;i<128;i++)values.add(BigDecimal.valueOf(Double.MIN_VALUE*(i+1)));
  String bits=SALTSQL.encodeAsOneWindow01String(values,5,2);
  assertEquals(128*66,bits.length());
  assertTrue(bits.length()>8191);assertTrue(bits.length()<16384);
  List<BigDecimal> decoded=SALTSQL_Decompress.decompressOneWindow01Stream(bits,5,2);
  assertEquals(128,decoded.size());
  for(int i=0;i<128;i++)same(values.get(i).doubleValue(),decoded.get(i).doubleValue());
 }

 @Test public void auditLocalOverallWindows() throws Exception {
  org.junit.Assume.assumeTrue(Boolean.getBoolean("saltplus.auditOverall"));
  File[] files=new File("datasets/Overall").listFiles((dir,name)->name.endsWith(".csv"));
  assertNotNull(files);Arrays.sort(files);
  long total=0;
  for(File file:files){
   List<BigDecimal> window=new ArrayList<>();long count=0;
   try(java.io.Reader reader=new java.io.InputStreamReader(new FileInputStream(file),"UTF-8");
       org.apache.commons.csv.CSVParser csv=org.apache.commons.csv.CSVFormat.DEFAULT.parse(reader)){
    for(org.apache.commons.csv.CSVRecord row:csv){
     if(row.size()<2)continue;
     double v=Double.parseDouble(row.get(1).trim());
     window.add(BigDecimal.valueOf(v));count++;
     if(window.size()==128){checkWindow(window);window.clear();}
    }
   }
   if(!window.isEmpty())checkWindow(window);
   total+=count;System.out.println("WINDOW_RAW_PASS "+file.getName()+" "+count);
  }
  System.out.println("WINDOW_RAW_TOTAL "+total);
 }
 private void checkWindow(List<BigDecimal> values) throws Exception {
  String bits=SALTSQL.encodeAsOneWindow01String(values,5,2);
  List<BigDecimal> result=SALTSQL_Decompress.decompressOneWindow01Stream(bits,5,2);
  assertEquals(values.size(),result.size());
  for(int i=0;i<values.size();i++)same(values.get(i).doubleValue(),result.get(i).doubleValue());
 }
}
