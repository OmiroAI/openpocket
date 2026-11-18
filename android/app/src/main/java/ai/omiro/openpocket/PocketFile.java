package ai.omiro.openpocket;

final class PocketFile {
    final String folder, name;
    final long durationSeconds;
    PocketFile(String folder, String name, long durationSeconds) {
        this.folder = folder; this.name = name; this.durationSeconds = durationSeconds;
    }
}
