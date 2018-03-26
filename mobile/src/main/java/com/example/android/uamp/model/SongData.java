package com.example.android.uamp.model;

import java.util.List;

public class SongData {
    public List<String> artists;
    public List<String> instrumentalists;
    public List<String> raagas;
    public List<String> instruments;
    public List<String> taals;
    public List<Song> songs;

    public static class Song {
        public List<String> artists;
        public String id;
        public String album;
        public String title;
        public String taal;
        public String time;
        public String raaga;
        public String url;
        public String instrument;
        public long duration;
        public boolean isFusion;
        public boolean isInstrumental;
        public boolean isFilmi;
        public boolean isJugalbandi;
    }
}
