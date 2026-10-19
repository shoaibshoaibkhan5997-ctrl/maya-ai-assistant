package com.maya.ai.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCommandMatcherTest {
    @Test fun youtube() = assertEquals(AppTarget.YOUTUBE, AppCommandMatcher.match("MAYA YouTube kholo"))
    @Test fun instagram() = assertEquals(AppTarget.INSTAGRAM, AppCommandMatcher.match("Instagram kholo"))
    @Test fun whatsapp() = assertEquals(AppTarget.WHATSAPP, AppCommandMatcher.match("whatsapp open karo"))
    @Test fun chrome() = assertEquals(AppTarget.CHROME, AppCommandMatcher.match("mujhe Chrome chalu karo"))
    @Test fun settings() = assertEquals(AppTarget.SETTINGS, AppCommandMatcher.match("Settings kholo"))
    @Test fun playStore() = assertEquals(AppTarget.PLAY_STORE, AppCommandMatcher.match("play store kholo"))
    @Test fun googlePhotosIsGallery() = assertEquals(AppTarget.GALLERY, AppCommandMatcher.match("google photos kholo"))
    @Test fun gallery() = assertEquals(AppTarget.GALLERY, AppCommandMatcher.match("gallery kholo"))
    @Test fun google() = assertEquals(AppTarget.GOOGLE, AppCommandMatcher.match("google kholo"))
    @Test fun devanagari() = assertEquals(AppTarget.YOUTUBE, AppCommandMatcher.match("यूट्यूब खोलो"))
    @Test fun devanagariWhatsapp() = assertEquals(AppTarget.WHATSAPP, AppCommandMatcher.match("मया व्हाट्सएप खोलो"))

    @Test fun extraWordsGoToGemini() = assertNull(AppCommandMatcher.match("YouTube kholo sad song search karo"))
    @Test fun noOpenWordIsNotACommand() = assertNull(AppCommandMatcher.match("youtube kya hai"))
    @Test fun twoAppsIsAmbiguous() = assertNull(AppCommandMatcher.match("youtube instagram kholo"))
    @Test fun greetingIgnored() = assertNull(AppCommandMatcher.match("hello"))
}
