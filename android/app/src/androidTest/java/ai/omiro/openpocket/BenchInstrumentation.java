package ai.omiro.openpocket;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.media.MediaExtractor;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Explicitly invoked bench runner. Never packaged in the application release. */
public class BenchInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private void check(boolean condition,String description){if(!condition)throw new AssertionError(description);}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();
        if("agent".equals(args.getString("mode"))){agent(context,result);}
        else if("wifi".equals(args.getString("mode"))){wifi(context,result);}
        else if("provision".equals(args.getString("mode"))){provision(context);result.putString("stream","PASS: binding key saved encrypted, read back, and staging file removed\n");}
        else if("device".equals(args.getString("mode"))){device(context,result);}
        else {state(context);result.putString("stream","PASS: persistent state, destination isolation, immutable attempted delivery, retry, and Keystore storage\n");}
        finish(-1,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e.getClass().getSimpleName()+" "+safe(e)+"\n");finish(0,result);}}
    private String safe(Throwable e){if(e instanceof AssertionError)return e.getMessage();if(e instanceof AgentApi.Failure)return e.getMessage();return "Inspect app behavior; private configuration withheld";}
    private void agent(Context context,Bundle result)throws Exception{
        File input=new File(context.getFilesDir(),"agent-input.json");
        try(Store store=new Store(context)){
            JSONObject config=new JSONObject(new String(Files.readAllBytes(input.toPath()),StandardCharsets.UTF_8));
            AgentApi api=new AgentApi();JSONObject info=api.info(config.getString("url"),config.getString("token"));
            Destination destination=store.saveDestination(config.getString("name"),config.getString("url"),info.getString("destination_id"));
            new SecureStore(context).put("upload:"+destination.id,config.getString("token"));new AppSettings(context).destinationId(destination.id);store.bindUnassigned(destination.id);
            List<Recording> recordings=store.recordings();recordings.sort(Comparator.comparingLong(r->r.durationSeconds));Recording selected=null;
            for(Recording r:recordings)if(r.localPath!=null&&r.durationSeconds>=10&&new File(r.localPath).isFile()&&!r.attempted){selected=r;break;}
            check(selected!=null,"No downloaded, unattempted recording available");
            if(!destination.id.equals(selected.destinationId)){check(store.reassign(selected.id,destination.id),"Could not assign test destination");selected=store.recording(selected.id);}
            store.state(selected.id,"uploading",null,0,null);
            JSONObject receipt=api.deliver(destination,selected,new File(selected.localPath),config.getString("token"),null);
            JSONObject duplicate=api.deliver(destination,selected,new File(selected.localPath),config.getString("token"),null);
            check(receipt.getString("receipt_id").equals(duplicate.getString("receipt_id")),"Duplicate receipt mismatch");
            store.delivered(selected.id,receipt.toString(),(long)(receipt.getDouble("accepted_at")*1000));store.event("delivery","Live HTTPS acceptance and deduplication verified");
            result.putString("stream","PASS: agent configured, HTTPS delivery accepted, duplicate receipt stable; job="+receipt.getString("job_id")+" bytes="+selected.size+"\n");
        }finally{Files.deleteIfExists(input.toPath());}
    }
    private void wifi(Context context,Bundle result)throws Exception{
        android.app.Activity activity=startActivitySync(new android.content.Intent(context,MainActivity.class).putExtra("bench",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        PocketBleClient ble=new PocketBleClient(context);PocketWifiClient wifi=new PocketWifiClient(context);
        File first=new File(context.getFilesDir(),"bench-wifi.mp3"),second=new File(context.getFilesDir(),"bench-wifi-repeat.mp3"),bluetooth=new File(context.getFilesDir(),"bench-wifi-ble.mp3");
        try{
            ble.connect(new AppSettings(context).device());ble.authenticate(new SecureStore(context).get("pocket_binding"));check(ble.battery()>10,"Battery too low for Wi-Fi bench");
            List<PocketFile> files=ble.list();files.sort(Comparator.comparingLong(f->f.durationSeconds));PocketFile chosen=null;
            for(PocketFile f:files)if(f.durationSeconds>=10){chosen=f;break;}check(chosen!=null,"No suitable recording");
            Bundle step=new Bundle();step.putString("stream","Joining Pocket Wi-Fi; approve Android prompt if shown\n");sendStatus(1,step);
            long bytes=wifi.download(ble,chosen,first,null);String hash=AgentApi.digest(first);
            wifi=new PocketWifiClient(context);wifi.download(ble,chosen,second,null);check(hash.equals(AgentApi.digest(second)),"Repeated Wi-Fi payload differs");
            ble.close();ble=new PocketBleClient(context);ble.connect(new AppSettings(context).device());ble.authenticate(new SecureStore(context).get("pocket_binding"));ble.list();
            ble.download(chosen,bluetooth,null);check(hash.equals(AgentApi.digest(bluetooth)),"Wi-Fi differs from Bluetooth");
            MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(first.getAbsolutePath());check(extractor.getTrackCount()>0,"Wi-Fi audio unrecognized");}finally{extractor.release();}
            result.putString("stream","PASS: Wi-Fi bytes="+bytes+", two fresh Wi-Fi sessions match Bluetooth SHA256, Android media recognized\n");
        }finally{wifi.close();ble.close();runOnMainSync(activity::finish);}
    }
    private void provision(Context context)throws Exception{
        File input=new File(context.getFilesDir(),"binding-input");
        try{
            String key=new String(Files.readAllBytes(input.toPath()),StandardCharsets.UTF_8).trim();
            check(key.matches("[0-9a-fA-F]{16}"),"Invalid binding key format");
            SecureStore secrets=new SecureStore(context);secrets.put("pocket_binding",key);
            check(key.equals(secrets.get("pocket_binding")),"Protected key verification failed");
            String prefs=new String(Files.readAllBytes(new File(context.getApplicationInfo().dataDir,"shared_prefs/protected.xml").toPath()),StandardCharsets.UTF_8);
            check(!prefs.contains(key),"Plaintext key found in preferences");
        }finally{Files.deleteIfExists(input.toPath());}
        check(!input.exists(),"Staging file remains");
    }
    private void state(Context context)throws Exception{
        String dbName="bench-"+UUID.randomUUID()+".db";String id;
        try(Store store=new Store(context,dbName)){
            Destination a=store.saveDestination("A","https://a.invalid","a");
            store.discover("AA:BB:CC:DD:EE:FF",new PocketFile("2000-01-01","20000101000000",1),a.id);
            Recording r=store.recordings().get(0);id=r.id;store.downloaded(id,"/private/synthetic",12,"hash");
            Destination b=store.saveDestination("B","https://b.invalid","b");r=store.recording(id);
            check(r.destinationId.equals(a.id)&&!r.approved,"Destination changed silently");
            check(store.reassign(id,b.id),"Unattempted delivery could not move");
            store.state(id,"uploading",null,0,null);store.recoverActive();r=store.recording(id);
            check(r.state.equals("downloaded")&&r.attempted,"Restart lost upload history");
            check(!store.reassign(id,a.id),"Uncertain delivery redirected");
            store.state(id,"failed",null,0,"Network unavailable");store.retry(id);
            check(store.recording(id).localPath.equals("/private/synthetic"),"Retry lost local audio");
            store.delivered(id,"receipt",100);store.clearLocal(id);r=store.recording(id);
            check(r.state.equals("delivered")&&r.localPath==null&&r.deliveredAt==100,"Cleanup lost durable delivery");
        }
        try(Store reopened=new Store(context,dbName)){check(reopened.recording(id).state.equals("delivered"),"State did not survive reopening");}
        SecureStore secrets=new SecureStore(context);String name="bench-"+UUID.randomUUID();secrets.put(name,"synthetic-test-secret");check("synthetic-test-secret".equals(secrets.get(name)),"Keystore round trip failed");String prefs=new String(Files.readAllBytes(new File(context.getApplicationInfo().dataDir,"shared_prefs/protected.xml").toPath()),StandardCharsets.UTF_8);check(!prefs.contains("synthetic-test-secret"),"Secret stored in plaintext");secrets.remove(name);
        context.deleteDatabase(dbName);
    }
    private void device(Context context,Bundle result)throws Exception{
        File input=new File(context.getFilesDir(),"bench-input.json");JSONObject config=new JSONObject(new String(Files.readAllBytes(input.toPath()),StandardCharsets.UTF_8));
        AgentApi api=new AgentApi();JSONObject info=api.info(config.getString("url"),config.getString("token"));
        Destination destination=new Destination("bench","Bench service",config.getString("url"),info.getString("destination_id"));
        PocketBleClient client=new PocketBleClient(context);File first=new File(context.getFilesDir(),"bench-first.mp3"),second=new File(context.getFilesDir(),"bench-second.mp3");
        try{
            client.connect(config.getString("device"));client.authenticate(config.getString("binding"));
            List<PocketFile> recordings=client.list();check(!recordings.isEmpty(),"No recordings discovered");
            recordings.sort(Comparator.comparingLong(r->r.durationSeconds));PocketFile selected=recordings.get(0);for(PocketFile f:recordings){if(f.durationSeconds>=10){selected=f;break;}}
            long size=client.download(selected,first,null);client.download(selected,second,null);String hash=AgentApi.digest(first);
            check(size==first.length()&&hash.equals(AgentApi.digest(second)),"Repeated downloads differ");
            MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(first.getAbsolutePath());check(extractor.getTrackCount()>0,"Audio is not recognized");}finally{extractor.release();}
            Recording r=new Recording("bench:"+selected.folder+":"+selected.name,config.getString("device"),selected.folder,selected.name,"downloaded",first.getAbsolutePath(),destination.id,null,Protocol.timestampMillis(selected.name),selected.durationSeconds,size,0,java.time.Instant.ofEpochMilli(Protocol.timestampMillis(selected.name)).toString(),hash,true,false,true);
            JSONObject one=api.deliver(destination,r,first,config.getString("token"),null);
            JSONObject two=api.deliver(destination,r,first,config.getString("token"),null);
            check(one.getString("receipt_id").equals(two.getString("receipt_id")),"Duplicate delivery created a new receipt");
            result.putString("stream","PASS: recorder discovery count="+recordings.size()+", repeated download bytes="+size+", SHA256 match, Android media recognition, HTTP acceptance and deduplicated receipt\n");
        }finally{client.close();input.delete();}
    }
}
