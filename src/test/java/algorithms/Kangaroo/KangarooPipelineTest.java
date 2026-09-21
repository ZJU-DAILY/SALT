package algorithms.Kangaroo;

import algorithms.AlgorithmsManager;
import algorithms.Encoder;
import algorithms.Decoder;
import compression.kangaroo.KangarooEncoder;
import compression.kangaroo.Options;
import enums.DataTypeEnums;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.Random;
import static org.junit.Assert.*;

public class KangarooPipelineTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void commonIoIsByteIdenticalToStandaloneBothModes() throws Exception {
        double[] values=new double[5000];Random random=new Random(127);
        for(int i=0;i<values.length;i++)values[i]=i<2000?11.44:(random.nextInt(200000)-100000)/100.0;
        values[2000]=-0.0;values[2001]=0.0;values[2002]=1e-20;
        values[2003]=Double.longBitsToDouble(0x7ff8000000001234L);values[2004]=Double.MIN_VALUE;
        for(Options.Search search:Options.Search.values()) {
            String name=search==Options.Search.FAST?"Kangaroo":"KangarooCompact";
            Path file=folder.newFile(name+".bin").toPath();
            Encoder encoder=AlgorithmsManager.getEncoder(DataTypeEnums.DOUBLE.getType(),name,file.toString());
            KangarooEncoder standalone=new KangarooEncoder(new Options(32,search,true,true));
            long reported=0;for(double v:values){reported+=encoder.encode(v);standalone.add(v);}
            reported+=encoder.close();encoder.flush();encoder.flush();
            assertEquals(0,encoder.close());
            assertEquals(Files.size(file)*8,reported);
            assertArrayEquals(standalone.finish(),Files.readAllBytes(file));
            Decoder decoder=AlgorithmsManager.getDecoder(DataTypeEnums.DOUBLE.getType(),name,file.toString());
            for(double v:values)assertEquals(Double.doubleToRawLongBits(v),Double.doubleToRawLongBits(decoder.decodeDouble()));
            try{decoder.decodeDouble();fail("Accepted extra record");}catch(java.util.NoSuchElementException expected){ }
            try{encoder.encode(1.0);fail("Accepted record after close");}catch(IllegalStateException expected){ }
        }
    }
    @Test public void configuredWindowAndFlushWithoutClose() throws Exception {
        Path file=folder.newFile("config.bin").toPath();
        Encoder e=AlgorithmsManager.getEncoder(DataTypeEnums.DOUBLE.getType(),"Kangaroo",file.toString(),"window:8");
        e.encode(2.87);e.encode(2.87);e.flush();
        byte[] bytes=Files.readAllBytes(file);assertEquals(3,bytes[6]);
        Decoder d=AlgorithmsManager.getDecoder(DataTypeEnums.DOUBLE.getType(),"Kangaroo",file.toString(),"window:8");
        assertEquals(2.87,d.decodeDouble(),0);assertEquals(2.87,d.decodeDouble(),0);
    }
}
