package ai.omiro.openpocket;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class PocketBleClient implements AutoCloseable {
    private final Context context;
    private final LinkedBlockingQueue<String> controls=new LinkedBlockingQueue<>();
    private final ArrayBlockingQueue<byte[]> audio=new ArrayBlockingQueue<>(2048);
    private final CountDownLatch connected=new CountDownLatch(1), services=new CountDownLatch(1);
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic control;
    private volatile BluetoothGattCharacteristic stream;
    private volatile boolean live;
    private volatile String failure;
    private volatile CountDownLatch descriptor;
    private volatile int descriptorStatus;

    PocketBleClient(Context context) { this.context=context.getApplicationContext(); }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            if (status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) {
                live=true; connected.countDown(); if (!g.requestMtu(247)) g.discoverServices();
            } else if (state==BluetoothProfile.STATE_DISCONNECTED || status!=BluetoothGatt.GATT_SUCCESS) {
                live=false; failure="Recorder disconnected"; connected.countDown(); services.countDown();
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status) { g.discoverServices(); }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {
            if (status==BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service=g.getService(UUID.fromString(Protocol.SERVICE));
                if (service!=null) {
                    control=service.getCharacteristic(UUID.fromString(Protocol.CONTROL));
                    stream=service.getCharacteristic(UUID.fromString(Protocol.AUDIO));
                }
            }
            services.countDown();
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {
            descriptorStatus=status; CountDownLatch latch=descriptor; if (latch!=null) latch.countDown();
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) { receive(c,value); }
        @SuppressWarnings("deprecation") @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) { receive(c,c.getValue()); }
    };
    private void receive(BluetoothGattCharacteristic characteristic,byte[] value) {
        if (value==null) return;
        if (characteristic.getUuid().toString().equals(Protocol.AUDIO)) {
            if (!audio.offer(value.clone())) failure="Audio receive queue overflow";
        } else if (characteristic.getUuid().toString().equals(Protocol.CONTROL)) {
            controls.offer(new String(value,StandardCharsets.UTF_8).trim());
        }
    }
    void connect(String address) throws Exception {
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager.getAdapter();
        if (adapter==null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");
        BluetoothDevice device=adapter.getRemoteDevice(address);
        gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
        if (!connected.await(20,TimeUnit.SECONDS) || !live) throw new IllegalStateException("Recorder connection timed out");
        if (!services.await(20,TimeUnit.SECONDS) || control==null || stream==null) throw new IllegalStateException("Pocket service unavailable");
        subscribe(control); subscribe(stream);
    }
    private void subscribe(BluetoothGattCharacteristic characteristic) throws Exception {
        if (!gatt.setCharacteristicNotification(characteristic,true)) throw new IllegalStateException("BLE notification setup failed");
        BluetoothGattDescriptor cccd=characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (cccd==null) throw new IllegalStateException("BLE notification descriptor missing");
        descriptorStatus=-1; descriptor=new CountDownLatch(1);
        boolean started;
        if (Build.VERSION.SDK_INT>=33) started=gatt.writeDescriptor(cccd,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothGatt.GATT_SUCCESS;
        else { cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); started=gatt.writeDescriptor(cccd); }
        if (!started || !descriptor.await(10,TimeUnit.SECONDS) || descriptorStatus!=0) throw new IllegalStateException("BLE notification setup timed out");
        descriptor=null;
    }
    private synchronized void command(String value) throws Exception {
        if (!live || control==null) throw new IllegalStateException("Recorder disconnected");
        byte[] data=value.getBytes(StandardCharsets.UTF_8);
        boolean started;
        if (Build.VERSION.SDK_INT>=33) started=gatt.writeCharacteristic(control,data,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)==BluetoothGatt.GATT_SUCCESS;
        else { control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE); control.setValue(data); started=gatt.writeCharacteristic(control); }
        if (!started) throw new IllegalStateException("BLE command write failed");
        Thread.sleep(120);
    }
    private String awaitPrefix(String prefix,long timeoutMillis) throws Exception {
        long end=System.currentTimeMillis()+timeoutMillis;
        while (System.currentTimeMillis()<end) {
            if (!live) throw new IllegalStateException("Recorder disconnected");
            String message=controls.poll(Math.min(500,Math.max(1,end-System.currentTimeMillis())),TimeUnit.MILLISECONDS);
            if (message!=null && message.startsWith(prefix)) return message;
            if (message!=null && (message.startsWith("MCU&DISK&ERR") || message.startsWith("MCU&TIMEOUT"))) throw new IllegalStateException("Recorder reported an error");
        }
        throw new IllegalStateException("Recorder response timed out");
    }
    private String awaitStage(String prefix,long timeout,String stage)throws Exception {
        try{return awaitPrefix(prefix,timeout);}catch(IllegalStateException e){
            if("Recorder response timed out".equals(e.getMessage()))throw new IllegalStateException("Recorder timed out: "+stage);
            throw e;
        }
    }
    private void selectFolder(PocketFile file)throws Exception {
        controls.clear();command("APP&LIST&"+file.folder);awaitStage("MCU&LIST&",20000,"folder index");
    }
    void authenticate(String bindingKey) throws Exception {
        if (bindingKey==null || !bindingKey.matches("[0-9a-fA-F]{16}")) throw new IllegalStateException("Pocket binding key is not configured");
        controls.clear(); command("APP&SK&"+bindingKey);
        String response=awaitPrefix("MCU&SK",8000);
        if (!"MCU&SK&OK".equals(response)) throw new IllegalStateException("Pocket binding was rejected");
    }
    List<PocketFile> list() throws Exception {
        controls.clear(); command("APP&STE");
        if (!"MCU&STE&0".equals(awaitPrefix("MCU&STE&",8000))) throw new IllegalStateException("Recorder is busy recording; retry when idle");
        ArrayList<String> folders=new ArrayList<>();
        controls.clear(); command("APP&LIST_DIRS");
        long end=System.currentTimeMillis()+15000, last=System.currentTimeMillis();
        while (System.currentTimeMillis()<end && System.currentTimeMillis()-last<3000) {
            String line=controls.poll(500,TimeUnit.MILLISECONDS); if (line==null) continue;
            if (line.startsWith("MCU&DIRS&")) {
                String folder=line.substring("MCU&DIRS&".length()).trim();
                if (Protocol.folder(folder) && !folders.contains(folder)) folders.add(folder);
                last=System.currentTimeMillis();
            } else if (line.startsWith("MCU&DIRS_SUM")) break;
        }
        ArrayList<PocketFile> files=new ArrayList<>();
        for (String folder:folders) {
            controls.clear(); command("APP&LIST&"+folder);
            long deadline=System.currentTimeMillis()+20000, lastLine=System.currentTimeMillis();
            while (System.currentTimeMillis()<deadline && System.currentTimeMillis()-lastLine<4000) {
                String line=controls.poll(500,TimeUnit.MILLISECONDS); if (line==null) continue;
                PocketFile file=Protocol.listed(line,folder);
                if (file!=null) { files.add(file); lastLine=System.currentTimeMillis(); }
                if (line.startsWith("MCU&LIST&")) break;
            }
        }
        return files;
    }
    interface Progress { void update(long done,long total); }
    long download(PocketFile file,File target,Progress progress) throws Exception {
        if (!Protocol.folder(file.folder) || !Protocol.file(file.name)) throw new IllegalArgumentException("Invalid recorder file");
        selectFolder(file);
        controls.clear(); audio.clear(); failure=null;
        command("APP&U&"+file.folder+"&"+file.name+"&0");
        long size=Protocol.size(awaitStage("MCU&U&",10000,"file size"));
        if (size<=0 || size>100_000_000) throw new IllegalStateException("Invalid recorder file size");
        File wire=new File(target.getPath()+".wire");
        long received=0,lastProgress=System.currentTimeMillis();
        try {
            try(java.io.DataOutputStream raw=new java.io.DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(wire)))) {
                while(true) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if(!live) throw new IllegalStateException("Recorder disconnected");
                    if(failure!=null) throw new IllegalStateException(failure);
                    byte[] chunk=audio.poll(200,TimeUnit.MILLISECONDS);
                    if(chunk==null) {
                        if(received>=size && System.currentTimeMillis()-lastProgress>1000) break;
                        if(System.currentTimeMillis()-lastProgress>15000) throw new IllegalStateException("Recorder transfer stalled");
                        continue;
                    }
                    received+=chunk.length;
                    if(received>size+size/8+8192) throw new IllegalStateException("Recorder sent excessive data");
                    raw.writeInt(chunk.length);raw.write(chunk);lastProgress=System.currentTimeMillis();
                    if(progress!=null)progress.update(Math.min(received,size),size);
                }
            }
            long excess=received-size,written=0;
            byte[] previous=null;
            try(java.io.DataInputStream raw=new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(wire)));FileOutputStream out=new FileOutputStream(target)) {
                while(raw.available()>0) {
                    int length=raw.readInt();if(length<1||length>517)throw new IllegalStateException("Invalid audio packet");
                    byte[] chunk=new byte[length];raw.readFully(chunk);
                    // Equal packets are only candidates when the entire transfer is oversized.
                    if(excess>=length && written>0 && written%4096==0 && Arrays.equals(previous,chunk)){excess-=length;continue;}
                    if(written+length>size)throw new IllegalStateException("Audio byte count mismatch");
                    out.write(chunk);written+=length;previous=chunk;
                }
                if(written!=size||excess!=0)throw new IllegalStateException("Audio byte count mismatch");
                out.getFD().sync();
            }
            return size;
        } finally {wire.delete();}
    }
    private volatile boolean wifiPowered;
    long[] storage()throws Exception {
        controls.clear();command("APP&SPACE");long[] result=Protocol.space(awaitPrefix("MCU&SPA&",8000));
        if(result==null)throw new IllegalStateException("Recorder storage unavailable");return result;
    }
    int battery() throws Exception {
        controls.clear();command("APP&BAT");String value=awaitPrefix("MCU&BAT&",8000);
        try {int percent=Integer.parseInt(value.substring(8));return percent>=0&&percent<=100?percent:0;}
        catch(NumberFormatException e){return 0;}
    }
    String[] startWifi() throws Exception {
        controls.clear();command("APP&STE");
        if(!"MCU&STE&0".equals(awaitPrefix("MCU&STE&",8000)))throw new IllegalStateException("Recorder is busy recording; retry when idle");
        controls.clear();command("APP&WIFI");String reply=awaitPrefix("MCU&WIFI&",8000);
        String[] parts=reply.split("&",4);
        if(parts.length!=4||parts[2].isEmpty()||parts[2].getBytes(StandardCharsets.UTF_8).length>32||parts[3].length()<8||parts[3].length()>63)
            throw new IllegalStateException("Invalid recorder Wi-Fi configuration");
        command("APP&WIFIC");Thread.sleep(2500);controls.clear();wifiPowered=true;command("APP&WIFIO");awaitPrefix("MCU&WIFIO",10000);Thread.sleep(4000);
        return new String[]{parts[2],parts[3]};
    }
    long startWifiFile(PocketFile file)throws Exception{
        if(!Protocol.folder(file.folder)||!Protocol.file(file.name))throw new IllegalArgumentException("Invalid recorder file");
        selectFolder(file);audio.clear();
        controls.clear();command("APP&U&"+file.folder+"&"+file.name+"&0");long size=Protocol.size(awaitStage("MCU&U&",10000,"file size"));
        if(size<=0||size>100_000_000)throw new IllegalStateException("Invalid recorder file size");
        // Some firmware starts TCP audio without a control acknowledgement.
        // The caller validates the complete announced payload, never an acknowledgement alone.
        command("APP&U&WIFI");return size;
    }
    void stopWifi(){if(wifiPowered){try{command("APP&WIFIC");}catch(Exception ignored){}wifiPowered=false;}}
    void delete(PocketFile file) throws Exception {
        if (!Protocol.folder(file.folder) || !Protocol.file(file.name)) throw new IllegalArgumentException("Invalid recorder file");
        controls.clear();
        command("APP&D&/SD:/RECORD/"+file.folder+"/"+file.name+".MP3");
        String response=awaitPrefix("MCU&D",10000);
        if (!"MCU&D".equals(response)) throw new IllegalStateException("Recorder deletion was rejected");
    }
    boolean isConnected() { return live; }
    @Override public void close() {
        stopWifi();live=false;
        BluetoothGatt current=gatt;
        if (current!=null) { current.disconnect(); current.close(); gatt=null; }
    }
}
