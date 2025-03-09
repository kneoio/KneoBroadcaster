package io.kneo.broadcaster.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayListLogParser {

    public static String parseCompact(String playlistContent) {
        Pattern mediaSequencePattern = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
        Matcher mediaSequenceMatcher = mediaSequencePattern.matcher(playlistContent);
        String mediaSequence = mediaSequenceMatcher.find() ? mediaSequenceMatcher.group(1) : "unknown";
        Pattern extinfoPattern = Pattern.compile("#EXTINF:\\d+,(.+?)\\n.+?/([\\d]+)\\.ts", Pattern.DOTALL);
        List<Song> songs = getSongs(playlistContent, extinfoPattern);

        StringBuilder compact = new StringBuilder();
        compact.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append("\n");

        for (Song song : songs) {
            compact.append("#EXTINF:5,").append(song.name)
                    .append(" (segments/").append(song.startSegment)
                    .append("...").append(song.endSegment).append(".ts)\n");
        }

        return compact.toString();
    }

    private static List<Song> getSongs(String playlistContent, Pattern extinfoPattern) {
        Matcher extinfoMatcher = extinfoPattern.matcher(playlistContent);

        List<Song> songs = new ArrayList<>();
        String currentSong = null;
        int startSegment = -1;
        int lastSegment = -1;

        while (extinfoMatcher.find()) {
            String songName = extinfoMatcher.group(1);
            int segment = Integer.parseInt(extinfoMatcher.group(2));

            if (currentSong == null) {
                currentSong = songName;
                startSegment = segment;
            } else if (!songName.equals(currentSong)) {
                songs.add(new Song(currentSong, startSegment, lastSegment));
                currentSong = songName;
                startSegment = segment;
            }

            lastSegment = segment;
        }

        if (currentSong != null) {
            songs.add(new Song(currentSong, startSegment, lastSegment));
        }
        return songs;
    }

    private record Song(String name, int startSegment, int endSegment) {
    }
}
