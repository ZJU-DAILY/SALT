package algorithms.SALTE;

import algorithms.SALTE.encoder.DoubleOurEEncoder;
import algorithms.SALTE.decoder.DoubleOurEDecoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

public class SALTERawTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private long roundTrip(double[] values) throws Exception {
        File f = temp.newFile();
        DoubleOurEEncoder enc = new DoubleOurEEncoder(f.toString(), true);
        for (double v : values) enc.encode(v);
        enc.flush();
        DoubleOurEDecoder dec = new DoubleOurEDecoder(f.toString());
        for (int i = 0; i < values.length; i++) {
            double actual = dec.decodeDouble();
            if (values[i] == 0.0 && actual == 0.0) continue;
            assertEquals("record " + i, Double.doubleToRawLongBits(values[i]), Double.doubleToRawLongBits(actual));
        }
        return enc.getRawCount();
    }

    @Test public void originalCounterexamplesAndSpecialValues() throws Exception {
        for (double value : new double[]{0.1234567890123456, 1e-20, 1.2345678901234567}) {
            assertEquals(1, roundTrip(new double[]{value}));
        }
        assertTrue(roundTrip(new double[]{0.1234567890123456, 1e-20, 1.2345678901234567,
                -0.0, +0.0, Double.MIN_VALUE, Double.MIN_NORMAL, Math.nextUp(Double.MIN_NORMAL),
                Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.longBitsToDouble(0x7ff8000000001234L), 0.1, 0.2}) > 0);
    }

    @Test public void mixedValuesAcrossMultipleCodebookBoundaries() throws Exception {
        double[] values = new double[90005];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 97 == 0 || i % 10000 >= 9997 ? 1e-20 : ((i / 2) % 200 - 100) / 100.0;
        }
        assertTrue(roundTrip(values) > 0);
    }

    @Test public void allRawAndEmptyCodebooksAcrossBoundaries() throws Exception {
        double[] values = new double[60003];
        Arrays.fill(values, Double.POSITIVE_INFINITY);
        assertEquals(values.length, roundTrip(values));
    }

    @Test public void randomBitPatternsAndNearbyDecimals() throws Exception {
        Random rng = new Random(7182026);
        double[] values = new double[40000];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 2 == 0 ? Double.longBitsToDouble(rng.nextLong())
                    : Math.nextAfter(rng.nextInt(1000) / 100.0, Double.POSITIVE_INFINITY);
        }
        assertTrue(roundTrip(values) > 0);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }

    @Test public void rawDoesNotAdvanceEitherWindowOrStatistics() throws Exception {
        File f = temp.newFile();
        DoubleOurEEncoder enc = new DoubleOurEEncoder(f.toString(), true);
        enc.encode(0.2);
        BigDecimal[] before = ((BigDecimal[]) field(enc, "valueWin")).clone();
        BigDecimal[] delta = ((BigDecimal[]) field(enc, "deltaWin")).clone();
        Object index = field(enc, "winIndex");
        String counts = field(enc, "counts").toString();
        enc.encode(1e-20);
        assertEquals(1, enc.getRawCount());
        assertArrayEquals(before, (BigDecimal[]) field(enc, "valueWin"));
        assertArrayEquals(delta, (BigDecimal[]) field(enc, "deltaWin"));
        assertEquals(index, field(enc, "winIndex"));
        assertEquals(counts, field(enc, "counts").toString());
        enc.encode(0.2); // ZERO after RAW must still find the preceding ordinary value.
        enc.flush();
        DoubleOurEDecoder dec = new DoubleOurEDecoder(f.toString());
        assertEquals(0.2, dec.decodeDouble(), 0);
        assertEquals(1e-20, dec.decodeDouble(), 0);
        assertArrayEquals(before, (BigDecimal[]) field(dec, "valueWin"));
        assertArrayEquals(delta, (BigDecimal[]) field(dec, "deltaWin"));
        assertEquals(index, field(dec, "winIndex"));
        assertEquals(0.2, dec.decodeDouble(), 0);
    }

    @Test public void initialThreeCodewordsAddOneBitPerSafeValueAndSignedZeroIsAllowed() throws Exception {
        double[] values = {0.1, 0.2, 0.2, -0.0, 0.0, 1.25, 1.3};
        assertEquals(0, roundTrip(values));
        File off = temp.newFile(), on = temp.newFile();
        DoubleOurEEncoder legacy = new DoubleOurEEncoder(off.toString(), false);
        DoubleOurEEncoder guarded = new DoubleOurEEncoder(on.toString(), true);
        long offBits = 0, onBits = 0;
        for (double v : values) { offBits += legacy.encode(v); onBits += guarded.encode(v); }
        legacy.flush(); guarded.flush();
        byte[] b = Files.readAllBytes(on.toPath());
        assertEquals(0xF2, b[0] & 255);
        assertEquals(8 + values.length, onBits - offBits);
        DoubleOurEDecoder dec = new DoubleOurEDecoder(off.toString());
        for (double v : values) assertEquals(v, dec.decodeDouble(), 0);
    }

    @Test public void rawAndZeroArePeerCodewordsFromFirstRecord() throws Exception {
        File f = temp.newFile();
        DoubleOurEEncoder enc = new DoubleOurEEncoder(f.toString(), true);
        double raw = 1e-20;
        long bits = enc.encode(0.1); // WIN 0, ZERO 11
        bits += enc.encode(raw);    // WIN 0, RAW 01, raw64 (no ESC payload)
        bits += enc.encode(0.1);    // RAW did not advance the initial window
        enc.flush();
        assertEquals(40 + 4 + 68 + 4, bits);
        utils.StreamReader in = new utils.StreamReader(f.toString());
        assertEquals(0xF2, in.readInt(8));
        in.readLong(32);
        assertEquals(0, in.readInt(2)); assertEquals(3, in.readInt(2));
        assertEquals(0, in.readInt(2)); assertEquals(1, in.readInt(2));
        assertEquals(Double.doubleToRawLongBits(raw), in.readLong(64));
        assertEquals(0, in.readInt(2)); assertEquals(3, in.readInt(2));
        DoubleOurEDecoder dec = new DoubleOurEDecoder(f.toString());
        assertEquals(0.1, dec.decodeDouble(), 0);
        assertEquals(raw, dec.decodeDouble(), 0);
        assertEquals(0.1, dec.decodeDouble(), 0);
    }

    @Test public void propertySwitchIsUsedByDefaultConstructor() throws Exception {
        String previous = System.getProperty("salt.raw.enabled");
        try {
            System.clearProperty("salt.raw.enabled");
            assertTrue(new DoubleOurEEncoder(temp.newFile().toString()).isRawEnabled());
            System.setProperty("salt.raw.enabled", "false");
            assertFalse(new DoubleOurEEncoder(temp.newFile().toString()).isRawEnabled());
            System.setProperty("salt.raw.enabled", "true");
            assertTrue(new DoubleOurEEncoder(temp.newFile().toString()).isRawEnabled());
        } finally {
            if (previous == null) System.clearProperty("salt.raw.enabled");
            else System.setProperty("salt.raw.enabled", previous);
        }
    }

    @Test public void invalidReferencesDoNotForceRawWhenAnotherReferenceWorks() throws Exception {
        double[] values = {0.100000000000001, 0.1000000000000001};
        assertEquals(0, roundTrip(values));
        DoubleOurEEncoder enc = new DoubleOurEEncoder(temp.newFile().toString(), true);
        for (double value : values) enc.encode(value);
        assertTrue(enc.getMeta().get("rejected_candidates") > 0);
        assertEquals(0, enc.getRawCount());
        enc.flush();
    }

    @Test public void legacySingleReferenceWindowIsNotMistakenForVersionHeader() throws Exception {
        File f = temp.newFile();
        utils.StreamWriter out = new utils.StreamWriter(f.toString());
        out.write(0, 4); out.write(3, 4); out.write(2, 4); out.write(30000, 20);
        out.write(true); // ZERO referring to the sole initialized 0.1 value
        out.clear();
        assertEquals(0.1, new DoubleOurEDecoder(f.toString()).decodeDouble(), 0);
    }
}
