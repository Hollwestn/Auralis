package io.auralis.desktop;
import io.auralis.desktop.playback.SleepTimer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SleepTimerTest {
    @Test void fadesOnlyAtDeadlineAndCancelRestoresGain(){
        long[] now={1000};var timer=new SleepTimer(()->now[0]);timer.start(1);
        now[0]+=49_000;assertEquals(1,timer.gain());assertFalse(timer.expired());
        now[0]+=6_000;assertEquals(.5,timer.gain(),.001);now[0]+=6_000;assertTrue(timer.expired());assertEquals(0,timer.gain());
        timer.cancel();assertEquals(1,timer.gain());assertFalse(timer.active());assertFalse(timer.expired());
    }
    @Test void replacementStartsFreshAndRejectsInvalidDuration(){long[] now={10};var timer=new SleepTimer(()->now[0]);timer.start(1);now[0]+=59_000;timer.start(30);assertEquals(1_800_000,timer.remainingMillis());assertThrows(IllegalArgumentException.class,()->timer.start(0));}
}
