package com.example.android.uamp;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;

public class Preferences {
    private static final String MUSIC_PREFS = "MUSIC_PREFS";
    private static final String PREF_MUSIC_SOURCE_REMOTE = "PREF_MUSIC_SOURCE_REMOTE";

    public static void toggleMusicSource(@NonNull Context context) {
        SharedPreferences sharedPreferences
                = context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE);
        boolean musicSourceRemote = sharedPreferences.getBoolean(PREF_MUSIC_SOURCE_REMOTE, false);
        musicSourceRemote = !musicSourceRemote;
        sharedPreferences.edit().putBoolean(PREF_MUSIC_SOURCE_REMOTE, musicSourceRemote).apply();
    }

    public static boolean isMusicSourceRemote(@NonNull Context context) {
        return context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_MUSIC_SOURCE_REMOTE, false);
    }

    public static boolean isMusicSourceLocal(@NonNull Context context) {
        return !context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_MUSIC_SOURCE_REMOTE, false);
    }
}
