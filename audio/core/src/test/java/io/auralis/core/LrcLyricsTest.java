package io.auralis.core;

import io.auralis.core.lyrics.LrcLyrics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LrcLyricsTest {
    @Test void handlesOffsetsRepeatedTimestampsSortAndSeek() {
        var lyrics = LrcLyrics.parse("[ti:Север]\n[00:03.500]Вторая строка\n[00:01.2][00:05.20]Припев\n[offset:-200]\n[00:99]Некорректное время");
        assertEquals("Север", lyrics.metadata().get("ti")); assertEquals(3, lyrics.lines().size());
        assertEquals(-1, lyrics.indexAt(.5)); assertEquals("Припев", lyrics.lineAt(1.0).orElseThrow().text());
        assertEquals("Вторая строка", lyrics.lineAt(4).orElseThrow().text()); assertEquals(2, lyrics.indexAt(5));
    }
}
