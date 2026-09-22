package com.zeroeclipse;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;

/** Automated visual smoke test used during packaging. */
public final class QaCapture {
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale","1.0");
        System.setProperty("sun.java2d.opengl","true");
        String out=args.length>0?args[0]:"/tmp/zero_eclipse_v7_qa.png";

        EventQueue.invokeAndWait(() -> {
            try { new GameWindow().start(); }
            catch(Exception e){ throw new RuntimeException(e); }
        });

        Robot robot=new Robot();
        robot.setAutoDelay(40);
        Thread.sleep(900);
        tap(robot,KeyEvent.VK_ENTER);
        Thread.sleep(800);

        robot.keyPress(KeyEvent.VK_D);
        Thread.sleep(1150);
        tap(robot,KeyEvent.VK_SPACE);
        Thread.sleep(95);
        Dimension d=Toolkit.getDefaultToolkit().getScreenSize();
        String jumpOut=out.replace(".png","_JUMP.png");
        BufferedImage jumpCapture=robot.createScreenCapture(new Rectangle(0,0,d.width,d.height));
        ImageIO.write(jumpCapture,"png",new File(jumpOut));

        Thread.sleep(180);
        robot.keyRelease(KeyEvent.VK_D);
        Thread.sleep(350);

        BufferedImage capture=robot.createScreenCapture(new Rectangle(0,0,d.width,d.height));
        ImageIO.write(capture,"png",new File(out));
        System.out.println("QA_CAPTURE_OK "+out+" + "+jumpOut+" "+d.width+"x"+d.height);
        System.exit(0);
    }

    private static void tap(Robot r,int key){r.keyPress(key);r.keyRelease(key);}
}
