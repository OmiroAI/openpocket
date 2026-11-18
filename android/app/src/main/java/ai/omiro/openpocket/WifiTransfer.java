package ai.omiro.openpocket;

import java.io.*;

/** Exact-length payload; TCP may append recorder transport padding. */
final class WifiTransfer {
    static long copy(InputStream input, OutputStream output, long size, PocketBleClient.Progress progress) throws IOException {
        if(size<=0||size>100_000_000)throw new IOException("Invalid recording size");
        byte[] buffer=new byte[32768];long done=0;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
        while(done<size){
            if(System.nanoTime()>deadline)throw new java.net.SocketTimeoutException("Recording transfer deadline exceeded");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            int count=input.read(buffer,0,(int)Math.min(buffer.length,size-done));
            if(count<0)throw new EOFException("Incomplete recording");
            if(count==0)continue;
            output.write(buffer,0,count);done+=count;
            if(progress!=null)progress.update(done,size);
        }
        return done;
    }
}
