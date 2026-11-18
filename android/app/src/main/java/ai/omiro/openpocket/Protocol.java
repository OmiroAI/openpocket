package ai.omiro.openpocket;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

final class Protocol {
    static final String SERVICE = "001120a0-2233-4455-6677-889912345678";
    static final String AUDIO = "001120a1-2233-4455-6677-889912345678";
    static final String CONTROL = "001120a3-2233-4455-6677-889912345678";
    private static final Pattern FOLDER = Pattern.compile("[0-9]{4}-[0-9]{2}(-[0-9]{2})?");
    private static final Pattern FILE = Pattern.compile("[0-9]{14}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    static boolean folder(String value) { return value != null && FOLDER.matcher(value).matches(); }
    static boolean file(String value) { return value != null && FILE.matcher(value).matches(); }
    static PocketFile listed(String response, String requestedFolder) {
        String[] parts = response.split("&");
        if (parts.length < 3 || !"MCU".equals(parts[0]) || !"F".equals(parts[1])) return null;
        String folder, name, seconds;
        if (parts.length >= 5 && folder(parts[2])) { folder=parts[2]; name=parts[3]; seconds=parts[4]; }
        else if (parts.length >= 4) { folder=requestedFolder; name=parts[2]; seconds=parts[3]; }
        else { folder=requestedFolder; name=parts[2]; seconds="0"; }
        if (!folder(folder) || !file(name)) return null;
        try { return new PocketFile(folder, name, Math.max(0, Long.parseLong(seconds))); }
        catch (NumberFormatException exception) { return null; }
    }
    // APP&SPACE reports whole MiB, independent of decimal UI units.
    static final long STORAGE_UNIT_BYTES=1024L*1024;
    static long[] space(String response) {
        String[] p=response.split("&",-1);
        if(p.length!=4||!p[0].equals("MCU")||!p[1].equals("SPA"))return null;
        try{long free=Long.parseLong(p[2]),total=Long.parseLong(p[3]);
            if(free<0||total<=0||free>total||total>Long.MAX_VALUE/STORAGE_UNIT_BYTES)return null;
            return new long[]{free,total};
        }catch(NumberFormatException e){return null;}
    }
    static long size(String response) {
        String[] p=response.split("&");
        if (p.length < 3 || !"MCU".equals(p[0]) || !"U".equals(p[1])) return -1;
        try { return Long.parseLong(p[2]); }
        catch (NumberFormatException exception) { return -1; }
    }
    static long timestampMillis(String file) {
        if (!file(file)) return 0;
        try { return LocalDateTime.parse(file, STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
        catch (RuntimeException exception) { return 0; }
    }
    static String remoteStem(String device, String file) {
        String safeDevice=device.replaceAll("[^0-9A-Fa-f]", "").toLowerCase();
        if (!file(file) || safeDevice.length() < 12) throw new IllegalArgumentException("Invalid recording identity");
        return safeDevice + "_" + file;
    }
}
