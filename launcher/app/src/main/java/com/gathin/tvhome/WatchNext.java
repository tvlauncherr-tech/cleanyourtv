package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The system's "Continue watching" rows (TvContract.WatchNextPrograms), read for the Home strip (WatchRow).
 * docs/watch-next.md has what real TVs publish. Needs READ_TV_LISTINGS, which a sideloaded app cannot be
 * granted at install: build-and-install grants it, or `adb shell pm grant com.gathin.tvhome
 * android.permission.READ_TV_LISTINGS`. Without it the strip simply stays hidden.
 */
final class WatchNext {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** Cards in the strip. */
    static final int MAX_ITEMS = 10;
    /** Rows linger for months in the provider: older than this is not "continue" any more. */
    static final long MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000;
    /** A row at or past this share of its episode is finished; the app's "next" row for the show takes over. */
    static final int DONE_PERCENT = 95;
    /** WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST: something saved, not something under way. */
    static final int TYPE_WATCHLIST = 3;

    private static final String TAG = "TvHomeWatchNext";
    private static final int MAX_ROWS_READ = 500;

    private WatchNext() { }

    /** One row of the provider, reduced to what the strip shows. */
    static final class Item {
        final String pkg;
        final String title;
        final String season;
        final String episode;
        final String intentUri;
        final long engagedMs;
        /** 0 to 100, or -1 when the app publishes no position or no duration. */
        final int percent;
        final int type;

        Item(String pkg, String title, String season, String episode, String intentUri,
             long engagedMs, int percent, int type) {
            this.pkg = pkg == null ? "" : pkg;
            this.title = title == null ? "" : title;
            this.season = season;
            this.episode = episode;
            this.intentUri = intentUri == null ? "" : intentUri;
            this.engagedMs = engagedMs;
            this.percent = percent;
            this.type = type;
        }

        /** What makes two rows the same show of the same app. */
        String showKey() {
            return pkg + "\n" + title;
        }

        /** Changes whenever something the card shows changes. */
        String signature() {
            return showKey() + "\n" + season + "\n" + episode + "\n" + percent + "\n" + intentUri;
        }
    }

    static boolean granted(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads the table. Never throws: no permission, a provider that is missing or a odd firmware give an empty list. */
    static List<Item> load(Context ctx, long nowMs) {
        if (!granted(ctx)) return Collections.emptyList();
        List<Item> rows = new ArrayList<>();
        // No selection and no projection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException for a selection,
        // so everything is read and filtered here (docs/watch-next.md).
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                null, null, null, null)) {
            if (c == null) return Collections.emptyList();
            int iPkg = c.getColumnIndex("package_name");
            int iTitle = c.getColumnIndex("title");
            int iSeason = c.getColumnIndex("season_display_number");
            int iEpisode = c.getColumnIndex("episode_display_number");
            int iIntent = c.getColumnIndex("intent_uri");
            int iEngaged = c.getColumnIndex("last_engagement_time_utc_millis");
            int iPos = c.getColumnIndex("last_playback_position_millis");
            int iDuration = c.getColumnIndex("duration_millis");
            int iType = c.getColumnIndex("watch_next_type");
            while (c.moveToNext() && rows.size() < MAX_ROWS_READ) {
                String intent = text(c, iIntent);
                String title = text(c, iTitle);
                if (intent == null || intent.isEmpty() || title == null || title.isEmpty()) continue;
                long pos = number(c, iPos, -1);
                long duration = number(c, iDuration, -1);
                rows.add(new Item(text(c, iPkg), title, text(c, iSeason), text(c, iEpisode), intent,
                        number(c, iEngaged, 0), percent(pos, duration), (int) number(c, iType, 0)));
            }
        } catch (RuntimeException e) {   // SecurityException, IllegalArgumentException from a missing provider, ...
            Log.w(TAG, "Continue watching not available: " + e);
            return Collections.emptyList();
        }
        return pick(rows, nowMs);
    }

    /**
     * Which rows the strip shows: unfinished, not stale, newest first, one card per show of an app, at most MAX_ITEMS.
     * Finished rows are dropped before the one-per-show step, so an app's "next episode" row takes the place of the
     * episode that just ended.
     */
    static List<Item> pick(List<Item> rows, long nowMs) {
        List<Item> kept = new ArrayList<>();
        for (Item it : rows) {
            if (it.type == TYPE_WATCHLIST) continue;
            if (it.percent >= DONE_PERCENT) continue;
            if (it.engagedMs > 0 && nowMs - it.engagedMs > MAX_AGE_MS) continue;
            kept.add(it);
        }
        // Stable: rows without a time (0) go last, in the order the provider gave them.
        Collections.sort(kept, (a, b) -> Long.compare(b.engagedMs, a.engagedMs));
        Set<String> seen = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item it : kept) {
            if (!seen.add(it.showKey())) continue;
            out.add(it);
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    static int percent(long position, long duration) {
        if (position < 0 || duration <= 0) return -1;
        long p = position * 100 / duration;
        return (int) Math.max(0, Math.min(100, p));
    }

    /** "T1 · E3", "E3", "T1", or "" when the app gives neither. */
    static String subtitle(Item it) {
        boolean s = present(it.season);
        boolean e = present(it.episode);
        if (s && e) return "T" + it.season.trim() + " · E" + it.episode.trim();
        if (e) return "E" + it.episode.trim();
        if (s) return "T" + it.season.trim();
        return "";
    }

    private static boolean present(String v) {
        if (v == null) return false;
        String t = v.trim();
        return !t.isEmpty() && !"0".equals(t);
    }

    private static String text(Cursor c, int index) {
        if (index < 0 || c.isNull(index)) return null;
        return c.getString(index);
    }

    private static long number(Cursor c, int index, long fallback) {
        if (index < 0 || c.isNull(index)) return fallback;
        try {
            return c.getLong(index);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
