package io.auralis.desktop.visualization;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AudioEnvelopeTest {
    @Test void aSingleLowBinRemainsVisibleWithoutRespondingToMidFrequencyTone() {
        AudioEnvelope envelope=new AudioEnvelope();envelope.setPlaying(true);
        float[] input=new float[512];Arrays.fill(input,-60);input[2]=-30;
        AudioEnvelope.Frame measured=null;
        for(int i=0;i<8;i++){envelope.accept(input,i*.05);measured=envelope.step(.05);}
        assertTrue(measured.bass()>.55,"A narrow bass peak must not be diluted into 64 display bands");
        Arrays.fill(input,-60);input[24]=-10;
        for(int i=0;i<45;i++){envelope.accept(input,i*.05);measured=envelope.step(.05);}
        assertTrue(measured.bass()<.001,"A mid-frequency tone must not keep the bass animation active");
    }
    @Test void silenceDoesNotInventAudioAndPauseReleasesToZero() {
        AudioEnvelope envelope = new AudioEnvelope(); envelope.setPlaying(true); float[] silence = new float[128]; Arrays.fill(silence, -60);
        envelope.accept(silence, 0); assertEquals(0, envelope.step(.1).energy());
        float[] tone = silence.clone(); Arrays.fill(tone, 0, 8, -12); envelope.accept(tone, .1);
        var frame = envelope.step(.05); assertTrue(frame.bass() > .5); assertTrue(frame.bass() > frame.treble()); assertTrue(frame.onset() > 0);
        envelope.setPlaying(false); for (int i = 0; i < 80; i++) frame = envelope.step(.05);
        assertTrue(frame.energy() < .00001); assertTrue(frame.onset() < .00001);
    }
    @Test void steadySignalHasNoRepeatedOnsetsAndCallbackArrayIsNotRetained() {
        AudioEnvelope envelope = new AudioEnvelope(); envelope.setPlaying(true); float[] input = new float[64]; Arrays.fill(input, -20);
        envelope.accept(input, 0); var first = envelope.step(.05); assertTrue(first.onset() > .5);
        for (int i = 0; i < 50; i++) { envelope.accept(input, i * .05); envelope.step(.05); }
        var steady = envelope.step(.05); assertTrue(steady.onset() < .001); assertTrue(steady.energy() > .5);
        Arrays.fill(input, Float.NaN); assertTrue(envelope.step(.05).energy() > .4);
        for (int i = 0; i < 100; i++) steady = envelope.step(.05); assertTrue(steady.energy() < .0001);
    }
}
