package algorithms.SALTE;

import algorithms.SALTE.decoder.DoubleOurEDecoder;
import algorithms.SALTE.encoder.DoubleOurEEncoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class SALTERoundTripTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripsRepresentativeDecimalValues() throws Exception {
        double[] values = {
                0.1, 0.1, 0.2, 1.234, 1.235, -0.125, -0.125, 10.0, 9.875, 0.0
        };

        assertRoundTrip(values);
    }

    @Test
    public void roundTripsAcrossCodebookBoundary() throws Exception {
        double[] values = new double[30_005];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((i % 200) - 100) / 100.0;
        }

        assertRoundTrip(values);
    }

    private void assertRoundTrip(double[] values) throws Exception {
        File encoded = temporaryFolder.newFile("round-trip.bin");
        DoubleOurEEncoder encoder = new DoubleOurEEncoder(encoded.getAbsolutePath());
        for (double value : values) {
            encoder.encode(value);
        }
        encoder.flush();

        DoubleOurEDecoder decoder = new DoubleOurEDecoder(encoded.getAbsolutePath());
        for (double expected : values) {
            double actual = decoder.decodeDouble();
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
        }
    }
}
