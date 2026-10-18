package dev.t1m3.qplayer.ai;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/** Strict, provider-neutral result returned by the AI playlist prompt. */
public final class AiPlaylistResult {
    @SerializedName("playlistName") public String playlistName = "AI 推荐歌单";
    @SerializedName("songs") public List<Song> songs = new ArrayList<>();
    @SerializedName("summary") public String summary = "";
    /** Optional conversational follow-ups returned in the same provider request. */
    @SerializedName("suggestions") public List<String> suggestions = new ArrayList<>();

    public static final class Song {
        @SerializedName("title") public String title = "";
        @SerializedName("artist") public String artist = "";
        @SerializedName("reason") public String reason = "";
    }
}
