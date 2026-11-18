package ai.omiro.openpocket;

import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;

final class AgentApi {
    interface Progress { void update(long done,long total); }
    static final class Failure extends IOException { final String safe; Failure(String message){super(message);safe=message;} }
    private volatile HttpURLConnection active;
    void cancel(){HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    static String validUrl(String value) throws Exception {
        URI uri=new URI(value.trim());String host=uri.getHost();
        boolean loopback=BuildConfig.DEBUG && "http".equals(uri.getScheme()) && ("127.0.0.1".equals(host)||"localhost".equals(host));
        if((!"https".equals(uri.getScheme())&&!loopback)||host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Failure("Use a valid HTTPS service URL");
        return value.trim().replaceAll("/+$","");
    }
    JSONObject info(String url,String token)throws Exception {return request(validUrl(url)+"/v1/info",token,null,null,null);}
    String processingStatus(Destination destination, Recording recording, String token) throws Exception {
        JSONObject result=request(validUrl(destination.url)+"/v1/recordings/"+recordingId(recording)+"/progress",token,null,null,null);
        if(result==null||!destination.remoteId.equals(result.optString("destination_id")))return "Processing status unavailable";
        switch(result.optString("stage")){
            case "needs_attention": return "Processing needs attention";
            case "agent_completed": return "Agent processing complete";
            case "agent_processing": return "Agent processing";
            case "transcript_ready": return "Transcript ready";
            case "transcribing": return "Transcribing";
            case "waiting_for_transcription": return "Waiting for transcription";
            case "waiting_for_agent": return "Waiting for agent";
            default: return "Processing status unavailable";
        }
    }
    static String digest(File file)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());
    }
    static String recordingId(Recording r)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(r.id.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();}
    static JSONObject metadata(Recording r)throws Exception {
        return new JSONObject().put("location",r.location).put("media_type",r.device.equals("phone")?"audio/mp4":null).put("version",1).put("recorder_id",r.device.replace(":","").toLowerCase(java.util.Locale.US)).put("created_at",r.createdIso).put("device_timestamp",r.file).put("duration_seconds",r.durationSeconds).put("size_bytes",r.size).put("sha256",r.sha256);
    }
    JSONObject deliver(Destination destination,Recording recording,File file,String token,Progress progress)throws Exception {
        if(token==null||token.isBlank())throw new Failure("Upload credential missing");
        JSONObject info=info(destination.url,token);
        if(!destination.remoteId.equals(info.getString("destination_id")))throw new Failure("Server identity changed; review Settings");
        String url=destination.url+"/v1/recordings/"+recordingId(recording);
        JSONObject expected=metadata(recording);
        JSONObject receipt=request(url,token,null,null,null);
        if(receipt==null){
            if(!file.isFile()||file.length()!=recording.size||!digest(file).equals(recording.sha256))throw new Failure("Local audio integrity mismatch");
            receipt=request(url,token,file,expected,progress);
        }
        if(receipt==null||!"accepted".equals(receipt.optString("status"))||!destination.remoteId.equals(receipt.optString("destination_id"))||!recordingId(recording).equals(receipt.optString("recording_id")))throw new Failure("Invalid delivery receipt");
        JSONObject got=receipt.getJSONObject("metadata");
        for(java.util.Iterator<String> it=expected.keys();it.hasNext();){String k=it.next();if(!expected.get(k).toString().equals(got.optString(k)))throw new Failure("Destination recording conflict");}
        if(receipt.getDouble("accepted_at")<=0)throw new Failure("Invalid delivery timestamp");
        return receipt;
    }
    private JSONObject request(String url,String token,File file,JSONObject meta,Progress progress)throws Exception {
        if(token==null||token.indexOf('\n')>=0||token.indexOf('\r')>=0)throw new Failure("Invalid upload credential");
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();active=connection;
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(file!=null){connection.setRequestMethod("PUT");connection.setDoOutput(true);connection.setRequestProperty("Content-Type",meta.optString("media_type","audio/mpeg"));connection.setRequestProperty("X-Recording-Metadata",Base64.encodeToString(meta.toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));connection.setFixedLengthStreamingMode(file.length());
                try(InputStream in=new FileInputStream(file);OutputStream out=connection.getOutputStream()){byte[] buf=new byte[65536];int n;long count=0;while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();out.write(buf,0,n);count+=n;if(progress!=null)progress.update(count,file.length());}}
            }
            int status=connection.getResponseCode();if(status==404&&file==null&&!url.endsWith("/info"))return null;
            if(status<200||status>=300){if(status==401||status==403)throw new Failure("Upload credential rejected");if(status==409)throw new Failure("Destination recording conflict");if(status==413||status==507)throw new Failure("Server storage limit reached");if(status>=500)throw new Failure("Network unavailable");throw new Failure("Server rejected request ("+status+")");}
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1000000)throw new Failure("Server response too large");out.write(buf,0,n);}return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));}
        }finally{connection.disconnect();if(active==connection)active=null;}
    }
}
