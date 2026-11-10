package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class LanguageListTest {
    @Test
    public void readsTheFileInOrderAndSkipsWhatIsNotALanguage() {
        List<LanguageList.Language> l = LanguageList.parse("{\"languages\":[{\"tag\":\"en\",\"name\":\"English\"},"
                + "{\"tag\":\"pt-BR\",\"name\":\"Portugu\\u00eas\"},{\"tag\":\"EN!\",\"name\":\"bad\"},{\"tag\":\"de\"},"
                + "{\"tag\":\"en\",\"name\":\"again\"},{\"name\":\"no tag\"},7]}");
        assertEquals(2, l.size());
        assertEquals("en", l.get(0).tag);
        assertEquals("English", l.get(0).name);
        assertEquals("pt-BR", l.get(1).tag);
    }

    @Test
    public void badInputGivesNoLanguages() {
        assertTrue(LanguageList.parse(null).isEmpty());
        assertTrue(LanguageList.parse("not json").isEmpty());
        assertTrue(LanguageList.parse("{\"languages\":3}").isEmpty());
        assertTrue(LanguageList.parse("{}").isEmpty());
    }

    @Test
    public void aTagIsInTheBuildWhenItsResourcesAre() {
        String[] have = {"en-US", "ru", "pt-BR", "es-419", "sr-Latn", "in", "iw", "zh-CN"};
        assertTrue(LanguageList.inBuild("ru", have));
        assertTrue(LanguageList.inBuild("pt", have));
        assertTrue(LanguageList.inBuild("pt-BR", have));
        assertFalse(LanguageList.inBuild("pt-PT", have));
        assertTrue(LanguageList.inBuild("es-419", have));
        assertFalse(LanguageList.inBuild("es-MX", have));
        assertTrue(LanguageList.inBuild("sr-Latn", have));
        assertTrue(LanguageList.inBuild("id", have));
        assertTrue(LanguageList.inBuild("he", have));
        assertTrue(LanguageList.inBuild("zh-CN", have));
        assertFalse(LanguageList.inBuild("zh-TW", have));
        assertFalse(LanguageList.inBuild("fr", have));
    }
}
