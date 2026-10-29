import { graphicsBudgetTelemetry } from '../visuals/budget';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, View } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { GLView, type ExpoWebGLRenderingContext } from 'expo-gl';



/** Small vertex buffers, rather than a full-screen fragment loop for every particle pair. */
export function NetworkBackdrop({ accent, animate, quality }: { accent: string; animate: boolean; quality: 'Eco'|'Balanced'|'Ultra' }) {
  const [size, setSize] = useState({ width: 1, height: 1 });
  const settings = useRef({ accent, animate, quality, active: true, focused: true, size });
  const frame = useRef(0); const draw = useRef<(() => void)|null>(null); const dispose = useRef<(() => void)|null>(null);
  const stop = useCallback(() => { cancelAnimationFrame(frame.current); frame.current = 0; }, []);
  const start = useCallback(() => { stop(); if (settings.current.active && settings.current.focused) draw.current?.(); }, [stop]);
  useEffect(() => { Object.assign(settings.current, { accent, animate, quality, size }); start(); }, [accent, animate, quality, size, start]);
  useEffect(() => { let second=0;const first=requestAnimationFrame(()=>{second=requestAnimationFrame(start);});return()=>{cancelAnimationFrame(first);cancelAnimationFrame(second);}; }, [size.width,size.height,start]);
  useEffect(() => { const sub = AppState.addEventListener('change', value => { settings.current.active = value === 'active'; if (settings.current.active) start(); else stop(); }); return () => { sub.remove(); stop(); dispose.current?.(); }; }, [start, stop]);
  useFocusEffect(useCallback(() => { settings.current.focused = true; start(); return () => { settings.current.focused = false; stop(); }; }, [start, stop]));
  const context = (gl: ExpoWebGLRenderingContext) => {
    const compile = (kind: number, text: string) => { const shader = gl.createShader(kind)!; gl.shaderSource(shader, text); gl.compileShader(shader); if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error('Particle shader unavailable'); return shader; };
    try {
      const vertex = compile(gl.VERTEX_SHADER, 'attribute vec3 point; varying float alpha; uniform float size; void main(){gl_Position=vec4(point.xy,0.,1.);gl_PointSize=size;alpha=point.z;}');
      const fragment = compile(gl.FRAGMENT_SHADER, 'precision mediump float; varying float alpha; uniform vec3 color; void main(){gl_FragColor=vec4(color,alpha);}');
      const program = gl.createProgram()!; gl.attachShader(program, vertex); gl.attachShader(program, fragment); gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('Particle program unavailable');
      const buffer = gl.createBuffer(); const position = gl.getAttribLocation(program, 'point');
      const color = gl.getUniformLocation(program, 'color'), dotSize = gl.getUniformLocation(program, 'size');
      let clock = 0, last = 0;
      const render = () => {
        frame.current = 0; const config = settings.current;
        if (!config.active || !config.focused) return;
        const now = Date.now();
        if (config.animate && now-last < 1000/15) { frame.current = requestAnimationFrame(render); return; }
        if (last && config.animate) clock += Math.min(.08, (now-last)/1000); last = now;
        const width = Math.max(1, config.size.width), height = Math.max(1, config.size.height), count = Math.max(12,Math.round((config.quality === 'Eco' ? 22 : config.quality === 'Balanced' ? 36 : 50)*[1,.8,.6,.4][graphicsBudgetTelemetry.level]));
        const points: number[] = [], lines: number[] = [], xy: [number, number][] = [];
        for (let i=0;i<count;i++) {
          const x=(((i*.61803398875)%1)*width+Math.sin(clock*.055+i*1.7)*19+width)%width;
          const y=(((i*.41421356237)%1)*height+Math.cos(clock*.043+i*.93)*22+height)%height;
          xy.push([x,y]);
          points.push(x/width*2-1,1-y/height*2,.32);
        }
        for (let i=0;i<count;i++) { let links=0;for(let j=i+1;j<count&&links<4;j++) {
          const distance=Math.hypot(xy[i][0]-xy[j][0],xy[i][1]-xy[j][1]), reach=115;
          if(distance<reach){const fade=(1-distance/reach)**2*.2;lines.push(xy[i][0]/width*2-1,1-xy[i][1]/height*2,fade,xy[j][0]/width*2-1,1-xy[j][1]/height*2,fade);links++;}
        }}
        gl.viewport(0,0,gl.drawingBufferWidth,gl.drawingBufferHeight);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.useProgram(program);gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);
        const hex=config.accent.replace('#','');gl.uniform3f(color,parseInt(hex.slice(0,2),16)/255,parseInt(hex.slice(2,4),16)/255,parseInt(hex.slice(4,6),16)/255);gl.uniform1f(dotSize,Math.min(4,2.2*gl.drawingBufferWidth/width));
        gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.enableVertexAttribArray(position);gl.vertexAttribPointer(position,3,gl.FLOAT,false,0,0);
        if(lines.length){gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(lines),gl.DYNAMIC_DRAW);gl.drawArrays(gl.LINES,0,lines.length/3);}
        gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(points),gl.DYNAMIC_DRAW);gl.drawArrays(gl.POINTS,0,points.length/3);gl.flush();gl.endFrameEXP();
        if(config.animate) frame.current=requestAnimationFrame(render);
      };
      draw.current=render;dispose.current=()=>{gl.deleteBuffer(buffer);gl.deleteProgram(program);gl.deleteShader(vertex);gl.deleteShader(fragment);};start();
    } catch { stop(); /* Background failure never blocks playback or navigation. */ }
  };
  return <View pointerEvents="none" onLayout={event=>{const next=event.nativeEvent.layout;setSize(old=>old.width===next.width&&old.height===next.height?old:{width:next.width,height:next.height});}} style={{position:'absolute',inset:0,opacity:.65}}>{size.width >= 2 && size.height >= 2 && <GLView style={{flex:1,backgroundColor:'transparent'}} onContextCreate={context} msaaSamples={0}/>}</View>;
}
