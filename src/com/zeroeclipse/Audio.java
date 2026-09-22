package com.zeroeclipse;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class Audio {
    private final Map<String,byte[]> data=new HashMap<>();
    private volatile Clip music;
    private volatile String currentTrack="";
    private final AtomicInteger musicGeneration=new AtomicInteger();

    void load(String key,String resource){
        try(InputStream in=Audio.class.getResourceAsStream(resource)){
            if(in!=null)data.put(key,in.readAllBytes());
        }catch(Exception ignored){}
    }

    void play(String key){ play(key,0f); }

    void play(String key,float gainDb){
        byte[] b=data.get(key); if(b==null)return;
        try{
            AudioInputStream ais=AudioSystem.getAudioInputStream(new ByteArrayInputStream(b));
            Clip c=AudioSystem.getClip(); c.open(ais);
            setGain(c,gainDb);
            c.start();
            c.addLineListener(e->{ if(e.getType()==LineEvent.Type.STOP)c.close(); });
        }catch(Exception ignored){}
    }

    synchronized void music(String track){
        if(track.equals(currentTrack) && music!=null && music.isOpen())return;
        byte[] b=data.get(track); if(b==null)return;

        try{
            AudioInputStream ais=AudioSystem.getAudioInputStream(new ByteArrayInputStream(b));
            Clip next=AudioSystem.getClip();
            next.open(ais);
            setGain(next,-32f);
            next.loop(Clip.LOOP_CONTINUOUSLY);

            Clip old=music;
            music=next;
            currentTrack=track;
            int generation=musicGeneration.incrementAndGet();

            Thread fade=new Thread(() -> {
                final int steps=14;
                for(int i=0;i<=steps;i++){
                    if(musicGeneration.get()!=generation)break;
                    float t=i/(float)steps;
                    setGain(next,-32f+23f*t);
                    if(old!=null&&old.isOpen())setGain(old,-9f-27f*t);
                    try{Thread.sleep(22);}catch(InterruptedException ignored){break;}
                }
                try{if(old!=null&&old.isOpen())old.close();}catch(Exception ignored){}
                if(next.isOpen())setGain(next,-9f);
            },"zero-eclipse-music-fade");
            fade.setDaemon(true);
            fade.start();
        }catch(Exception ignored){}
    }

    synchronized void stopMusic(){
        musicGeneration.incrementAndGet();
        try{if(music!=null)music.close();}catch(Exception ignored){}
        music=null;currentTrack="";
    }

    private static void setGain(Clip c,float gainDb){
        try{
            if(c!=null&&c.isOpen()&&c.isControlSupported(FloatControl.Type.MASTER_GAIN)){
                FloatControl gain=(FloatControl)c.getControl(FloatControl.Type.MASTER_GAIN);
                float v=Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),gainDb));
                gain.setValue(v);
            }
        }catch(Exception ignored){}
    }

    void stop(){ stopMusic(); }
}
