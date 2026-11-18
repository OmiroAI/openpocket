package ai.omiro.openpocket;

import android.content.Context;
import android.media.MediaExtractor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class SyncEngine {
    interface Listener { void changed(String status); }
    interface Result { void done(String error); }
    private final Context context; private final Store store;private final AppSettings settings;private final SecureStore secrets;private final Listener listener;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final AgentApi api=new AgentApi();
    private volatile PocketWifiClient wifi;private boolean wifiThisCycle;private long wifiRetryAfter;
    private volatile boolean yieldDownloads;private volatile long foregroundVersion;
    private volatile PocketBleClient pocket;private volatile boolean visible;private volatile String playingPath;private long lastProgress;
    SyncEngine(Context context,Store store,AppSettings settings,SecureStore secrets,Listener listener){this.context=context.getApplicationContext();this.store=store;this.settings=settings;this.secrets=secrets;this.listener=listener;worker.execute(store::recoverActive);worker.scheduleWithFixedDelay(this::cycle,2,30,TimeUnit.SECONDS);}
    void visible(boolean value){visible=value;if(value)syncNow();else{foregroundVersion++;api.cancel();PocketWifiClient w=wifi;if(w!=null)w.close();PocketBleClient p=pocket;if(p!=null)p.close();pocket=null;}}
    void stop(){visible(false);worker.shutdownNow();}
    void syncNow(){if(!worker.isShutdown())worker.execute(this::cycle);}
    void playing(String path){playingPath=path;}
    void retry(String id){worker.execute(()->{store.retry(id);cycle();});}
    void destination(String name,String url,String token,Result result){api.cancel();worker.execute(()->{try{
        urlCheck(name,url,token);String normalized=AgentApi.validUrl(url);JSONObject info=api.info(normalized,token);
        if(info.getInt("api_version")!=1)throw new AgentApi.Failure("Unsupported service API");
        Destination current=store.destination(settings.destinationId());
        if(current!=null && current.name.equals(name) && current.url.equals(normalized) && current.remoteId.equals(info.getString("destination_id"))){secrets.put("upload:"+current.id,token);}
        else{Destination d=store.saveDestination(name,normalized,info.getString("destination_id"));secrets.put("upload:"+d.id,token);settings.destinationId(d.id);store.bindUnassigned(d.id);}
        store.event("settings","Agent destination verified");result.done(null);changed("Agent destination saved");
    }catch(Exception e){result.done(safe(e));}});}
    private void urlCheck(String name,String url,String token)throws Exception{if(name.isBlank()||name.length()>80||token.length()<32)throw new AgentApi.Failure("Enter an agent name and its upload token");AgentApi.validUrl(url);}
    void recorder(String address,String key,Result result){worker.execute(()->{try{if(!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address))throw new AgentApi.Failure("Choose a valid recorder address");if(!key.isBlank()){if(!key.matches("[0-9a-fA-F]{16}"))throw new AgentApi.Failure("Binding key must contain 16 hex characters");secrets.put("pocket_binding",key);}if(secrets.get("pocket_binding")==null)throw new AgentApi.Failure("Enter the recorder binding key");settings.device(address);if(pocket!=null)pocket.close();pocket=null;result.done(null);cycle();}catch(Exception e){result.done(safe(e));}});}
    void approveOriginal(String id){worker.execute(()->{store.approveOriginal(id);cycle();});}
    void reassign(String id){worker.execute(()->{String active=settings.destinationId();if(active!=null&&store.reassign(id,active))changed("Destination assignment updated");else changed("An attempted delivery must finish at its original destination");});}
    void deleteFromPocket(Recording r){worker.execute(()->{try{if(pocket==null||!pocket.isConnected()||!settings.device().equalsIgnoreCase(r.device))throw new AgentApi.Failure("Connect to this recorder first");pocket.delete(new PocketFile(r.folder,r.file,r.durationSeconds));store.deletedFromPocket(r.id);settings.invalidateStorage();store.event("recorder","Confirmed deletion completed");changed("Deleted from Pocket");}catch(Exception e){changed("Deletion failed: "+safe(e));}});}
    void cleanOne(Recording r){worker.execute(()->{if("delivered".equals(r.state)&&removeLocal(r))changed("Delivered local copy removed");});}
    void bulk(String action,List<String> ids){
        long requestedVersion=foregroundVersion;yieldDownloads=true;
        worker.execute(()->{int completed=0;String error=null;
            try{
                if(!visible||foregroundVersion!=requestedVersion)throw new AgentApi.Failure("Action cancelled after leaving the app");
                if(action.equals("upload")){uploadPending(ids);return;}
                for(String id:ids){
                    if(!visible||foregroundVersion!=requestedVersion)throw new AgentApi.Failure("Action stopped because the app closed");
                    Recording r=store.recording(id);if(r==null)continue;
                    if(action.equals("retry")){if(r.state.equals("failed")){store.retry(id);completed++;}}
                    else if(action.equals("clean")){if(r.state.equals("delivered")&&removeLocal(r))completed++;}
                    else if(action.equals("delete")){
                        if(!r.onPocket)continue;
                        if(pocket==null||!pocket.isConnected()||!settings.device().equalsIgnoreCase(r.device))throw new AgentApi.Failure("Connect to this recorder first");
                        pocket.delete(new PocketFile(r.folder,r.file,r.durationSeconds));store.deletedFromPocket(id);settings.invalidateStorage();completed++;
                        changed("Deleting from Pocket · "+completed+" of "+ids.size());
                    }
                }
            }catch(Exception e){error=safe(e);}finally{
                yieldDownloads=false;
                String summary=(action.equals("upload")?"Upload action finished · check recording statuses":action+": "+completed+" completed")+(error==null?"":" · stopped: "+error);
                store.event("bulk",summary);changed(summary);
            }
        });
    }
    private void cycle(){if(!visible||worker.isShutdown())return;store.retryDue();cleanup();
        try{String address=settings.device();if(!address.isBlank())syncPocket(address);}catch(Exception e){PocketBleClient p=pocket;if(p!=null)p.close();pocket=null;if(visible){store.event("recorder",safe(e));changed(safe(e));}}
        if(visible&&!yieldDownloads)uploadPending();
        if(visible){long downloads=0,uploads=0;for(Recording r:store.recordings())if(r.state.equals("failed")){if(r.localPath==null)downloads++;else uploads++;}changed(downloads+uploads>0?downloads+" download failures · "+uploads+" uploads need attention":pocket!=null&&pocket.isConnected()?"Pocket connected · queue checked":"Pocket disconnected · local queue checked");}
    }
    private void syncPocket(String address)throws Exception{
        if(pocket==null||!pocket.isConnected()){changed("Connecting to Pocket");PocketBleClient p=new PocketBleClient(context);pocket=p;p.connect(address);p.authenticate(secrets.get("pocket_binding"));store.event("recorder","Pocket connected");}
        wifiThisCycle=System.currentTimeMillis()>=wifiRetryAfter&&settings.preferWifi()&&context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(wifiThisCycle){try{wifiThisCycle=pocket.battery()>10;}catch(Exception unavailable){wifiThisCycle=false;}}
        if(!visible)return;try{long[] space=pocket.storage();settings.storage(address,space[0],space[1]);}catch(Exception unavailable){settings.invalidateStorage();}
        if(!visible)return;changed("Discovering recordings");List<PocketFile> files=pocket.list();for(PocketFile file:files)store.discover(address,file,settings.destinationId());store.bindUnassigned(settings.destinationId());changed("Found "+files.size()+" recordings");
        for(Recording r:store.recordings()){if(!visible||yieldDownloads)return;if(address.equalsIgnoreCase(r.device)&&r.onPocket&&"pending".equals(r.state))download(r);}
    }
    private void download(Recording r){File directory=new File(context.getFilesDir(),"recordings");if(!directory.isDirectory()&&!directory.mkdirs()){fail(r,"Private storage unavailable");return;}
        String stem=Protocol.remoteStem(r.device,r.file);File temp=new File(directory,"."+stem+".part"),target=new File(directory,stem+".mp3");
        try{if(directory.getUsableSpace()<110_000_000)throw new AgentApi.Failure("Phone storage is low");store.state(r.id,"downloading",null,0,null);changed("Downloading recording");long size=downloadAudio(new PocketFile(r.folder,r.file,r.durationSeconds),temp);
            MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(temp.getAbsolutePath());if(extractor.getTrackCount()==0)throw new IOException();}finally{extractor.release();}
            String hash=AgentApi.digest(temp);java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            store.downloaded(r.id,target.getAbsolutePath(),size,hash);store.event("download","Recording saved and checked");changed("Recording downloaded");
        }catch(Exception e){temp.delete();fail(r,safe(e));}
    }
    private long downloadAudio(PocketFile file,File temp)throws Exception{
        if(wifiThisCycle){
            PocketWifiClient client=new PocketWifiClient(context);wifi=client;
            try{if(!visible)throw new InterruptedException();changed("Joining Pocket Wi-Fi · approve Android’s connection prompt");
                long size=client.download(pocket,file,temp,(done,total)->progress("Downloading over Wi-Fi",done,total));
                store.event("download","Wi-Fi transfer completed");return size;
            }catch(Exception e){wifiThisCycle=false;wifiRetryAfter=System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(5);if(!visible)throw e;
                store.event("download",safe(e)+" · retrying over Bluetooth");changed("Wi-Fi unavailable · using Bluetooth");
                PocketBleClient prior=pocket;if(prior!=null)prior.close();
                PocketBleClient replacement=new PocketBleClient(context);pocket=replacement;replacement.connect(settings.device());replacement.authenticate(secrets.get("pocket_binding"));replacement.list();
            }finally{client.close();wifi=null;}
        }
        if(!visible)throw new InterruptedException();
        return pocket.download(file,temp,(done,total)->progress("Downloading over Bluetooth",done,total));
    }
    private void uploadPending(){uploadPending(null);}
    private void uploadPending(List<String> ids){for(Recording r:store.recordings()){if(ids!=null&&!ids.contains(r.id))continue;if(!visible)return;if(!RecordingPresentation.readyToUpload(r))continue;Destination d=store.destination(r.destinationId);if(d==null){fail(r,"Destination unavailable");continue;}
        try{store.state(r.id,"uploading",null,0,null);changed("Delivering recording");JSONObject receipt=api.deliver(d,r,new File(r.localPath),secrets.get("upload:"+d.id),(done,total)->progress("Uploading",done,total));store.delivered(r.id,receipt.toString(),(long)(receipt.getDouble("accepted_at")*1000));store.event("delivery","Recording accepted by agent service");changed("Recording delivered");}catch(Exception e){fail(r,safe(e));}}
    }
    private void progress(String stage,long done,long total){long now=System.currentTimeMillis();if(now-lastProgress>300||done==total){lastProgress=now;changed(stage+" · "+(done*100/Math.max(1,total))+"%");}}
    private void fail(Recording r,String error){store.state(r.id,"failed",null,0,error);if(visible){String stage=r.localPath==null?"Download":"Upload";store.event(stage.toLowerCase(java.util.Locale.US),stage+": "+error);changed(stage+": "+error);}}
    static String safe(Exception e){if(e instanceof AgentApi.Failure)return ((AgentApi.Failure)e).safe;if(e instanceof SecurityException)return "Bluetooth permission is required";if(e instanceof javax.net.ssl.SSLException)return "Server certificate could not be verified";if(e instanceof java.net.SocketException||e instanceof java.net.UnknownHostException||e instanceof java.net.SocketTimeoutException)return "Network unavailable";if(e instanceof InterruptedException||e instanceof IOException)return "Transfer interrupted";if(e instanceof IllegalStateException){String m=e.getMessage();if(m!=null&&m.length()<100)return m;}return "Operation failed; check configuration and retry";}
    private void cleanup(){long before=System.currentTimeMillis()-TimeUnit.DAYS.toMillis(7);for(Recording r:store.recordings())if("delivered".equals(r.state)&&r.deliveredAt>0&&r.deliveredAt<=before)removeLocal(r);}
    private boolean removeLocal(Recording r){if(r.localPath==null||r.localPath.equals(playingPath))return false;File expected=new File(new File(context.getFilesDir(),"recordings"),Protocol.remoteStem(r.device,r.file)+".mp3");if(!expected.getAbsolutePath().equals(r.localPath))return false;if(expected.exists()&&!expected.delete())return false;store.clearLocal(r.id);store.event("storage","Delivered local copy removed");return true;}
    private void changed(String status){listener.changed(status);}
}
