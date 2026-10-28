import { visualClockRate } from '../visuals/motion';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, AppState, PixelRatio, Text, View, type ViewStyle } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { GLView, type ExpoWebGLRenderingContext } from 'expo-gl';
import { File, Paths } from 'expo-file-system';
import { readSpectrumUniforms } from '../core/spectrum';
import { fragmentShader, qualityRenderScale, vertexShader, visualModes, type VisualMode, type VisualQuality } from '../visuals/shaders';

function ShaderVisual({ mode = 'Black Hole', accent = '#F8D34A', intensity = 1, quality = 'Balanced', idleSpeed = 1, playingSpeed = .75, fps, animate = true, enabled = true, playing = false, coverUrl, coverToken, style }: { mode?: VisualMode; accent?: string; intensity?: number; quality?: VisualQuality; idleSpeed?: number; playingSpeed?: number; fps?: 15|30|45|60; animate?: boolean; enabled?: boolean; playing?: boolean; coverUrl?: string; coverToken?: string; style?: ViewStyle }) {
  const [error, setError] = useState('');
  const [bounds, setBounds] = useState({ width: 1, height: 1 });
  // Bound physical pixels as well as layout points on dense phone screens.
  const pixelBudget = quality === 'Ultra' ? 900 : quality === 'Balanced' ? 640 : 400;
  const renderScale = Math.min(qualityRenderScale[quality], pixelBudget / Math.max(1, bounds.width, bounds.height) / PixelRatio.get());
  const settings = useRef({ mode, accent, intensity, quality, idleSpeed, playingSpeed, fps, animate, enabled, playing, reduced: false, active: true, focused: true });
  const frame = useRef(0); const draw = useRef<(() => void) | null>(null); const cleanup = useRef<(() => void) | null>(null);
  const localCover = useRef<string | null>(null);
  const uploadCover = useRef<((uri: string | null) => void) | null>(null);
  const stop = useCallback(() => { cancelAnimationFrame(frame.current); frame.current = 0; }, []);
  const start = useCallback(() => { stop(); if (settings.current.active && settings.current.focused && settings.current.enabled) draw.current?.(); }, [stop]);
  useEffect(() => { Object.assign(settings.current, { mode, accent, intensity, quality, idleSpeed, playingSpeed, fps, animate, enabled, playing }); start(); }, [mode, accent, intensity, quality, idleSpeed, playingSpeed, fps, animate, enabled, playing, start]);
  useEffect(() => {
    // A backing surface resize clears its pixels. Repaint once after layout has
    // settled, including when playback is paused and no animation loop runs.
    let repaint = 0;
    const first = requestAnimationFrame(() => { repaint = requestAnimationFrame(start); });
    return () => { cancelAnimationFrame(first); cancelAnimationFrame(repaint); };
  }, [bounds.width, bounds.height, renderScale, start]);
  useEffect(() => {
    let cancelled = false;
    localCover.current = null; uploadCover.current?.(null);
    if (!coverUrl) return;
    if (coverUrl.startsWith('file://')) { localCover.current = coverUrl; uploadCover.current?.(coverUrl); start(); return; }
    const target = new File(Paths.cache, `auralis-cover-${Date.now()}-${Math.floor(performance.now())}.image`);
    void File.downloadFileAsync(coverUrl, target, { headers: coverToken ? { Authorization: `Bearer ${coverToken}` } : {}, idempotent: true }).then(file => { if (!cancelled) { localCover.current = file.uri; uploadCover.current?.(file.uri); start(); } else if (file.exists) file.delete(); }).catch(() => { /* Missing original artwork is an explicit neutral fallback. */ });
    return () => { cancelled = true; if (target.exists) target.delete(); };
  }, [coverUrl, coverToken, start]);
  useEffect(() => {
    void AccessibilityInfo.isReduceMotionEnabled().then(value => { settings.current.reduced = value; start(); });
    const reduce = AccessibilityInfo.addEventListener('reduceMotionChanged', value => { settings.current.reduced = value; start(); });
    const state = AppState.addEventListener('change', value => { settings.current.active = value === 'active'; if (value === 'active') start(); else stop(); });
    return () => { reduce.remove(); state.remove(); stop(); cleanup.current?.(); };
  }, [start, stop]);
  useFocusEffect(useCallback(() => { settings.current.focused = true; start(); return () => { settings.current.focused = false; stop(); }; }, [start, stop]));
  const context = (gl: ExpoWebGLRenderingContext) => {
    try {
      const compile = (kind: number, source: string) => { const shader = gl.createShader(kind)!; gl.shaderSource(shader, source); gl.compileShader(shader); if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader) || 'GLSL compilation failed'); return shader; };
      const vertex = compile(gl.VERTEX_SHADER, vertexShader); const fragment = compile(gl.FRAGMENT_SHADER, fragmentShader);
      const program = gl.createProgram()!; gl.attachShader(program, vertex); gl.attachShader(program, fragment); gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('GLSL link failed');
      const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]), gl.STATIC_DRAW);
      const locations = Object.fromEntries(['u_resolution','u_time','u_mode','u_intensity','u_quality','u_accent','u_audio','u_flash','u_bands','u_cover','u_hasCover'].map(name => [name, gl.getUniformLocation(program, name)]));
      const texture = gl.createTexture(); let hasCover = false;
      const setCover = (uri: string | null) => {
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        if (uri) { gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, { localUri: uri } as unknown as TexImageSource); hasCover = true; }
        else { gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([12,14,18,255])); hasCover = false; }
      };
      uploadCover.current = setCover; setCover(localCover.current);
      let clock = 0; let lastDraw = 0; let settlingFrames = 0;
      const render = () => {
        frame.current = 0;
        const config = settings.current;
        if (!config.active || !config.focused || !config.enabled) return;
        const now = Date.now(); const requestedFps = config.fps || (config.quality === 'Eco' ? 15 : config.quality === 'Ultra' ? 60 : 30);
        const targetFps = config.playing ? requestedFps : Math.min(15, requestedFps);
        const animated = !config.reduced && config.animate;
        if (animated && now - lastDraw < 1000 / targetFps) { frame.current = requestAnimationFrame(render); return; }
        if (lastDraw && animated) clock += Math.min(.1, (now-lastDraw)/1000) * visualClockRate(config.playing, config.idleSpeed, config.playingSpeed); lastDraw = now;
        gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight); gl.useProgram(program); gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
        const position = gl.getAttribLocation(program, 'position'); gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
        gl.uniform2f(locations.u_resolution, gl.drawingBufferWidth, gl.drawingBufferHeight);
        gl.uniform1f(locations.u_time, clock); gl.uniform1f(locations.u_mode, visualModes.indexOf(config.mode)); gl.uniform1f(locations.u_intensity, config.intensity);
        gl.uniform1f(locations.u_quality, config.quality === 'Eco' ? 0 : config.quality === 'Ultra' ? 2 : 1);
        gl.uniform1i(locations.u_cover, 0); gl.uniform1f(locations.u_hasCover, hasCover ? 1 : 0);
        const color = config.accent.replace('#',''); gl.uniform3f(locations.u_accent, parseInt(color.slice(0,2),16)/255, parseInt(color.slice(2,4),16)/255, parseInt(color.slice(4,6),16)/255);
        const spectrum = readSpectrumUniforms(now, config.playing);
        gl.uniform1f(locations.u_flash, config.reduced || !config.animate ? 0 : spectrum.flash);
        gl.uniform4f(locations.u_audio, spectrum.levels[0], spectrum.levels[1], spectrum.levels[2], spectrum.levels[3]);
        gl.uniform1fv(locations.u_bands, spectrum.bands);
        gl.drawArrays(gl.TRIANGLES, 0, 6); gl.flush(); gl.endFrameEXP();
        // Native/Web backing buffers can resize after the layout callback. Present
        // a few static frames while layout settles, then stop completely when animations are disabled.
        if (animated || settlingFrames-- > 0) frame.current = requestAnimationFrame(render);
      };
      draw.current = () => { settlingFrames = 4; render(); };
      cleanup.current = () => { uploadCover.current = null; gl.deleteTexture(texture); gl.deleteBuffer(buffer); gl.deleteProgram(program); gl.deleteShader(vertex); gl.deleteShader(fragment); };
      start();
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'GPU unavailable'); }
  };
  if (!enabled || error) return <View style={[{ backgroundColor: '#08090B', alignItems: 'center', justifyContent: 'center' }, style]}><Text style={{ color: '#8E929B', fontSize: 12 }}>{error ? 'GPU недоступен на этом устройстве' : 'Визуализация выключена'}</Text></View>;
  return <View onLayout={event => { const next = event.nativeEvent.layout; setBounds(previous => previous.width === next.width && previous.height === next.height ? previous : { width: next.width, height: next.height }); }} style={[{ overflow: 'hidden', backgroundColor: '#08090B', alignItems: 'center', justifyContent: 'center' }, style]} accessibilityLabel={`GPU-визуализация: ${mode}. Реакция использует спектр воспроизводимого аудио.`}>{bounds.width >= 2 && bounds.height >= 2 && <GLView style={{ width: bounds.width * renderScale, height: bounds.height * renderScale, transform: [{ scale: 1/renderScale }] }} onLayout={() => { requestAnimationFrame(start); }} onContextCreate={context} msaaSamples={0} />}</View>;
}


export type AudioVisualProps = Parameters<typeof ShaderVisual>[0];
export function AudioVisual(props: AudioVisualProps) {
  return <ShaderVisual {...props}/>;
}
