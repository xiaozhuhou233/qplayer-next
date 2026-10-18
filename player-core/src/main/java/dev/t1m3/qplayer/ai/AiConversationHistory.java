package dev.t1m3.qplayer.ai;

import com.google.gson.Gson;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** App-private, account-separated transcript snapshots. Never contains provider credentials. */
public final class AiConversationHistory {
    private static final Gson GSON = new Gson();
    private final File directory;

    public AiConversationHistory(File directory) { this.directory = directory; }

    public static final class Archive {
        public int version = 1;
        public List<Session> sessions = new ArrayList<>();
    }
    public static final class Session {
        public String id = "";
        public String title = "";
        public long updatedAt;
        public String draft = "";
        public String attachmentDay = "";
        public Playlist attachment;
        public List<Message> messages = new ArrayList<>();
        public List<String> suggestions = new ArrayList<>();
    }
    public static final class Message {
        public long id;
        public boolean user;
        public String text = "";
        public List<String> sentences = new ArrayList<>();
        public Playlist playlist;
        public boolean interrupted;
        public String error = "";
    }
    public static final class Playlist {
        public String name = "";
        public String summary = "";
        public List<NeteaseSong> songs = new ArrayList<>();
    }

    public static String encode(Archive archive) { return GSON.toJson(archive); }

    public static Archive decode(String json) throws IOException {
        try {
            Archive archive = GSON.fromJson(json, Archive.class);
            if (archive == null || archive.version != 1 || archive.sessions == null) throw new IOException("无法读取对话历史");
            for (Session session : archive.sessions) {
                if (session == null || session.id == null || session.id.isEmpty() || session.messages == null)
                    throw new IOException("对话历史数据不完整");
                if (session.title == null) session.title = "";
                if (session.draft == null) session.draft = "";
                if (session.suggestions == null) session.suggestions = new ArrayList<>();
                for (Message message : session.messages) {
                    if (message == null) throw new IOException("对话历史数据不完整");
                    if (message.text == null) message.text = "";
                    if (message.sentences == null) message.sentences = new ArrayList<>();
                    if (message.error == null) message.error = "";
                    validate(message.playlist);
                }
                validate(session.attachment);
            }
            return archive;
        } catch (RuntimeException error) { throw new IOException("无法读取对话历史", error); }
    }

    private static void validate(Playlist playlist) throws IOException {
        if (playlist == null) return;
        if (playlist.songs == null || playlist.songs.contains(null)) throw new IOException("歌单历史数据不完整");
        if (playlist.name == null) playlist.name = "";
        if (playlist.summary == null) playlist.summary = "";
    }

    public synchronized Archive load(long account) throws IOException {
        File file = new File(directory, account + ".json");
        return file.isFile() ? decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new Archive();
    }

    /** Write beside the old file and replace only after flushing, so a killed process keeps the old snapshot. */
    public synchronized void save(long account, String snapshot) throws IOException {
        decode(snapshot);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建对话历史目录");
        File target = new File(directory, account + ".json");
        File pending = new File(directory, account + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(pending)) {
            out.write(snapshot.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
