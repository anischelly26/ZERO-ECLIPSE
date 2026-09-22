package com.zeroeclipse;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;

/** Automated visual test for boss telegraphs, projectiles, dodge and Zero Pulse. */
public final class BossQaCapture {
    public static void main(String[] args) throws Exception {
        String out=args.length>0?args[0]:"/tmp/zero_eclipse_v7_boss.png";
        final GameWindow[] holder=new GameWindow[1];
        EventQueue.invokeAndWait(() -> {
            try{
                holder[0]=new GameWindow();
                holder[0].start();
                holder[0].qaWarpToLevel(3);
            }catch(Exception e){throw new RuntimeException(e);}
        });
        Robot r=new Robot();r.setAutoDelay(35);
        Thread.sleep(1200);
        r.keyPress(KeyEvent.VK_D);Thread.sleep(1500);r.keyRelease(KeyEvent.VK_D);
        // Advance any boss-intro dialogue quickly.
        for(int i=0;i<8;i++){tap(r,KeyEvent.VK_E);Thread.sleep(180);}
        tap(r,KeyEvent.VK_F);Thread.sleep(180);tap(r,KeyEvent.VK_K);Thread.sleep(380);
        tap(r,KeyEvent.VK_J);Thread.sleep(100);tap(r,KeyEvent.VK_J);Thread.sleep(100);tap(r,KeyEvent.VK_J);
        Thread.sleep(350);
        Dimension d=Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage cap=r.createScreenCapture(new Rectangle(0,0,d.width,d.height));
        ImageIO.write(cap,"png",new File(out));
        System.out.println("BOSS_QA_CAPTURE_OK "+out);
        System.exit(0);
    }
    private static void tap(Robot r,int key){r.keyPress(key);r.keyRelease(key);}
}
