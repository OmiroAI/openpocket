package ai.omiro.openpocket;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class WifiTransferTest {
    @Test public void fragmentedPayloadExcludesPadding()throws Exception{
        byte[] data=new byte[44000];for(int i=0;i<data.length;i++)data[i]=(byte)i;
        ByteArrayInputStream source=new ByteArrayInputStream(data);
        InputStream fragmented=new FilterInputStream(source){@Override public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(n,113));}};
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        assertEquals(41001,WifiTransfer.copy(fragmented,out,41001,null));
        assertArrayEquals(java.util.Arrays.copyOf(data,41001),out.toByteArray());assertEquals(2999,source.available());
    }
    @Test public void shortStreamIsNotDelivered()throws Exception{
        try{WifiTransfer.copy(new ByteArrayInputStream(new byte[10]),new ByteArrayOutputStream(),11,null);fail();}catch(EOFException expected){}
    }
    @Test public void rejectsInvalidSizesBeforeReading()throws Exception{
        for(long size:new long[]{-1,0,100000001}){try{WifiTransfer.copy(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),size,null);fail();}catch(IOException expected){}}
    }
    @Test public void interruptionStopsCopy()throws Exception{
        Thread.currentThread().interrupt();try{WifiTransfer.copy(new ByteArrayInputStream(new byte[1]),new ByteArrayOutputStream(),1,null);fail();}catch(InterruptedIOException expected){}finally{Thread.interrupted();}
    }
}
