package ai.omiro.openpocket;

import android.content.Context;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** One local-only request per recorder Wi-Fi boot. Never changes process routing. */
final class PocketWifiClient implements AutoCloseable {
    private final ConnectivityManager manager;
    private final CountDownLatch ready=new CountDownLatch(1);
    private volatile Network network;
    private volatile Socket socket;
    private volatile boolean closed;
    private boolean registered;
    private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network value){if(!closed)network=value;ready.countDown();}
        @Override public void onUnavailable(){ready.countDown();}
        @Override public void onLost(Network value){if(value.equals(network))close();}
    };
    PocketWifiClient(Context context){manager=context.getSystemService(ConnectivityManager.class);}
    long download(PocketBleClient ble,PocketFile file,File target,PocketBleClient.Progress progress)throws Exception{
        boolean success=false;String stage="startup";
        try{
            String[] credentials=ble.startWifi();
            stage="network approval";NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(new WifiNetworkSpecifier.Builder().setSsid(credentials[0]).setWpa2Passphrase(credentials[1]).build()).build();
            synchronized(this){if(closed)throw new IOException();manager.requestNetwork(request,callback,90000);registered=true;}
            if(!ready.await(95,TimeUnit.SECONDS)||network==null||closed)throw new IOException("Pocket Wi-Fi unavailable");
            stage="TCP connection";Socket current=new Socket();synchronized(this){if(closed){current.close();throw new IOException();}socket=current;}
            stage="socket binding";network.bindSocket(current);stage="TCP connection";current.connect(new InetSocketAddress("192.168.200.1",8475),10000);current.setSoTimeout(15000);
            stage="file selection";long size=ble.startWifiFile(file);
            stage="audio stream";try(FileOutputStream output=new FileOutputStream(target)){
                WifiTransfer.copy(current.getInputStream(),output,size,progress);output.getFD().sync();
            }
            success=true;return size;
        }catch(IllegalStateException e){throw new AgentApi.Failure("Pocket Wi-Fi "+stage+": "+SyncEngine.safe(e));}catch(IOException e){throw new AgentApi.Failure("Pocket Wi-Fi "+stage+" failed ("+e.getClass().getSimpleName()+" "+errno(e)+")");}finally{close();ble.stopWifi();if(!success)target.delete();}
    }
    private String errno(IOException e){String text=String.valueOf(e.getMessage());for(String code:new String[]{"EPERM","EACCES","ENONET","ENETUNREACH","ECONNREFUSED","ETIMEDOUT","ECONNABORTED","ENETDOWN","EHOSTUNREACH"})if(text.contains(code))return code;return closed?"network lost or cancelled":"unspecified";}
    @Override public synchronized void close(){
        closed=true;ready.countDown();
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
        if(registered){registered=false;try{manager.unregisterNetworkCallback(callback);}catch(IllegalArgumentException ignored){}}
    }
}
