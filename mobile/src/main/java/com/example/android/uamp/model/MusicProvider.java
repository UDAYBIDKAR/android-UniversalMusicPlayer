/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.model;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import com.example.android.uamp.Preferences;
import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_GENRE;
import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_ROOT;
import static com.example.android.uamp.utils.MediaIDHelper.createMediaID;

/**
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * MusicProviderSource defined by a constructor argument of this class.
 */
public class MusicProvider {

    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

    private MusicProviderSource mSource;
    private Context mContext;

    // Categorized caches for music track data:
    private ConcurrentMap<String, List<MediaMetadataCompat>> mMusicListByGenre;
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;
    private ConcurrentMap<String, List<MediaMetadataCompat>> mMusicListByTime;
    private final Set<String> mFavoriteTracks;
    private SongData mSongData;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public MusicProvider(@NonNull Context context) {
        this(new RemoteJSONSource());
        mContext = context;
        mSongData = getSongsData();
    }
    public MusicProvider(MusicProviderSource source) {
        mSource = source;
        mMusicListByGenre = new ConcurrentHashMap<>();
        mMusicListByTime = new ConcurrentHashMap<>();
        mMusicListById = new ConcurrentHashMap<>();
        mFavoriteTracks = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    }

    /**
     * Get an iterator over the list of genres
     *
     * @return genres
     */
    public Iterable<String> getGenres() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        return mMusicListByGenre.keySet();
    }

    /**
     * Get an iterator over a shuffled collection of all songs
     */
    public Iterable<MediaMetadataCompat> getShuffledMusic() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        List<MediaMetadataCompat> shuffled = new ArrayList<>(mMusicListById.size());
        for (MutableMediaMetadata mutableMetadata: mMusicListById.values()) {
            shuffled.add(mutableMetadata.metadata);
        }
        Collections.shuffle(shuffled);
        return shuffled;
    }

    /**
     * Get music tracks of the given genre
     *
     */
    public List<MediaMetadataCompat> getMusicsByGenre(String genre) {
        if (mCurrentState != State.INITIALIZED || !mMusicListByGenre.containsKey(genre)) {
            return Collections.emptyList();
        }
        return mMusicListByGenre.get(genre);
    }

    public List<MediaMetadataCompat> getMusicsByTime(String time, String kind) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        List<MediaMetadataCompat> mediaItems = new ArrayList<>();
        for (SongData.Song song : mSongData.songs) {
            boolean addSong = false;
            if (kind.equals("Fusion*")) {
                addSong = song.isFusion;
            } else if (kind.equals("Filmi*")) {
                addSong = song.isFilmi;
            } else if (song.time != null && song.time.equals(time)) {
                if (kind.equals("*")) {
                    addSong = true;
                } else if (kind.equals("Instrumental*") && (song.isInstrumental || song.isFusion)) {
                    addSong = true;
                } else if (kind.equals("Vocal*") && (!song.isInstrumental && !song.isFusion)) {
                    addSong = true;
                } else if (kind.equals("Jugalbandi*") && song.isJugalbandi) {
                    addSong = true;
                }
                if (song.artists.contains(kind) || kind.equals(song.instrument)) {
                    addSong = true;
                }
            }
            if (addSong) {
                mediaItems.add(mMusicListById.get(song.id).metadata);
            }
        }
        return mediaItems;
    }

    /**
     * Very basic implementation of a search that filter music tracks with title containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicBySongTitle(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_TITLE, query);
    }

    /**
     * Very basic implementation of a search that filter music tracks with album containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicByAlbum(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ALBUM, query);
    }

    /**
     * Very basic implementation of a search that filter music tracks with artist containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicByArtist(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ARTIST, query);
    }

    /**
     * Very basic implementation of a search that filter music tracks with a genre containing
     * the given query.
     *
     */
    public List<MediaMetadataCompat> searchMusicByGenre(String query) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_GENRE, query);
    }

    private List<MediaMetadataCompat> searchMusic(String metadataField, String query) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        ArrayList<MediaMetadataCompat> result = new ArrayList<>();
        query = query.toLowerCase(Locale.US);
        for (MutableMediaMetadata track : mMusicListById.values()) {
            if (track.metadata.getString(metadataField).toLowerCase(Locale.US)
                .contains(query)) {
                result.add(track.metadata);
            }
        }
        return result;
    }


    /**
     * Return the MediaMetadataCompat for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public MediaMetadataCompat getMusic(String musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId).metadata : null;
    }

    public synchronized void updateMusicArt(String musicId, Bitmap albumArt, Bitmap icon) {
        MediaMetadataCompat metadata = getMusic(musicId);
        metadata = new MediaMetadataCompat.Builder(metadata)

                // set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is used, for
                // example, on the lockscreen background when the media session is active.
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)

                // set small version of the album art in the DISPLAY_ICON. This is used on
                // the MediaDescription and thus it should be small to be serialized if
                // necessary
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)

                .build();

        MutableMediaMetadata mutableMetadata = mMusicListById.get(musicId);
        if (mutableMetadata == null) {
            throw new IllegalStateException("Unexpected error: Inconsistent data structures in " +
                    "MusicProvider");
        }

        mutableMetadata.metadata = metadata;
    }

    public void setFavorite(String musicId, boolean favorite) {
        if (favorite) {
            mFavoriteTracks.add(musicId);
        } else {
            mFavoriteTracks.remove(musicId);
        }
    }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    public boolean isFavorite(String musicId) {
        return mFavoriteTracks.contains(musicId);
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final Callback callback) {
        LogHelper.d(TAG, "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            if (callback != null) {
                // Nothing to do, execute callback immediately
                callback.onMusicCatalogReady(true);
            }
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                retrieveMedia();
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }.execute();
    }

    private synchronized void buildListsByGenre() {
        ConcurrentMap<String, List<MediaMetadataCompat>> newMusicListByGenre = new ConcurrentHashMap<>();
        ConcurrentMap<String, List<MediaMetadataCompat>> newMusicListByTime = new ConcurrentHashMap<>();
        if (mSongData.songs == null) {
            return;
        }
        for (SongData.Song song : mSongData.songs) {
            MutableMediaMetadata m = mMusicListById.get(song.id);
            if (song.time == null) {
                continue; //TODO
            } else {
                List<MediaMetadataCompat> list = mMusicListByTime.get(song.time);
                if (list == null) {
                    list = new ArrayList<>();
                    newMusicListByTime.put(song.time, list);
                }
                list.add(m.metadata);
            }
        }
        mMusicListByGenre = newMusicListByGenre;
        mMusicListByTime = newMusicListByTime;
    }

    private SongData getSongsData() {
        InputStream is = mContext.getResources().openRawResource(R.raw.songs);
        Writer writer = new StringWriter();
        char[] buffer = new char[1024];
        try {
            Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            int n;
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
            is.close();
        } catch (Exception e) {
            return null;
        }

        return new Gson().fromJson(writer.toString(), SongData.class);
    }

    private synchronized void retrieveMedia() {
        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                mCurrentState = State.INITIALIZING;

                Iterator<MediaMetadataCompat> tracks = getSongsIterator();
                while (tracks.hasNext()) {
                    MediaMetadataCompat item = tracks.next();
                    String musicId = item.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                    mMusicListById.put(musicId, new MutableMediaMetadata(musicId, item));
                }
                buildListsByGenre();
                mCurrentState = State.INITIALIZED;
            }
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
            }
        }
    }


    public List<MediaBrowserCompat.MediaItem> getChildren(String mediaId, Resources resources) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        if (!MediaIDHelper.isBrowseable(mediaId)) {
            return mediaItems;
        }

        if (MEDIA_ID_ROOT.equals(mediaId)) {
            Calendar c = Calendar.getInstance();
            long now = c.getTimeInMillis();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long passed = now - c.getTimeInMillis();
            long minutesPassed = passed / 1000 / 60;
            if (minutesPassed >= 0 && minutesPassed < 180) {
                mediaId = "24-03";
            } else if (minutesPassed >= 180 && minutesPassed < 360) {
                mediaId = "03-06";
            } else if (minutesPassed >= 360 && minutesPassed < 540) {
                mediaId = "06-09";
            } else if (minutesPassed >= 540 && minutesPassed < 720) {
                mediaId = "09-12";
            } else if (minutesPassed >= 720 && minutesPassed < 900) {
                mediaId = "12-15";
            } else if (minutesPassed >= 900 && minutesPassed < 1080) {
                mediaId = "15-18";
            } else if (minutesPassed >= 1080 && minutesPassed < 1260) {
                mediaId = "18-21";
            } else if (minutesPassed >= 1260 && minutesPassed < 1440) {
                mediaId = "21-24";
            }
            mediaItems = createBrowsableMediaItemForTime(mediaId, resources);
        } else {
            String[] mediaIds = mediaId.split("/");
            if (mediaIds.length > 1) {
                for (MediaMetadataCompat metadata : getMusicsByTime(mediaIds[0], mediaIds[1])) {
                    mediaItems.add(createMediaItem(metadata, mediaId));
                }
            } else {
                LogHelper.w(TAG, "Skipping unmatched mediaId: ", mediaId);
            }
        }
        return mediaItems;
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForRoot(String time, String desc, Resources resources) {
        return new MediaBrowserCompat.MediaItem( new MediaDescriptionCompat.Builder()
                .setMediaId(time)
                .setTitle(time)
                .setSubtitle(desc)
                .setIconUri(Uri.parse("android.resource://" +
                        "com.example.android.uamp/drawable/ic_by_genre"))
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForGenre(String genre,
                                                                    Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_MUSICS_BY_GENRE, genre))
                .setTitle(genre)
                .setSubtitle(resources.getString(
                        R.string.browse_musics_by_genre_subtitle, genre))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private List<MediaBrowserCompat.MediaItem> createBrowsableMediaItemForTime(String time,
                                                                          Resources resources) {
        Map<String, Integer> counts = new HashMap<>();
        int totalInstrumentals = 0;
        int totalVocals = 0;
        int totalFusion = 0;
        int totalFilmi = 0;
        int totalJugalBandi = 0;
        int allSongs = 0;
        for (SongData.Song song : mSongData.songs) {
            if (song.isFusion) {
                totalFusion++;
            }
            if (song.isFilmi) {
                totalFilmi++;
            }
            if (song.time == null || !song.time.equals(time)) {
                continue;
            }
            allSongs++;
            if (song.isJugalbandi) {
                totalJugalBandi++;
            }
            if (song.isFusion || song.isInstrumental) {
                totalInstrumentals++;
            } else {
                totalVocals++;
            }
            for (String instrument : mSongData.instruments) {
                if (instrument.equals(song.instrument)) {
                    if (counts.get(instrument) == null) {
                        counts.put(instrument, 0);
                    }
                    counts.put(instrument, counts.get(instrument) + 1);
                }
            }
            for (String artist : mSongData.artists) {
                if (song.artists != null && song.artists.contains(artist)) {
                    if (counts.get(artist) == null) {
                        counts.put(artist, 0);
                    }
                    counts.put(artist, counts.get(artist) + 1);
                }
            }
        }

        List<MediaBrowserCompat.MediaItem> list = new ArrayList<>();

        list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, time, "*"))
                .setTitle("All")
                .setSubtitle(String.format(Locale.US, "%d songs", allSongs))
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        if (totalInstrumentals > 0) {
            list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId(createMediaID(null, time, "Instrumental*"))
                    .setTitle("All Instrumentals")
                    .setSubtitle(String.format(Locale.US, "%d songs", totalInstrumentals))
                    .build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }
        if (totalVocals > 0) {
            list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId(createMediaID(null, time, "Vocal*"))
                    .setSubtitle(String.format(Locale.US, "%d songs", totalVocals))
                    .setTitle("All Vocal")
                    .build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }

        list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, time, "Fusion*"))
                .setSubtitle(String.format(Locale.US, "%d songs", totalFusion))
                .setTitle("Fusion")
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        if (totalJugalBandi > 0) {
            list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId(createMediaID(null, time, "Jugalbandi*"))
                    .setSubtitle(String.format(Locale.US, "%d songs", totalJugalBandi))
                    .setTitle("Jugalbandi")
                    .build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }

        list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, time, "Filmi*"))
                .setSubtitle(String.format(Locale.US, "%d songs", totalFilmi))
                .setTitle("Filmi")
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        for (String instrument : mSongData.instruments) {
            if (counts.get(instrument) != null) {
                list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                        .setMediaId(createMediaID(null, time, instrument))
                        .setTitle(instrument)
                        .setSubtitle(String.format(Locale.US, "%d songs", counts.get(instrument)))
                        .build(),
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            }
        }
        for (String artist : mSongData.artists) {
            if (counts.get(artist) != null) {
                list.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                        .setMediaId(createMediaID(null, time, artist))
                        .setTitle(artist)
                        .setSubtitle(String.format(Locale.US, "%d songs", counts.get(artist)))
                        .build(),
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            }
        }
        return list;
    }

    private MediaBrowserCompat.MediaItem createMediaItem(MediaMetadataCompat metadata, String id) {
        // Since mediaMetadata fields are immutable, we need to create a copy, so we
        // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
        // when we get a onPlayFromMusicID call, so we can create the proper queue based
        // on where the music was selected from (by artist, by genre, random, etc)
        String hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                metadata.getDescription().getMediaId(), id.split("/"));
        String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String raaga = metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE);

        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(hierarchyAwareMediaID)
                .setTitle(title)
                .setSubtitle(raaga)
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);

    }

    private Iterator<MediaMetadataCompat> getSongsIterator() {
        ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();

        if (mSongData == null || mSongData.songs == null) {
            return tracks.iterator();
        }
        for(SongData.Song song : mSongData.songs) {
            MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
            String source;
            if (Preferences.isMusicSourceRemote(mContext)) {
                source = song.url.replace("?dl=0", "?dl=1");
            } else {
                source = "http://readyshare.routerlogin.net/shares/data/classical/songs/" + song.id + ".mp3";
            }
            builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.id)
                    .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE, source)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                    .putString(MediaMetadataCompat.METADATA_KEY_GENRE, song.raaga == null ? "Hindustani Classical" : song.raaga)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title);
            if (song.artists != null) {
                StringBuilder artistsBuilder = new StringBuilder();
                for(String artist : song.artists) {
                    artistsBuilder.append(artist).append(",");
                }
                String artists = artistsBuilder.toString();
                builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artists.substring(0, artists.length()-1));
                tracks.add(builder.build());
            } else {
                tracks.add(builder.build());
            }
        }
        return tracks.iterator();
    }
}
