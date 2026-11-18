package ai.omiro.openpocket;

/** Shared, deterministic presentation and bulk-action eligibility. */
final class RecordingPresentation {
    static String duration(long seconds) {
        long s=Math.max(0,seconds), h=s/3600, m=s/60%60;
        return (h>0?h+" hr ":"")+(h>0||m>0?m+" min ":"")+s%60+" sec";
    }
    static String failureLabel(Recording r,String destination){
        if(r.localPath==null)return "Download failed · Pocket → phone";
        return "Network unavailable".equals(r.error)?"Upload waiting · "+destination+" unreachable":"Upload failed · phone → "+destination;
    }
    static String failureHelp(Recording r){
        if(r.localPath==null)return r.error==null?"Reconnect Pocket and retry the download.":r.error;
        return "Network unavailable".equals(r.error)?"Audio is safe on this phone. Connect Tailscale and check your agent is online. Upload retries automatically while the app is open.":"Audio is safe on this phone. "+(r.error==null?"Retry the upload.":r.error);
    }
    static boolean readyToUpload(Recording r) {
        return "downloaded".equals(r.state)&&r.localPath!=null&&r.destinationId!=null&&r.approved;
    }
    static boolean pocketDeletion(Recording r,String device,boolean deliveredOnly) {
        return r.onPocket&&r.device.equalsIgnoreCase(device)&&(!deliveredOnly||"delivered".equals(r.state));
    }
    static boolean downloaded(Recording r) {return r.localPath!=null||"delivered".equals(r.state);}
    static int color(Recording r) {
        if(!r.approved&&!"delivered".equals(r.state))return 0xff805b16;
        switch(r.state){case "failed":return 0xffa63c36;case "delivered":return 0xff28684f;
            case "downloading":case "uploading":return 0xff3e5bc1;case "downloaded":return 0xff356775;default:return 0xff626a77;}
    }
    static int tint(Recording r) {
        if(!r.approved&&!"delivered".equals(r.state))return 0xfffff6e5;
        switch(r.state){case "failed":return 0xffffeeec;case "delivered":return 0xffedf7f0;
            case "downloading":case "uploading":return 0xffedf1ff;case "downloaded":return 0xffedf6f8;default:return 0xfff0f1f4;}
    }
}
