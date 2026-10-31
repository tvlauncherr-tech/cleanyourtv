package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class WatchNextTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static WatchNext.Item row(String pkg, String title, long engagedMs, int percent, int type) {
        return new WatchNext.Item(pkg, title, "1", "2", "intent:#Intent;end", engagedMs, percent, type);
    }

    @Test public void newestFirst() {
        List<WatchNext.Item> out = WatchNext.pick(Arrays.asList(
                row("a", "Old", NOW - 5 * DAY, 10, 0),
                row("b", "New", NOW - 1 * DAY, 10, 0)), NOW);
        assertEquals("New", out.get(0).title);
        assertEquals("Old", out.get(1).title);
    }

    @Test public void aFinishedEpisodeGivesWayToTheNextOneOfTheSameShow() {
        List<WatchNext.Item> out = WatchNext.pick(Arrays.asList(
                row("a", "Show", NOW - 1 * DAY, 99, 0),      // just ended
                row("a", "Show", NOW - 2 * DAY, -1, 1)), NOW); // the app's "next episode" row
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).type);
    }

    @Test public void oneCardPerShowOfAnApp() {
        List<WatchNext.Item> out = WatchNext.pick(Arrays.asList(
                row("a", "Show", NOW - 3 * DAY, 20, 0),
                row("a", "Show", NOW - 1 * DAY, 40, 0)), NOW);
        assertEquals(1, out.size());
        assertEquals(40, out.get(0).percent);
    }

    @Test public void theSameTitleInTwoAppsStaysTwoCards() {
        List<WatchNext.Item> out = WatchNext.pick(Arrays.asList(
                row("a", "Film", NOW - 1 * DAY, 20, 0),
                row("b", "Film", NOW - 2 * DAY, 20, 0)), NOW);
        assertEquals(2, out.size());
    }

    @Test public void staleAndWatchlistRowsAreLeftOut() {
        List<WatchNext.Item> out = WatchNext.pick(Arrays.asList(
                row("a", "Stale", NOW - (WatchNext.MAX_AGE_MS + DAY), 10, 0),
                row("a", "Saved", NOW - DAY, -1, WatchNext.TYPE_WATCHLIST),
                row("a", "Fine", NOW - DAY, 10, 0)), NOW);
        assertEquals(1, out.size());
        assertEquals("Fine", out.get(0).title);
    }

    @Test public void rowsWithoutATimeComeLastAndAreKept() {
        List<WatchNext.Item> out = WatchNext.pick(Arrays.asList(
                row("a", "NoTime", 0, -1, 0),
                row("b", "Dated", NOW - DAY, -1, 0)), NOW);
        assertEquals("Dated", out.get(0).title);
        assertEquals("NoTime", out.get(1).title);
    }

    @Test public void atMostTenCards() {
        List<WatchNext.Item> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) rows.add(row("a", "Show " + i, NOW - i * DAY / 24, 10, 0));
        assertEquals(WatchNext.MAX_ITEMS, WatchNext.pick(rows, NOW).size());
    }

    @Test public void percentNeedsAPositionAndADuration() {
        assertEquals(-1, WatchNext.percent(-1, 1000));
        assertEquals(-1, WatchNext.percent(500, 0));
        assertEquals(50, WatchNext.percent(500, 1000));
        assertEquals(100, WatchNext.percent(5000, 1000));
    }

    @Test public void subtitleSaysSeasonAndEpisodeWhenThereAre() {
        assertEquals("T1 · E2", WatchNext.subtitle(row("a", "x", 0, -1, 0)));
        assertEquals("E7", WatchNext.subtitle(new WatchNext.Item("a", "x", null, "7", "u", 0, -1, 0)));
        assertEquals("T3", WatchNext.subtitle(new WatchNext.Item("a", "x", "3", "0", "u", 0, -1, 0)));
        assertEquals("", WatchNext.subtitle(new WatchNext.Item("a", "x", "", null, "u", 0, -1, 0)));
    }
}
