package SALT;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public class SALTSQLCrudPersistenceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void modificationsPersistAcrossReopenAndPreserveOtherWindows() throws Exception {
        String base = new File(folder.getRoot(), "sample").getPath();
        List<BigDecimal> expected = new ArrayList<>(Arrays.asList(
                new BigDecimal("1.25"), new BigDecimal("1.5"),
                new BigDecimal("2.25"), new BigDecimal("2.5")));
        String first = SALTSQL.encodeAsOneWindow01String(expected.subList(0, 2), 5, 2);
        String second = SALTSQL.encodeAsOneWindow01String(expected.subList(2, 4), 5, 2);
        writeBits(base + ".bin", "011101010010" + first + second);
        sidecar(base + "_window_len.bin", first.length(), second.length());
        sidecar(base + "_len_fenwick.bin", first.length(), first.length() + second.length());
        sidecar(base + "_window_num.bin", 2, 2);
        sidecar(base + "_num_fenwick.bin", 2, 4);
        verify(base, expected);

        modify(base, "updateRecordByIndex", 0, new BigDecimal("12.75"));
        expected.set(0, new BigDecimal("12.75"));
        verify(base, expected);
        modify(base, "insertRecordByIndex", 1, new BigDecimal("1.75"));
        expected.add(1, new BigDecimal("1.75"));
        verify(base, expected);
        modify(base, "deleteRecordByIndex", 0, null);
        expected.remove(0);
        verify(base, expected);
        modify(base, "insertRecordByIndex", expected.size(), new BigDecimal("2.75"));
        expected.add(new BigDecimal("2.75"));
        verify(base, expected);
    }

    private void modify(String base, String name, long index, BigDecimal value) throws Exception {
        try (SALTSQL_CRUD crud = SALTSQL_CRUD.openByName(base)) {
            Method method = value == null
                    ? SALTSQL_CRUD.class.getDeclaredMethod(name, long.class)
                    : SALTSQL_CRUD.class.getDeclaredMethod(name, long.class, BigDecimal.class);
            method.setAccessible(true);
            if (value == null) method.invoke(crud, index);
            else method.invoke(crud, index, value);
        }
    }

    private void verify(String base, List<BigDecimal> expected) throws Exception {
        try (SALTSQL_CRUD crud = SALTSQL_CRUD.openByName(base)) {
            assertEquals(expected.size(), crud.getTotalRecords());
            List<BigDecimal> actual = crud.getRangeByRecordIndex(0, expected.size());
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++)
                assertEquals("record " + i, 0, expected.get(i).compareTo(actual.get(i)));
        }
    }

    private void sidecar(String path, int a, int b) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(path))) {
            out.writeByte(32);
            out.writeInt(2);
            out.writeInt(a);
            out.writeInt(b);
        }
    }

    private void writeBits(String path, String bits) throws IOException {
        try (OutputStream out = new FileOutputStream(path)) {
            for (int i = 0; i < bits.length(); i += 8) {
                int octet = 0;
                for (int j = 0; j < 8; j++)
                    octet = (octet << 1) | (i + j < bits.length() ? bits.charAt(i + j) - '0' : 0);
                out.write(octet);
            }
        }
    }
}
