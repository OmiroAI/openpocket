package ai.omiro.openpocket;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.DateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private final int ink=Color.rgb(32,38,49), muted=Color.rgb(98,106,119), paper=Color.rgb(247,248,250), accent=Color.rgb(62,91,193);
    private Store store;private AppSettings settings;private SecureStore secrets;private SyncEngine engine;
    private LinearLayout root,content;private ScrollView scroll;private TextView liveStatus;private String status="Ready to connect";private boolean settingsScreen;private boolean attentionOnly;
    private boolean deliveredScreen;
    private MediaRecorder phoneMic;
    private File phoneFile;
    private long phoneStarted, phoneElapsed;
    private String phoneDestination;

    private void startPhone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
            return;
        }
        MediaRecorder mic = new MediaRecorder(this);
        try {
            File dir = new File(getFilesDir(), "recordings");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException();
            phoneFile = File.createTempFile("phone-", ".m4a", dir);
            mic.setAudioSource(MediaRecorder.AudioSource.MIC);
            mic.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mic.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mic.setOutputFile(phoneFile);
            mic.prepare();
            mic.start();
            phoneStarted = System.currentTimeMillis();
            phoneElapsed = SystemClock.elapsedRealtime();
            phoneDestination = settings.destinationId();
            phoneMic = mic;
            renderRecordings();
        } catch (Exception error) {
            mic.release();
            if (phoneFile != null) phoneFile.delete();
            phoneFile = null;
            toast("Could not start the phone microphone");
        }
    }
    private void stopPhone() {
        if (phoneMic == null) return;
        try {
            phoneMic.stop();
            store.savePhone(phoneFile, phoneStarted, (SystemClock.elapsedRealtime()-phoneElapsed)/1000, phoneDestination, Objects.equals(phoneDestination, settings.destinationId()));
            engine.syncNow();
        } catch (RuntimeException error) {
            toast("Phone recording could not be finalized");
        } catch (Exception error) {
            toast("Phone audio saved locally but could not be queued");
        } finally {
            phoneMic.release();
            phoneMic = null;
            phoneFile = null;
            renderRecordings();
        }
    }
    private final Handler handler=new Handler();private MediaPlayer player;private ScanCallback scanning;
    private Runnable afterPermission;private ProgressBar transferProgress;private TextView recorderFeedback,agentFeedback,storageUsage;private boolean agentSaving;private Runnable scanTimeout;
    private EditText deviceInput,bindingInput,nameInput,urlInput,tokenInput;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);settings=new AppSettings(this);secrets=new SecureStore(this);engine=new SyncEngine(this,store,settings,secrets,s->runOnUiThread(()->{status=s;if(liveStatus!=null)liveStatus.setText(friendlyStatus(s));if(transferProgress!=null&&s.contains("%")){transferProgress.setVisibility(View.VISIBLE);try{transferProgress.setIndeterminate(false);transferProgress.setProgress(Integer.parseInt(s.substring(s.lastIndexOf("·")+1).replace("%", "").trim()));}catch(Exception ignored){}}if(!settingsScreen&&!s.contains("%")&&(!deliveredScreen||s.equals("Recording delivered")||s.contains("removed")))renderRecordings();else if(settingsScreen)updateStorage();}));getWindow().setStatusBarColor(paper);getWindow().setNavigationBarColor(paper);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);render();}
    @Override protected void onStart(){super.onStart();if(locationEnabled())store.location.start();if(!BuildConfig.DEBUG||!getIntent().getBooleanExtra("bench",false))engine.visible(true);}
    @Override protected void onStop(){super.onStop();store.location.stop();stopPhone();stopScan();stopPlayback();engine.visible(false);}
    @Override protected void onDestroy(){engine.stop();super.onDestroy();}
    private boolean hasPermissions(){return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    private boolean permission(){if(hasPermissions()&&(!settings.preferWifi()||checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED))return true;requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.NEARBY_WIFI_DEVICES},10);return false;}
    private void withPermission(Runnable action){if(permission())action.run();else afterPermission=action;}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] results){super.onRequestPermissionsResult(code,p,results);if(code==12){setLocation(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED);renderSettings();return;}if(code==11){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startPhone();else toast("Microphone permission is needed to record on this phone");return;}Runnable next=afterPermission;afterPermission=null;if(hasPermissions()){if(next!=null)next.run();}else toast("Allow Nearby devices in Android Settings to connect to Pocket");}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));return v;}
    private void title(LinearLayout parent,String value,int size){TextView v=text(value,size,ink);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));parent.addView(v);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(18),dp(14),dp(18),dp(14));l.setBackground(background(Color.WHITE,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(8));content.addView(l,lp);return l;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(accent);b.setStateListAnimator(null);b.setBackgroundTintList(null);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183e5bc1),background(0xffeef1f8,12),null));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(dp(3),dp(6),dp(3),dp(6));b.setLayoutParams(bp);b.setOnClickListener(v->action.run());return b;}
    private void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30ffffff),background(accent,12),null));}
    private void eyebrow(LinearLayout parent,String value){TextView t=text(value,13,muted);parent.addView(t);}
    private View navItem(String label,boolean selected,int tab){
        LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(8),0,dp(8));item.setMinimumHeight(dp(80));
        android.widget.ImageView icon=new android.widget.ImageView(this);icon.setImageResource(tab==2?R.drawable.ic_settings:tab==1?R.drawable.ic_delivered:R.drawable.ic_recordings);icon.setColorFilter(selected?accent:muted);icon.setPadding(dp(20),dp(4),dp(20),dp(4));icon.setBackground(background(selected?0xffe5ebfb:Color.TRANSPARENT,20));item.addView(icon,new LinearLayout.LayoutParams(dp(64),dp(32)));
        TextView name=text(label,12,selected?accent:muted);name.setGravity(Gravity.CENTER);name.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);item.addView(name);item.setContentDescription(label);item.setSelected(selected);item.setFocusable(true);item.setClickable(true);item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183e5bc1),null,background(Color.WHITE,0)));item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.setOnClickListener(v->{if(!selected){settingsScreen=tab==2;deliveredScreen=tab==1;attentionOnly=false;render();}});return item;
    }
    private void render(){root=column();root.setFocusableInTouchMode(true);root.requestFocus();root.setBackgroundColor(paper);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(0,bars.top,0,bars.bottom);return insets;});root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(12),dp(12),dp(8));TextView heading=text(settingsScreen?"Settings":deliveredScreen?"Delivered":"Sync",28,ink);heading.setTypeface(Typeface.create("sans-serif-medium",0));header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));ImageButton more=new ImageButton(this);more.setImageResource(R.drawable.ic_more);more.setColorFilter(ink);more.setContentDescription("More options");more.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183e5bc1),null,background(Color.WHITE,24)));more.setOnClickListener(v->bulkActions(v));header.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(header);
        scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();content.setPadding(dp(20),0,dp(20),dp(16));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View divider=new View(this);divider.setBackgroundColor(0xffe5e8ee);root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.WHITE);nav.addView(navItem("Sync",!settingsScreen&&!deliveredScreen,0),new LinearLayout.LayoutParams(0,-2,1));nav.addView(navItem("Delivered",!settingsScreen&&deliveredScreen,1),new LinearLayout.LayoutParams(0,-2,1));nav.addView(navItem("Settings",settingsScreen,2),new LinearLayout.LayoutParams(0,-2,1));root.addView(nav);
        if(settingsScreen)renderSettings();else renderRecordings();}
    private String friendlyStatus(String value){
        if(settings.device().isBlank())return "Connect your Pocket";
        if(value.equals("Ready to connect"))return "Checking connection…";
        if(value.equals("Pocket connected · queue checked"))return "Pocket connected";
        if(value.equals("Pocket disconnected · local queue checked"))return "Pocket disconnected";
        if(value.startsWith("Joining Pocket Wi-Fi"))return "Joining Pocket Wi-Fi…";
        return value;
    }
    private void openAgentSetup(){settingsScreen=true;render();scroll.post(()->scroll.smoothScrollTo(0,Math.max(0,agentSection.getTop()-dp(8))));}
    private LinearLayout agentSection;
    private void renderRecordings(){if(content==null||settingsScreen)return;int y=scroll.getScrollY();content.removeAllViews();transferProgress=null;
        List<Recording> records=store.recordings();Destination destination=store.destination(settings.destinationId());
        liveStatus=null;
        if(!deliveredScreen){
        LinearLayout connection=card();liveStatus=text(friendlyStatus(status),17,ink);liveStatus.setTypeface(Typeface.create("sans-serif-medium",0));LinearLayout statusRow=new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(liveStatus,new LinearLayout.LayoutParams(0,-2,1));
        connection.addView(statusRow);
        boolean busy=status.contains("%")||status.startsWith("Downloading")||status.startsWith("Delivering")||status.startsWith("Joining")||status.startsWith("Discovering")||status.startsWith("Connecting");
        boolean transferring=status.contains("%")||status.startsWith("Downloading")||status.startsWith("Delivering");
        ProgressBar spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);
        spinner.setVisibility(busy&&!transferring?View.VISIBLE:View.INVISIBLE);
        statusRow.addView(spinner,new LinearLayout.LayoutParams(dp(18),dp(18)));
        transferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        transferProgress.setVisibility(transferring?View.VISIBLE:View.INVISIBLE);
        transferProgress.setIndeterminate(!status.contains("%"));
        connection.addView(transferProgress,new LinearLayout.LayoutParams(-1,dp(4)));
        List<Recording> deviceRecords=new ArrayList<>();for(Recording r:records)if(r.device.equalsIgnoreCase(settings.device()))deviceRecords.add(r);
        if(!deviceRecords.isEmpty()){
            long saved=deviceRecords.stream().filter(RecordingPresentation::downloaded).count();
            connection.addView(text("Downloaded "+saved+" of "+deviceRecords.size()+" recordings",14,ink));
            ProgressBar overall=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);overall.setMax(deviceRecords.size());overall.setProgress((int)saved);overall.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));overall.setContentDescription(saved+" of "+deviceRecords.size()+" recordings downloaded");connection.addView(overall,new LinearLayout.LayoutParams(-1,dp(6)));
            long failed=deviceRecords.stream().filter(r->r.state.equals("failed")&&r.localPath==null).count();
            if(failed>0)connection.addView(text(failed+" downloads need attention",13,0xffa63c36));
        }
        Button microphone = button(phoneMic == null ? "Record on this phone" : "Stop phone recording",
            () -> { if (phoneMic == null) startPhone(); else stopPhone(); });
        connection.addView(microphone);
        showPocketStorage(connection);
        if(settings.device().isBlank()){Button connect=button("Connect Pocket",()->withPermission(this::scan));primary(connect);connection.addView(connect);}
        else {
            Button check=button("Check for recordings",()->withPermission(engine::syncNow));
            check.setEnabled(!busy);
            connection.addView(check);
        }
        if(destination==null){LinearLayout setup=card();LinearLayout next=new LinearLayout(this);next.setGravity(Gravity.CENTER_VERTICAL);TextView heading=text("Agent not connected",16,ink);heading.setTypeface(Typeface.create("sans-serif-medium",0));next.addView(heading,new LinearLayout.LayoutParams(0,-2,1));Button configure=button("Set up",this::openAgentSetup);configure.setContentDescription("Set up agent");next.addView(configure,new LinearLayout.LayoutParams(dp(84),-2));setup.addView(next);setup.addView(text("Audio stays on this phone until you connect your agent.",13,muted));}
        else connection.addView(text("Send to "+destination.name,13,muted));
        }
        records.removeIf(r->deliveredScreen!=r.state.equals("delivered"));
        long attention=records.stream().filter(r->r.state.equals("failed")||!r.approved).count();
        if(records.isEmpty()){
            LinearLayout empty=card();
            title(empty,deliveredScreen?"No deliveries yet":settings.device().isBlank()?"No recorder connected":"Nothing waiting to sync",19);
            empty.addView(text(deliveredScreen?"Recordings appear here when your agent accepts them.":"New recordings appear here when Pocket connects.",14,muted));
        }
        if(attention>0||attentionOnly){Button issues=button(attentionOnly?"Show all recordings":"Review "+attention+" needing attention",()->{attentionOnly=!attentionOnly;scroll.scrollTo(0,0);renderRecordings();});content.addView(issues);if(attentionOnly&&attention==0)content.addView(text("No recordings need attention.",15,muted));}
        String previousDay="";
        for(Recording r:records){if(attentionOnly&&!r.state.equals("failed")&&(r.approved||r.state.equals("delivered")))continue;String day=DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(r.createdAt));if(!day.equals(previousDay)){eyebrow(content,day);previousDay=day;}
            LinearLayout item=card();LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout description=column();title(description,DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(r.createdAt)),17);description.addView(text(RecordingPresentation.duration(r.durationSeconds)+(r.size>0?" · "+formatBytes(r.size):""),13,muted));row.addView(description,new LinearLayout.LayoutParams(0,-2,1));
            if(r.localPath!=null){Button play=button("Play",()->play(r));play.setContentDescription("Play recording from "+day+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(r.createdAt)));row.addView(play,new LinearLayout.LayoutParams(dp(72),-2));}item.addView(row);
            Destination d=store.destination(r.destinationId);String state=r.state.equals("failed")?RecordingPresentation.failureLabel(r,d==null?"agent":d.name):label(r.state);if(r.state.equals("downloaded"))state=d==null?"On phone · agent not set up":"On phone · waiting to send";if(r.state.equals("delivered"))state="Delivered to "+(d==null?"agent":d.name);if(!r.approved&&!r.state.equals("delivered"))state="Paused · destination changed";
            TextView badge=text(state,13,RecordingPresentation.color(r));badge.setTypeface(null,Typeface.BOLD);badge.setPadding(dp(10),dp(7),dp(10),dp(7));badge.setBackground(background(RecordingPresentation.tint(r),8));item.addView(badge);
            if(r.error!=null)item.addView(text(RecordingPresentation.failureHelp(r),13,0xffa63c36));
            if(r.state.equals("failed"))item.addView(button(r.localPath==null?"Retry download":"Retry upload",()->engine.retry(r.id)));
            if(!r.approved&&!r.state.equals("delivered"))item.addView(button("Choose destination",()->details(r.id)));
            if(r.state.equals("delivered")&&r.localPath==null)item.addView(text("Local copy removed · saved with your agent",12,muted));
            item.setOnClickListener(v->details(r.id));item.setFocusable(true);item.setContentDescription("Recording "+day+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(r.createdAt))+". "+state+". Open details");
        }
        scroll.post(()->scroll.scrollTo(0,y));}
    private void bulkActions(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(0,10,0,"Transfer history");
        if(!settingsScreen){
            menu.getMenu().add(0,0,1,"Download all new");
            menu.getMenu().add(0,1,2,"Upload all ready");
            menu.getMenu().add(0,2,3,"Retry failed transfers");
            SubMenu manage=menu.getMenu().addSubMenu(0,20,4,"Manage recordings");
            manage.add(0,3,0,"Delete delivered from Pocket");
            manage.add(0,4,1,"Delete all listed from Pocket");
            manage.add(0,5,2,"Remove delivered phone copies");
        }
        menu.setOnMenuItemClickListener(item->{if(item.hasSubMenu())return false;if(item.getItemId()==10){history();return true;}runBulk(item.getItemId());return true;});menu.show();
    }
    private void runBulk(int which){
            if(which==0){withPermission(engine::syncNow);toast("New recordings will download automatically");return;}
            List<String> ids=new ArrayList<>();int undelivered=0;
            for(Recording r:store.recordings()){
                boolean selected=which==1?RecordingPresentation.readyToUpload(r):which==2?r.state.equals("failed"):which==5?r.state.equals("delivered")&&r.localPath!=null:RecordingPresentation.pocketDeletion(r,settings.device(),which==3);
                if(selected){ids.add(r.id);if(!r.state.equals("delivered"))undelivered++;}
            }
            if(ids.isEmpty()){toast("No eligible recordings");return;}
            String action=which==1?"upload":which==2?"retry":which==5?"clean":"delete";
            String message=which==1?"Send "+ids.size()+" ready recordings to their assigned destinations. Paused recordings stay paused.":which==2?"Retry "+ids.size()+" failed transfers. Existing destinations are kept.":which==5?"Remove "+ids.size()+" delivered playback copies from this phone. Pocket originals and delivery history are kept.":"Permanently delete "+ids.size()+" listed originals from the selected Pocket. This cannot be undone. Newly discovered recordings are not included.\n\n"+undelivered+" have no confirmed delivery."+(undelivered>0?" You may lose recordings that have no other copy.":"");
            if(which<3){engine.bulk(action,ids);toast(ids.size()+" transfers queued; paused destinations stay paused");return;}
            new AlertDialog.Builder(this).setTitle(which>=3&&which<=4?"Delete Pocket originals?":"Confirm bulk action").setMessage(message+"\n\nThe current transfer finishes first. Keep the app open.").setNegativeButton("Cancel",null).setPositiveButton(action.equals("delete")?"Delete "+ids.size()+" originals":"Continue",(d,w)->{engine.bulk(action,ids);toast("Action queued after the current transfer");}).show();
    }
    private void showPocketStorage(LinearLayout parent){
        if(settings.device().isBlank())return;
        long[] space=settings.storage();
        if(space==null){parent.addView(text("Pocket storage · unavailable until checked",12,muted));return;}
        long used=space[1]-space[0];int percent=(int)Math.round(100.0*used/space[1]);
        parent.addView(text(String.format(Locale.US,"Pocket storage · %.1f%% full",100.0*used/space[1]),13,percent>=90?0xffa63c36:muted));
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(percent);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(percent>=90?0xffa63c36:muted));bar.setContentDescription("Pocket storage "+percent+" percent full");parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(3)));
        parent.addView(text(formatBytes(used*Protocol.STORAGE_UNIT_BYTES)+" used of "+formatBytes(space[1]*Protocol.STORAGE_UNIT_BYTES)+" · "+formatBytes(space[0]*Protocol.STORAGE_UNIT_BYTES)+" free",12,muted));
        parent.addView(text("Last checked "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(space[2])),12,muted));
    }
    private String label(String state){return state.substring(0,1).toUpperCase(Locale.US)+state.substring(1);}
    private String formatBytes(long bytes){return bytes>=1000000000?String.format(Locale.US,"%.1f GB",bytes/1000000000.0):bytes>=1000000?String.format(Locale.US,"%.1f MB",bytes/1000000.0):String.format(Locale.US,"%.1f KB",bytes/1000.0);}
    private String retention(Recording r){long days=Math.max(0,(r.deliveredAt+7*86400000L-System.currentTimeMillis()+86399999)/86400000L);return "Local copy · cleanup in "+days+" days";}
    private EditText field(LinearLayout group,String name,String value,boolean secret){TextView label=text(name,13,muted);group.addView(label);EditText e=new EditText(this);e.setId(View.generateViewId());label.setLabelFor(e.getId());e.setSingleLine(true);e.setTextSize(16);e.setTextColor(ink);e.setText(value);if(secret){e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}else e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);group.addView(e,new LinearLayout.LayoutParams(-1,dp(52)));return e;}
    private boolean hasBinding(){try{return secrets.get("binding:"+settings.device())!=null||secrets.get("pocket_binding")!=null;}catch(Exception e){return false;}}
    private void saveRecorder(String address,String key){engine.recorder(address,key,error->runOnUiThread(()->{if(error==null){toast("Pocket saved. Connecting…");if(settingsScreen)renderSettings();}else{if(recorderFeedback!=null)recorderFeedback.setText(error);toast(error);}}));}
    private boolean locationEnabled() {
        return getPreferences(MODE_PRIVATE).getBoolean("capture_location", false)
            && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void setLocation(boolean enabled) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("capture_location",enabled).apply();
        if (enabled) store.location.start();
        else { store.location.stop(); store.location.forget(); }
    }
    private void renderSettings(){String draftName=nameInput==null?null:nameInput.getText().toString(),draftUrl=urlInput==null?null:urlInput.getText().toString(),draftToken=tokenInput==null?null:tokenInput.getText().toString();liveStatus=null;content.removeAllViews();
        LinearLayout recorder=card();title(recorder,"Pocket",19);showPocketStorage(recorder);recorderFeedback=text(settings.device().isBlank()?"No recorder selected":"Recorder saved · reconnects automatically",14,muted);recorder.addView(recorderFeedback);recorder.addView(button(settings.device().isBlank()?"Find Pocket":"Change recorder",()->withPermission(this::scan)));
        LinearLayout advanced=column();advanced.setVisibility(View.GONE);deviceInput=field(advanced,"Recorder address",settings.device(),false);bindingInput=field(advanced,hasBinding()?"Binding key saved · leave blank to keep it":"Binding key","",true);advanced.addView(button("Save recorder",()->withPermission(()->saveRecorder(deviceInput.getText().toString().trim().toUpperCase(Locale.US),bindingInput.getText().toString().trim()))));Button details=button("Device details",()->{advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});details.setBackgroundColor(Color.TRANSPARENT);recorder.addView(details);recorder.addView(advanced);
        Switch fast=new Switch(this);fast.setText("Prefer Wi-Fi downloads");fast.setTextSize(15);fast.setTextColor(ink);fast.setMinHeight(dp(56));fast.setChecked(settings.preferWifi());fast.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,0xff999faa}));fast.setOnCheckedChangeListener((v,on)->{settings.preferWifi(on);if(on)permission();});recorder.addView(fast);recorder.addView(text("Falls back to Bluetooth if Wi-Fi is unavailable. On this phone, Tailscale can block Wi-Fi downloads; uploads still use Tailscale.",13,muted));
        LinearLayout agent=card();agentSection=agent;title(agent,"Agent",19);Destination d=store.destination(settings.destinationId());if(d!=null)agent.addView(text("Sending to "+d.name,15,ink));
        LinearLayout form=column();form.setVisibility(d==null?View.VISIBLE:View.GONE);nameInput=field(form,"Agent name",draftName!=null?draftName:d==null?"":d.name,false);urlInput=field(form,"Service address",draftUrl!=null?draftUrl:d==null?"":d.url,false);urlInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);urlInput.setHint("https://your-agent.example.ts.net");tokenInput=field(form,d==null?"Upload token":"Upload token · blank keeps the saved token",draftToken==null?"":draftToken,true);form.addView(text("Connect Tailscale to reach your agent. We’ll check the connection before saving.",13,muted));Button save=button("Connect agent",()->saveDestination(d));primary(save);form.addView(save);agentFeedback=text("",13,muted);agentFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(agentFeedback);if(d!=null){agent.addView(button("Edit destination",()->form.setVisibility(form.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));form.addView(text("Existing uploads keep their destination until you choose where they should go.",13,muted));}agent.addView(form);
        Switch location = new Switch(this);
        location.setText("Share approximate recording location with agent");
        location.setChecked(locationEnabled());
        location.setOnCheckedChangeListener((view,enabled) -> {
            if (enabled && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},12);
            else setLocation(enabled);
        });
        content.addView(location);
        LinearLayout storage=card();title(storage,"Storage",19);long used=0;for(Recording r:store.recordings())if(r.localPath!=null)used+=new File(r.localPath).length();storageUsage=text(formatBytes(used)+" on this phone",15,ink);storage.addView(storageUsage);storage.addView(text("Local audio is removed 7 days after delivery. Audio that hasn’t been delivered is always kept.",14,muted));storage.addView(button("Free up delivered audio",()->new AlertDialog.Builder(this).setTitle("Remove delivered local audio?").setMessage("This removes playback copies from this phone. Pocket originals and delivery history are kept.").setNegativeButton("Cancel",null).setPositiveButton("Remove local copies",(dialog,which)->{for(Recording r:store.recordings())engine.cleanOne(r);toast("Cleanup queued");}).show()));
        content.addView(text("Open Pocket · Your recorder, without a subscription.",12,muted));}
    private void updateStorage(){if(storageUsage==null)return;long used=0;for(Recording r:store.recordings())if(r.localPath!=null)used+=new File(r.localPath).length();storageUsage.setText(formatBytes(used)+" on this phone");}
    private void saveDestination(Destination old){if(agentSaving)return;String name=nameInput.getText().toString().trim(),url=urlInput.getText().toString().trim(),token=tokenInput.getText().toString().trim();try{if(token.isEmpty()&&old!=null)token=secrets.get("upload:"+old.id);}catch(Exception e){toast("Saved credential unavailable; enter it again");return;}if(token==null)token="";final String credential=token;Runnable save=()->{agentSaving=true;agentFeedback.setText("Checking connection…");engine.destination(name,url,credential,error->runOnUiThread(()->{agentSaving=false;if(error==null){toast("Agent connected");nameInput=null;urlInput=null;tokenInput=null;renderSettings();}else{agentFeedback.setText(error);agentFeedback.setTextColor(0xffa63c36);}}));};if(old!=null&&(!old.url.equals(url)||!old.name.equals(name)))new AlertDialog.Builder(this).setTitle("Change destination?").setMessage("Existing recordings keep their original destination and pause delivery. In Details, choose to finish there or move a recording that has never been uploaded.").setNegativeButton("Cancel",null).setPositiveButton("Check and save",(d,w)->save.run()).show();else save.run();}
    private void details(String id){Recording r=store.recording(id);if(r==null)return;Destination d=store.destination(r.destinationId);List<String> choices=new ArrayList<>();if(r.localPath!=null)choices.add("Play recording");if(r.state.equals("failed"))choices.add("Retry transfer");if(!r.approved&&!r.state.equals("delivered")){choices.add("Finish at original destination");if(!r.attempted&&settings.destinationId()!=null)choices.add("Move to current destination");}if(r.onPocket)choices.add("Delete from Pocket…");if(r.localPath!=null&&r.state.equals("delivered"))choices.add("Remove local copy…");
        String message=DateFormat.getDateTimeInstance().format(new Date(r.createdAt))+"\n"+RecordingPresentation.duration(r.durationSeconds)+" · "+formatBytes(r.size)+"\nDestination: "+(d==null?"Not assigned":d.name)+"\nStatus: "+(r.state.equals("failed")?RecordingPresentation.failureLabel(r,d==null?"agent":d.name)+"\n"+RecordingPresentation.failureHelp(r):label(r.state))+"\nOn Pocket: "+(r.device.equals("phone")?"Recorded on phone":r.onPocket?"Yes":"Deleted")+"\nLocal audio: "+(r.localPath==null?"Unavailable":"Available");
        LinearLayout view=column();view.setPadding(dp(20),0,dp(20),0);view.addView(text(message,15,ink));if(r.location!=null)view.addView(text("Approximate capture location: "+r.location,14,muted));
        if(r.state.equals("delivered")&&d!=null){
            TextView processing=text("Checking agent status…",14,muted);
            view.addView(processing);
            new Thread(()->{
                String value;
                try{value=new AgentApi().processingStatus(d,r,secrets.get("upload:"+d.id));}
                catch(Exception unavailable){value="Agent status unavailable. Delivery confirmed.";}
                final String result=value;
                runOnUiThread(()->processing.setText(result));
            }).start();
        }
        ScrollView detailScroll=new ScrollView(this);detailScroll.addView(view);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Recording details").setView(detailScroll).setNegativeButton("Close",null).create();for(String choice:choices)view.addView(button(choice,()->{dialog.dismiss();switch(choice){case "Play recording":play(r);break;case "Retry transfer":engine.retry(id);break;case "Finish at original destination":engine.approveOriginal(id);break;case "Move to current destination":new AlertDialog.Builder(this).setTitle("Move this pending delivery?").setMessage("The recording will be sent to the current agent destination.").setNegativeButton("Cancel",null).setPositiveButton("Move",(x,y)->engine.reassign(id)).show();break;case "Delete from Pocket…":new AlertDialog.Builder(this).setTitle("Delete recorder original?").setMessage(r.state.equals("delivered")?"Delivery is confirmed. This permanently removes the original from Pocket.":"Delivery is NOT confirmed. Deleting the Pocket original could lose this recording. This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete from Pocket",(x,y)->engine.deleteFromPocket(r)).show();break;case "Remove local copy…":new AlertDialog.Builder(this).setTitle("Remove playback copy?").setMessage("Delivery is confirmed. Pocket's original is not affected.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(x,y)->engine.cleanOne(r)).show();break;}}));dialog.show();}
    private void history(){String value=String.join("\n\n",store.events());TextView v=text(value.isEmpty()?"No transfers yet.":value,14,ink);v.setPadding(dp(20),dp(10),dp(20),dp(10));ScrollView box=new ScrollView(this);box.addView(v);new AlertDialog.Builder(this).setTitle("Transfer history").setView(box).setPositiveButton("Close",null).show();}
    private void play(Recording r){stopPlayback();try{if(r.localPath==null||!new File(r.localPath).isFile()){toast("Local audio is unavailable");return;}engine.playing(r.localPath);player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());player.setDataSource(r.localPath);player.setOnPreparedListener(p->p.start());player.setOnCompletionListener(p->stopPlayback());player.setOnErrorListener((p,a,b)->{stopPlayback();toast("Unable to play this recording");return true;});player.prepareAsync();new AlertDialog.Builder(this).setTitle("Playing recording").setMessage("Audio plays from this phone's private storage.").setPositiveButton("Stop",(d,w)->stopPlayback()).setOnCancelListener(d->stopPlayback()).show();}catch(Exception e){stopPlayback();toast("Unable to play this recording");}}
    private void stopPlayback(){if(player!=null){player.release();player=null;}if(engine!=null)engine.playing(null);}
    private void chooseRecorder(String address){
        try{
            if(secrets.get("binding:"+address)!=null||(address.equals(settings.device())&&hasBinding())){
                saveRecorder(address,"");return;
            }
        }catch(Exception e){toast("Saved credential unavailable");return;}
        new AlertDialog.Builder(this).setTitle("Connect this Pocket")
            .setMessage("For a new, unpaired recorder, create a private binding key on this phone. For a previously paired recorder, use its existing key.")
            .setPositiveButton("Pair new Pocket",(dialog,which)->engine.enroll(address,error->runOnUiThread(()->{
                if(error==null){toast("Pocket paired");settingsScreen=false;deliveredScreen=false;render();}
                else toast("Pairing failed: "+error);
            })))
            .setNeutralButton("Use existing key",(dialog,which)->{
                settingsScreen=true;render();deviceInput.setText(address);
                ((View)deviceInput.getParent()).setVisibility(View.VISIBLE);
            }).setNegativeButton("Cancel",null).show();
    }
    private void scan(){if(!hasPermissions()){withPermission(this::scan);return;}stopScan();BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();if(adapter==null||!adapter.isEnabled()){toast("Enable Bluetooth first");return;}ArrayList<String> labels=new ArrayList<>(),addresses=new ArrayList<>();ArrayAdapter<String> list=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nearby Pocket recorders").setAdapter(list,(d,which)->{String address=addresses.get(which);stopScan();chooseRecorder(address);}).setNegativeButton("Cancel",(d,w)->stopScan()).setOnCancelListener(d->stopScan()).create();scanning=new ScanCallback(){@Override public void onScanResult(int t,ScanResult result){String name=result.getDevice().getName();String address=result.getDevice().getAddress();if(name!=null&&(name.startsWith("PKT")||name.toLowerCase(Locale.US).contains("pocket"))&&!addresses.contains(address)){addresses.add(address);labels.add(name);runOnUiThread(list::notifyDataSetChanged);}}@Override public void onScanFailed(int error){toast("Bluetooth scan failed; retry");}};adapter.getBluetoothLeScanner().startScan(scanning);dialog.show();scanTimeout=()->{stopScan();if(labels.isEmpty()){dialog.dismiss();toast("No Pocket found. Keep it nearby and awake, then retry.");}};handler.postDelayed(scanTimeout,15000);}
    private void stopScan(){if(scanTimeout!=null){handler.removeCallbacks(scanTimeout);scanTimeout=null;}if(scanning!=null){try{BluetoothAdapter a=getSystemService(BluetoothManager.class).getAdapter();if(a!=null&&a.isEnabled())a.getBluetoothLeScanner().stopScan(scanning);}catch(SecurityException ignored){}scanning=null;}}
    private void toast(String message){runOnUiThread(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
}
