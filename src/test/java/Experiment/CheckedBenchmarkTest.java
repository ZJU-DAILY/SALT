package Experiment;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.csv.*;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CheckedBenchmarkTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void preservesFirstRecordAndShortTailRejectsMissingRecords() throws Exception {
        Path file=folder.newFile("values.csv").toPath();
        Files.write(file,"0,1.25\n1,2.87\n2,11.44\n".getBytes(StandardCharsets.UTF_8));
        double[][] blocks=CheckedBenchmark.read(file,1,"no",2);
        assertEquals(2,blocks.length);assertArrayEquals(new double[]{1.25,2.87},blocks[0],0);
        assertArrayEquals(new double[]{11.44},blocks[1],0);
        Files.write(file,"0,1.25\n\n2,11.44\n".getBytes(StandardCharsets.UTF_8));
        try{CheckedBenchmark.read(file,1,"no",2);fail("Missing row ignored");}catch(IllegalArgumentException expected){ }
    }
    @Test public void failedRoundtripIsNotReportedAsSuccessfulThroughput() throws Exception {
        Path file=folder.newFile("failure.csv").toPath(),output=folder.getRoot().toPath().resolve("run");
        Files.write(file,"0,1e-20\n1,0.1234567890123456\n".getBytes(StandardCharsets.UTF_8));
        String previous = System.getProperty("salt.raw.enabled");
        try {
            // This test deliberately exercises the legacy failure-reporting path.
            System.setProperty("salt.raw.enabled", "false");
            CheckedBenchmark.main(new String[]{file.toString(),output.toString(),"1000","0","1","SALTE,Kangaroo,DeXOR","1","no"});
        } finally {
            if (previous == null) System.clearProperty("salt.raw.enabled");
            else System.setProperty("salt.raw.enabled", previous);
        }
        try(Reader reader=Files.newBufferedReader(output.resolve("summary.csv"),StandardCharsets.UTF_8);
            CSVParser parser=CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader)) {
            List<CSVRecord> rows=parser.getRecords();assertEquals(3,rows.size());
            Map<String,CSVRecord> byMethod=new HashMap<>();for(CSVRecord row:rows)byMethod.put(row.get("method"),row);
            assertEquals("FAIL_ROUNDTRIP",byMethod.get("SALTE").get("status"));
            assertEquals("",byMethod.get("SALTE").get("encode_MBps"));
            assertEquals("PASS_RAW_BITS",byMethod.get("Kangaroo").get("status"));
            assertEquals("2",byMethod.get("Kangaroo").get("records"));
            assertEquals("2",byMethod.get("Kangaroo").get("decoded_records"));
        }
    }
}
