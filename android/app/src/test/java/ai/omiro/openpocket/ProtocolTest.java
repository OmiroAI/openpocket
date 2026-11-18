package ai.omiro.openpocket;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProtocolTest {
    @Test public void parsesBothListShapesAndRejectsPaths() {
        assertEquals(9, Protocol.listed("MCU&F&2026-10-06&20261006112010&9", "2026-10-06").durationSeconds);
        assertEquals("20261006112010", Protocol.listed("MCU&F&20261006112010&9", "2026-10-06").name);
        assertNull(Protocol.listed("MCU&F&../../tmp&20261006112010&9", "2026-10-06"));
        assertEquals(-1, Protocol.size("MCU&U&WIFI"));
        assertEquals(36894, Protocol.size("MCU&U&36894&0"));
    }
    @Test public void storageRejectsMalformedAndImpossibleValues(){
        assertArrayEquals(new long[]{10,100},Protocol.space("MCU&SPA&10&100"));
        assertArrayEquals(new long[]{0,1},Protocol.space("MCU&SPA&0&1"));
        for(String value:new String[]{"MCU&SPA&-1&100","MCU&SPA&101&100","MCU&SPA&0&0","MCU&SPA&x&100","MCU&SPA&1&9223372036854775807","MCU&SPA&1&2&3"})assertNull(Protocol.space(value));
    }
    @Test public void storageUsesMebibytesRatherThanKilobytes(){
        long[] space=Protocol.space("MCU&SPA&059000&060000");
        assertNotNull(space);
        assertEquals(1048576000L,(space[1]-space[0])*Protocol.STORAGE_UNIT_BYTES);
        assertEquals(62914560000L,space[1]*Protocol.STORAGE_UNIT_BYTES);
        assertEquals(1.66667,100.0*(space[1]-space[0])/space[1],0.00001);
    }
    @Test public void buildsStableSafeRemoteName() {
        assertEquals("aabbccddeeff_20261006112010", Protocol.remoteStem("AA:BB:CC:DD:EE:FF", "20261006112010"));
    }
}
