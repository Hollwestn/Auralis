package io.auralis.desktop.visualization;

import javafx.application.Platform;
import javafx.scene.image.*;
import javafx.scene.paint.Color;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Shared GLSL rendered off the FX/audio threads. One bounded in-flight frame per pane. */
final class CinematicRenderer implements AutoCloseable {
    private static final ExecutorService GPU=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("auralis-gpu").daemon(true).factory());
    private static Engine engine;
    private static Throwable startupFailure;
    private final AtomicBoolean pending=new AtomicBoolean();
    private Runnable latest;
    private volatile boolean closed;
    private boolean ready;
    private long frames;
    private int presentedMode=-1;
    private double lastMillis;
    private int width,height,fbo,colorTexture,coverTexture;
    private ByteBuffer pixels;
    private int[] argb;
    private Image sourceCover,uploadedCover;
    private WritableImage image;
    record Frame(int width,int height,int mode,int quality,float time,float intensity,Color accent,
                 AudioEnvelope.Frame audio,float offsetX,float offsetY,float zoom,float aspectX,float aspectY,Image cover) {}

    void render(Frame frame,Consumer<Image> present,Consumer<Throwable> failed){
        if(closed)return;
        if(!pending.compareAndSet(false,true)){latest=()->render(frame,present,failed);return;}
        // PixelReader belongs to FX. The immutable cover upload is copied before dispatch.
        int[] cover=null;int cw=0,ch=0;
        if(frame.cover()!=sourceCover||(frame.cover()!=null&&frame.cover()!=uploadedCover&&frame.cover().getPixelReader()!=null)){
            sourceCover=frame.cover();
            if(sourceCover!=null&&sourceCover.getPixelReader()!=null){
                cw=(int)sourceCover.getWidth();ch=(int)sourceCover.getHeight();
                // Artwork decoding already runs in the application's cache; bound the upload.
                if(cw>0&&ch>0&&cw<=4096&&ch<=4096){cover=new int[cw*ch];sourceCover.getPixelReader().getPixels(0,0,cw,ch,PixelFormat.getIntArgbInstance(),cover,0,cw);}
            }
        }
        final int[] upload=cover;final int coverWidth=cw,coverHeight=ch;final boolean coverChanged=frame.cover()!=uploadedCover;
        GPU.execute(()->{
            if(closed){pending.set(false);return;}
            try{
                if(startupFailure!=null)throw new IllegalStateException("OpenGL initialization failed",startupFailure);
                if(engine==null){try{engine=new Engine();}catch(Throwable error){startupFailure=error;throw error;}}
                long started=System.nanoTime();
                allocate(frame.width(),frame.height());
                if(coverChanged){uploadCover(upload,coverWidth,coverHeight);uploadedCover=upload==null?null:frame.cover();}
                glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,width,height);glUseProgram(engine.program);
                uniform2("u_resolution",width,height);uniform2("u_aspect",frame.aspectX(),frame.aspectY());uniform1("u_mode",frame.mode());uniform1("u_quality",frame.quality());
                uniform1("u_time",frame.time());uniform1("u_intensity",frame.intensity());
                Color color=frame.accent();glUniform3f(location("u_accent"),(float)color.getRed(),(float)color.getGreen(),(float)color.getBlue());
                var audio=frame.audio();glUniform4f(location("u_audio"),(float)audio.bass(),(float)audio.mid(),(float)audio.treble(),(float)audio.energy());
                uniform1("u_flash",(float)audio.onset());
                double[] measured=audio.bands();float[] bands=new float[32];for(int i=0;i<32;i++)bands[i]=(float)Math.max(measured[i*2],measured[i*2+1]);
                glUniform1fv(location("u_bands"),bands);
                glUniform3f(location("u_view"),frame.offsetX(),frame.offsetY(),frame.zoom()-1);
                glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,coverTexture);glUniform1i(location("u_cover"),0);uniform1("u_hasCover",uploadedCover!=null?1:0);
                glBindVertexArray(engine.vao);glDrawArrays(GL_TRIANGLES,0,3);
                pixels.clear();glReadPixels(0,0,width,height,GL_BGRA,GL_UNSIGNED_BYTE,pixels);
                IntBuffer ints=pixels.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                for(int y=0;y<height;y++){ints.position((height-1-y)*width);ints.get(argb,y*width,width);}
                int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("OpenGL error "+error);
                double elapsed=(System.nanoTime()-started)/1_000_000.;
                Platform.runLater(()->{
                    try{if(!closed){if(image==null||(int)image.getWidth()!=width||(int)image.getHeight()!=height)image=new WritableImage(width,height);
                        image.getPixelWriter().setPixels(0,0,width,height,PixelFormat.getIntArgbPreInstance(),argb,0,width);
                        ready=true;frames++;presentedMode=frame.mode();lastMillis=elapsed;present.accept(image);}}
                    finally{pending.set(false);Runnable next=latest;latest=null;if(!closed&&next!=null)next.run();}
                });
            }catch(Throwable error){Platform.runLater(()->{pending.set(false);latest=null;if(!closed)failed.accept(error);});}
        });
    }
    boolean ready(){return ready;}
    long frames(){return frames;}
    double lastMillis(){return lastMillis;}
    int pixelCount(){return width*height;}
    Image renderedImage(){return image;}
    int presentedMode(){return presentedMode;}
    void cancelQueued(){latest=null;}
    private int location(String name){return glGetUniformLocation(engine.program,name);}
    private void uniform1(String name,float value){glUniform1f(location(name),value);}
    private void uniform2(String name,float x,float y){glUniform2f(location(name),x,y);}
    private void allocate(int w,int h){
        if(width==w&&height==h)return;
        if(fbo!=0){glDeleteFramebuffers(fbo);glDeleteTextures(colorTexture);}
        width=w;height=h;pixels=BufferUtils.createByteBuffer(w*h*4);argb=new int[w*h];
        fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);colorTexture=glGenTextures();glBindTexture(GL_TEXTURE_2D,colorTexture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colorTexture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("OpenGL framebuffer incomplete");
        if(coverTexture==0){coverTexture=glGenTextures();uploadCover(null,0,0);}
    }
    private void uploadCover(int[] cover,int w,int h){
        glBindTexture(GL_TEXTURE_2D,coverTexture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        ByteBuffer bytes=BufferUtils.createByteBuffer(cover==null?4:w*h*4);
        if(cover==null){bytes.putInt(0xff12100c);}else for(int pixel:cover)bytes.put((byte)(pixel>>16)).put((byte)(pixel>>8)).put((byte)pixel).put((byte)255);
        bytes.flip();glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,cover==null?1:w,cover==null?1:h,0,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
    }
    @Override public void close(){closed=true;latest=null;GPU.execute(()->{if(fbo!=0)glDeleteFramebuffers(fbo);if(colorTexture!=0)glDeleteTextures(colorTexture);if(coverTexture!=0)glDeleteTextures(coverTexture);});}
    private static final class Engine {
        final long window;final int program,vao;
        Engine() throws Exception{
            if(!glfwInit())throw new IllegalStateException("GLFW unavailable");
            glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
            glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);glfwWindowHint(GLFW_FOCUS_ON_SHOW,GLFW_FALSE);
            window=glfwCreateWindow(1,1,"Auralis offscreen graphics",0,0);
            if(window==0)throw new IllegalStateException("OpenGL 3.3 unavailable");
            glfwMakeContextCurrent(window);GL.createCapabilities();
            String source;
            try(var stream=CinematicRenderer.class.getResourceAsStream("/visuals/cinematic.frag")){
                if(stream==null)throw new IllegalStateException("Shared shader missing");source=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            }
            source="#version 330 core\n"+source.replace("precision highp float;", "out vec4 outputColor;").replace("gl_FragColor","outputColor").replace("texture2D(","texture(");
            int fragment=compile(GL_FRAGMENT_SHADER,source),vertex=compile(GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(program));
            glDeleteShader(vertex);glDeleteShader(fragment);vao=glGenVertexArrays();
            System.out.println("AURALIS_GPU_READY "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
        }
        private int compile(int kind,String source){int shader=glCreateShader(kind);glShaderSource(shader,source);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE)throw new IllegalStateException(glGetShaderInfoLog(shader));return shader;}
    }
}
