package ai.omiro.openpocket;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private final SharedPreferences prefs;
    AppSettings(Context context) { prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE); }
    String device() { return prefs.getString("device",""); }
    void device(String value) { prefs.edit().putString("device",value).apply(); }
    String destinationId() { return prefs.getString("destination",null); }
    void destinationId(String value) { prefs.edit().putString("destination",value).apply(); }
    boolean preferWifi(){return prefs.getBoolean("prefer_wifi",true);}
    void preferWifi(boolean value){prefs.edit().putBoolean("prefer_wifi",value).apply();}
    void storage(String device,long free,long total){prefs.edit().putString("storage_device",device).putLong("storage_free",free).putLong("storage_total",total).putLong("storage_checked",System.currentTimeMillis()).apply();}
    long[] storage(){if(!device().equalsIgnoreCase(prefs.getString("storage_device","")))return null;long total=prefs.getLong("storage_total",0);return total>0?new long[]{prefs.getLong("storage_free",0),total,prefs.getLong("storage_checked",0)}:null;}
    void invalidateStorage(){prefs.edit().remove("storage_total").apply();}
    String publicKey() { return prefs.getString("public_key",""); }
    void publicKey(String value) { prefs.edit().putString("public_key",value).apply(); }
}
