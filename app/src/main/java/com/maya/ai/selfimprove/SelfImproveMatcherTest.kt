package com.maya.ai.selfimprove

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfImproveMatcherTest {
    
    @Test 
    fun khudKoBehtar() { 
        assertTrue(SelfImproveMatcher.matches("Maya, khud ko behtar banao.")) 
    }
    
    @Test 
    fun apniCodingCheck() { 
        assertTrue(SelfImproveMatcher.matches("Maya, apni coding check karo.")) 
    }
    
    @Test 
    fun apneCodeProblems() { 
        assertTrue(SelfImproveMatcher.matches("Maya, apne code ki problems dhoondo.")) 
    }
    
    @Test 
    fun nayaFeature() { 
        assertTrue(SelfImproveMatcher.matches("Maya, naya feature develop karo.")) 
    }
    
    @Test 
    fun optimize() { 
        assertTrue(SelfImproveMatcher.matches("Maya, apna code optimize karo.")) 
    }
    
    @Test 
    fun selfImprove() { 
        assertTrue(SelfImproveMatcher.matches("self improve")) 
    }

    @Test 
    fun aboutTheUserIsNotACommand() { 
        assertFalse(SelfImproveMatcher.matches("mujhe khud ko behtar banana hai")) 
    }
    
    @Test 
    fun usersOwnCodeIsNotACommand() { 
        assertFalse(SelfImproveMatcher.matches("mera code check karo")) 
    }
    
    @Test 
    fun greeting() { 
        assertFalse(SelfImproveMatcher.matches("hello")) 
    }
    
    @Test 
    fun appCommand() { 
        assertFalse(SelfImproveMatcher.matches("YouTube kholo")) 
    }
    
    @Test 
    fun weather() { 
        assertFalse(SelfImproveMatcher.matches("aaj ka mausam kaisa hai")) 
    }
    
    @Test 
    fun longChatIsNotACommand() { 
        assertFalse(
            SelfImproveMatcher.matches("aaj office mein bahut kaam tha aur khud ko behtar banane ke liye main soch raha tha ki kya karun")
        ) 
    }
}
