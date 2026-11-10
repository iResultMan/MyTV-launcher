package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KidsJournalTest {
    @Test
    public void newestComesFirstInJson() {
        String log = KidsJournal.append("", 1000, "service_off", "helper off");
        log = KidsJournal.append(log, 2000, "service_on", "");
        assertEquals("[{\"t\":2000,\"kind\":\"service_on\",\"text\":\"\"},{\"t\":1000,\"kind\":\"service_off\",\"text\":\"helper off\"}]",
                KidsJournal.json(log, 10));
        assertEquals("service_on", KidsJournal.lastKind(log));
    }

    @Test
    public void jsonShowsOnlyTheLastN() {
        String log = "";
        for (int i = 1; i <= 5; i++) log = KidsJournal.append(log, i, "k", "e" + i);
        assertEquals("[{\"t\":5,\"kind\":\"k\",\"text\":\"e5\"},{\"t\":4,\"kind\":\"k\",\"text\":\"e4\"}]", KidsJournal.json(log, 2));
    }

    @Test
    public void oldestLinesFallOffPastTheCap() {
        String log = "";
        for (int i = 0; i < KidsJournal.MAX + 20; i++) log = KidsJournal.append(log, i, "k", "e");
        assertEquals(KidsJournal.MAX, log.split("\n").length);
        assertEquals("{\"t\":" + (KidsJournal.MAX + 19), KidsJournal.json(log, 1).substring(1, 6 + String.valueOf(KidsJournal.MAX + 19).length()));
        assertEquals(20, Long.parseLong(log.split("\n")[0].split("\\|")[0]));
    }

    @Test
    public void textCannotBreakTheLineOrTheJson() {
        String log = KidsJournal.append(null, 7, "pin_fail", "a|b\n\"c\"\\");
        String json = KidsJournal.json(log, 5);
        assertEquals(1, log.split("\n").length);
        assertEquals("[{\"t\":7,\"kind\":\"pin_fail\",\"text\":\"a b c\"}]", json);
    }

    @Test
    public void emptyLogGivesEmptyArray() {
        assertEquals("[]", KidsJournal.json("", 5));
        assertEquals("[]", KidsJournal.json(null, 5));
        assertEquals("", KidsJournal.lastKind(""));
    }
}
