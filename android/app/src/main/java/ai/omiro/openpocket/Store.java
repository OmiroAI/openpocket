package ai.omiro.openpocket;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

final class Store extends SQLiteOpenHelper {
    final CaptureLocation location;
    Store(Context context) { this(context,"openpocket.db"); }
    Store(Context context,String name) { super(context,name,null,2); location = new CaptureLocation(context); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE destinations(id TEXT PRIMARY KEY,name TEXT NOT NULL,url TEXT NOT NULL,remote_id TEXT NOT NULL)");
        db.execSQL("CREATE TABLE recordings(id TEXT PRIMARY KEY,device TEXT NOT NULL,folder TEXT NOT NULL,file TEXT NOT NULL,state TEXT NOT NULL,local_path TEXT,destination_id TEXT,error TEXT,created_at INTEGER NOT NULL,duration_seconds INTEGER NOT NULL,size INTEGER NOT NULL DEFAULT 0,delivered_at INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,created_iso TEXT NOT NULL,sha256 TEXT,approved INTEGER NOT NULL DEFAULT 1,attempted INTEGER NOT NULL DEFAULT 0,on_pocket INTEGER NOT NULL DEFAULT 1,receipt TEXT,location TEXT)");
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,category TEXT NOT NULL,message TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { if(oldVersion<2) db.execSQL("ALTER TABLE recordings ADD COLUMN location TEXT"); }
    synchronized Destination saveDestination(String name,String url,String remoteId) {
        String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();
        v.put("id",id);v.put("name",name);v.put("url",url);v.put("remote_id",remoteId);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { db.insertOrThrow("destinations",null,v);db.execSQL("UPDATE recordings SET approved=0 WHERE destination_id IS NOT NULL AND state!='delivered'");db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        return new Destination(id,name,url,remoteId);
    }
    synchronized Destination destination(String id) {
        if(id==null)return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,url,remote_id FROM destinations WHERE id=?",new String[]{id})) {
            return c.moveToFirst()?new Destination(c.getString(0),c.getString(1),c.getString(2),c.getString(3)):null;
        }
    }
    synchronized void discover(String device,PocketFile file,String destinationId) {
        String id=device.toLowerCase()+":"+file.folder+":"+file.name;
        Recording existing=recording(id);
        if(existing!=null){ContentValues v=new ContentValues();v.put("on_pocket",1);getWritableDatabase().update("recordings",v,"id=?",new String[]{id});return;}
        long created=Protocol.timestampMillis(file.name);
        ContentValues v=new ContentValues();v.put("id",id);v.put("device",device);v.put("folder",file.folder);v.put("file",file.name);v.put("state","pending");v.put("destination_id",destinationId);v.put("location",location.near(created));v.put("created_at",created);v.put("created_iso",Instant.ofEpochMilli(created).toString());v.put("duration_seconds",file.durationSeconds);v.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("recordings",null,v);
    }
    synchronized void savePhone(java.io.File file, long started, long seconds, String destination, boolean approved) throws Exception {
        ContentValues row = new ContentValues();
        row.put("id", "phone:" + UUID.randomUUID());
        row.put("device", "phone");
        row.put("folder", "");
        row.put("file", file.getName());
        row.put("state", "downloaded");
        row.put("location", location.near(started));
        row.put("created_at", started);
        row.put("created_iso", Instant.ofEpochMilli(started).toString());
        row.put("duration_seconds", seconds);
        row.put("destination_id", destination);
        row.put("approved", approved ? 1 : 0);
        row.put("on_pocket", 0);
        row.put("local_path", file.getAbsolutePath());
        row.put("size", file.length());
        row.put("sha256", AgentApi.digest(file));
        row.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("recordings", null, row);
    }
    synchronized void bindUnassigned(String destinationId) {
        if(destinationId==null)return;ContentValues v=new ContentValues();v.put("destination_id",destinationId);v.put("approved",1);
        getWritableDatabase().update("recordings",v,"destination_id IS NULL",null);
    }
    synchronized Recording recording(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM recordings WHERE id=?",new String[]{id})) { return c.moveToFirst()?from(c):null; }
    }
    synchronized List<Recording> recordings() {
        ArrayList<Recording> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM recordings ORDER BY created_at DESC",null)){while(c.moveToNext())rows.add(from(c));}return rows;
    }
    private String str(Cursor c,String k){return c.getString(c.getColumnIndexOrThrow(k));}
    private long num(Cursor c,String k){return c.getLong(c.getColumnIndexOrThrow(k));}
    private Recording from(Cursor c){Recording row = new Recording(str(c,"id"),str(c,"device"),str(c,"folder"),str(c,"file"),str(c,"state"),str(c,"local_path"),str(c,"destination_id"),str(c,"error"),num(c,"created_at"),num(c,"duration_seconds"),num(c,"size"),num(c,"delivered_at"),str(c,"created_iso"),str(c,"sha256"),num(c,"approved")==1,num(c,"attempted")==1,num(c,"on_pocket")==1);row.location=str(c,"location");return row;}
    synchronized void state(String id,String state,String path,long size,String error) {
        ContentValues v=new ContentValues();v.put("state",state);v.put("updated_at",System.currentTimeMillis());v.put("error",error);
        if(path!=null)v.put("local_path",path);if(size>0)v.put("size",size);if(state.equals("uploading"))v.put("attempted",1);
        getWritableDatabase().update("recordings",v,"id=?",new String[]{id});
    }
    synchronized void downloaded(String id,String path,long size,String hash) {
        ContentValues v=new ContentValues();v.put("state","downloaded");v.put("local_path",path);v.put("size",size);v.put("sha256",hash);v.putNull("error");v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("recordings",v,"id=?",new String[]{id});
    }
    synchronized void delivered(String id,String receipt,long acceptedAt) {
        ContentValues v=new ContentValues();v.put("state","delivered");v.put("receipt",receipt);v.put("delivered_at",acceptedAt);v.putNull("error");getWritableDatabase().update("recordings",v,"id=?",new String[]{id});
    }
    synchronized void clearLocal(String id){ContentValues v=new ContentValues();v.putNull("local_path");getWritableDatabase().update("recordings",v,"id=? AND state='delivered'",new String[]{id});}
    synchronized void deletedFromPocket(String id){ContentValues v=new ContentValues();v.put("on_pocket",0);getWritableDatabase().update("recordings",v,"id=?",new String[]{id});}
    synchronized void retry(String id){getWritableDatabase().execSQL("UPDATE recordings SET state=CASE WHEN local_path IS NULL THEN 'pending' ELSE 'downloaded' END,error=NULL WHERE id=? AND state='failed'",new Object[]{id});}
    synchronized void approveOriginal(String id){ContentValues v=new ContentValues();v.put("approved",1);getWritableDatabase().update("recordings",v,"id=?",new String[]{id});}
    synchronized boolean reassign(String id,String destination){ContentValues v=new ContentValues();v.put("destination_id",destination);v.put("approved",1);return getWritableDatabase().update("recordings",v,"id=? AND attempted=0 AND state!='delivered'",new String[]{id})==1;}
    synchronized void recoverActive(){getWritableDatabase().execSQL("UPDATE recordings SET state=CASE WHEN local_path IS NULL THEN 'pending' ELSE 'downloaded' END WHERE state IN ('downloading','uploading')");}
    synchronized void retryDue(){getWritableDatabase().execSQL("UPDATE recordings SET state=CASE WHEN local_path IS NULL THEN 'pending' ELSE 'downloaded' END,error=NULL WHERE state='failed' AND updated_at<? AND error IN ('Network unavailable','Transfer interrupted','Recorder disconnected','Recorder response timed out','Recorder transfer stalled','Recorder timed out: folder index','Recorder timed out: file size')",new Object[]{System.currentTimeMillis()-60000});}
    synchronized void event(String category,String message){ContentValues v=new ContentValues();v.put("created_at",System.currentTimeMillis());v.put("category",category);v.put("message",message);getWritableDatabase().insert("events",null,v);getWritableDatabase().execSQL("DELETE FROM events WHERE id NOT IN(SELECT id FROM events ORDER BY id DESC LIMIT 200)");}
    synchronized List<String> events(){List<String> rows=new ArrayList<>();java.text.DateFormat format=new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.US);try(Cursor c=getReadableDatabase().rawQuery("SELECT created_at,category,message FROM events ORDER BY id DESC LIMIT 50",null)){while(c.moveToNext())rows.add(format.format(new java.util.Date(c.getLong(0)))+" · "+c.getString(1)+": "+c.getString(2));}return rows;}
}
