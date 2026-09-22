package com.zeroeclipse;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;

/** Automated end-to-end input smoke test through dialogue, chest unlock, movement and combat. */
public final class CombatQaCapture {
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale","1.0");
        String out=args.length>0?args[0]:"/tmp/zero_eclipse_v7_combat.png";
        EventQueue.invokeAndWait(() -> {
            try { new GameWindow().start(); }
            catch(Exception e){throw new RuntimeException(e);}
        });
        Robot r=new Robot();r.setAutoDelay(35);
        Thread.sleep(800);tap(r,KeyEvent.VK_ENTER);Thread.sleep(450);

        // Reach Elena.
        hold(r,KeyEvent.VK_D,3200);Thread.sleep(120);tap(r,KeyEvent.VK_E);
        for(int i=0;i<10;i++){Thread.sleep(220);tap(r,KeyEvent.VK_E);} // advance typewriter/dialogue
        Thread.sleep(300);

        // Reach chest, unlock Time Fracture and advance story.
        hold(r,KeyEvent.VK_D,1650);Thread.sleep(100);tap(r,KeyEvent.VK_E);
        for(int i=0;i<9;i++){Thread.sleep(220);tap(r,KeyEvent.VK_E);}
        Thread.sleep(450);

        // Reach first combat encounter and execute a queued 3-hit combo.
        hold(r,KeyEvent.VK_D,1350);
        tap(r,KeyEvent.VK_J);Thread.sleep(90);tap(r,KeyEvent.VK_J);Thread.sleep(150);tap(r,KeyEvent.VK_J);
        Thread.sleep(180);

        Dimension d=Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage cap=r.createScreenCapture(new Rectangle(0,0,d.width,d.height));
        ImageIO.write(cap,"png",new File(out));
        System.out.println("COMBAT_QA_CAPTURE_OK "+out);
        System.exit(0);
    }

    private static void tap(Robot r,int key){r.keyPress(key);r.keyRelease(key);}
    private static void hold(Robot r,int key,long ms)throws Exception{r.keyPress(key);Thread.sleep(ms);r.keyRelease(key);}
}
