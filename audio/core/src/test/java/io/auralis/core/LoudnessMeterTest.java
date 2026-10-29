package io.auralis.core;
import io.auralis.core.audio.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LoudnessMeterTest{
 @TempDir Path root;
 @Test void streamingKWeightingMatchesFfmpegR128ForDecodedSignal()throws Exception{
  var audio=new FfmpegService();Assumptions.assumeTrue(audio.available());Path source=root.resolve("tone.wav");FfmpegService.run(List.of(audio.executable(),"-v","error","-f","lavfi","-i","sine=frequency=1000:duration=3:sample_rate=48000","-y",source.toString()),Duration.ofSeconds(20));var meter=new LoudnessMeter(48000,1);try(var input=AudioSystem.getAudioInputStream(source.toFile())){byte[] buffer=new byte[8192];int n;float[] frame=new float[1];while((n=input.read(buffer))!=-1)for(int i=0;i<n;i+=2){frame[0]=(short)((buffer[i]&255)|(buffer[i+1]<<8))/32768f;meter.accept(frame);}}
  var expected=new LoudnessService(audio).analyze(source);assertEquals(expected.integratedLufs(),meter.result().integratedLufs(),.15);assertTrue(Math.abs(meter.result().samplePeakDb()-expected.truePeakDb())<.2);
 }
 @Test void silenceIsNeverBoostedAndTruePeakCapsGain(){var meter=new LoudnessMeter(48000,2);for(int i=0;i<48000;i++)meter.accept(new float[]{0,0});assertEquals(-120,meter.result().integratedLufs());assertEquals(0,LoudnessService.gainDb(-120,-120,-16));assertEquals(-.5,LoudnessService.gainDb(-30,-.5,-16),.001);}
}
