package ai.omiro.openpocket;
import static org.junit.Assert.*;
import org.junit.Test;
public class RecordingPresentationTest {
    @Test public void durationRollsOverWithExplicitUnits(){
        assertEquals("0 sec",RecordingPresentation.duration(0));
        assertEquals("59 sec",RecordingPresentation.duration(59));
        assertEquals("1 min 0 sec",RecordingPresentation.duration(60));
        assertEquals("59 min 59 sec",RecordingPresentation.duration(3599));
        assertEquals("1 hr 0 min 0 sec",RecordingPresentation.duration(3600));
        assertEquals("25 hr 2 min 3 sec",RecordingPresentation.duration(90123));
    }
    private Recording record(String state,String path,String dest,boolean approved){return new Recording("id","device","folder","file",state,path,dest,null,0,10,100,0,"date","hash",approved,false,true);}
    @Test public void bulkUploadDoesNotRedirectOrApprovePausedRecordings(){
        assertTrue(RecordingPresentation.readyToUpload(record("downloaded","local","original",true)));
        assertFalse(RecordingPresentation.readyToUpload(record("downloaded","local","original",false)));
        assertFalse(RecordingPresentation.readyToUpload(record("downloaded","local",null,true)));
        assertFalse(RecordingPresentation.readyToUpload(record("failed","local","original",true)));
        assertFalse(RecordingPresentation.readyToUpload(record("delivered","local","original",true)));
    }
    @Test public void downloadAndUploadFailuresAreDistinct(){
        Recording download=record("failed",null,"dest",true),upload=record("failed","local","dest",true);
        assertEquals("Download failed · Pocket → phone",RecordingPresentation.failureLabel(download,"Example Agent"));
        assertEquals("Upload failed · phone → Example Agent",RecordingPresentation.failureLabel(upload,"Example Agent"));
        Recording offline=new Recording("id","device","folder","file","failed","local","dest","Network unavailable",0,10,100,0,"date","hash",true,true,true);
        assertEquals("Upload waiting · Example Agent unreachable",RecordingPresentation.failureLabel(offline,"Example Agent"));
        assertTrue(RecordingPresentation.failureHelp(offline).contains("Audio is safe on this phone"));
        assertTrue(RecordingPresentation.failureHelp(offline).contains("Tailscale"));
    }
    @Test public void deletionSelectionKeepsDeviceAndDeliveryScope(){
        Recording pending=record("pending",null,"dest",true), delivered=record("delivered","local","dest",true);
        assertFalse(RecordingPresentation.pocketDeletion(pending,"other device",false));
        assertFalse(RecordingPresentation.pocketDeletion(pending,"device",true));
        assertTrue(RecordingPresentation.pocketDeletion(delivered,"DEVICE",true));
        assertTrue(RecordingPresentation.pocketDeletion(pending,"device",false));
    }
    @Test public void failedDownloadsDoNotCountAsCompleted(){
        assertFalse(RecordingPresentation.downloaded(record("failed",null,"dest",true)));
        assertFalse(RecordingPresentation.downloaded(record("downloading",null,"dest",true)));
        assertTrue(RecordingPresentation.downloaded(record("delivered",null,"dest",true)));
        assertTrue(RecordingPresentation.downloaded(record("failed","local","dest",true)));
    }
}
