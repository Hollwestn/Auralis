package io.auralis.core.audio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** EBU R128 integrated loudness and oversampled true peak from the actual file. */
public final class LoudnessService {
 public record Measurement(double integratedLufs,double truePeakDb){}
 private final FfmpegService ffmpeg;
 public LoudnessService(FfmpegService ffmpeg){this.ffmpeg=ffmpeg;}
 public Measurement analyze(Path path)throws IOException{
  String output=FfmpegService.run(List.of(ffmpeg.executable(),"-nostdin","-nostats","-v","info","-i",path.toString(),"-map","0:a:0","-vn","-af","loudnorm=I=-16:TP=-1:LRA=11:print_format=json","-f","null","-"),Duration.ofHours(2));
  int end=output.lastIndexOf('}'),start=end<0?-1:output.lastIndexOf('{',end);if(start<0)throw new IOException("Анализ громкости не вернул измерения");
  JsonNode json=new ObjectMapper().readTree(output.substring(start,end+1));
  double lufs=number(json.path("input_i").asText()),peak=number(json.path("input_tp").asText());return new Measurement(lufs,peak);
 }
 private static double number(String text)throws IOException{if(text.equalsIgnoreCase("-inf"))return -120;try{double n=Double.parseDouble(text);if(Double.isFinite(n))return n;}catch(NumberFormatException ignored){}throw new IOException("Некорректное измерение громкости");}
 public static double gainDb(double lufs,double peakDb,double targetLufs){if(!Double.isFinite(lufs)||!Double.isFinite(peakDb)||lufs< -70)return 0;return Math.clamp(Math.min(targetLufs-lufs,-1-peakDb),-24,12);}
}
