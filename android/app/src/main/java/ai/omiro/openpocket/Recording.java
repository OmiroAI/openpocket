package ai.omiro.openpocket;

final class Recording {
    final String id, device, folder, file, state, localPath, destinationId, error, createdIso, sha256;
    final long createdAt, durationSeconds, size, deliveredAt;
    String location;
    final boolean approved, attempted, onPocket;
    Recording(String id,String device,String folder,String file,String state,String localPath,
              String destinationId,String error,long createdAt,long durationSeconds,long size,long deliveredAt,
              String createdIso,String sha256,boolean approved,boolean attempted,boolean onPocket) {
        this.id=id; this.device=device; this.folder=folder; this.file=file; this.state=state;
        this.localPath=localPath; this.destinationId=destinationId; this.error=error;
        this.createdAt=createdAt; this.durationSeconds=durationSeconds; this.size=size; this.deliveredAt=deliveredAt;
        this.createdIso=createdIso; this.sha256=sha256; this.approved=approved; this.attempted=attempted; this.onPocket=onPocket;
    }
}
