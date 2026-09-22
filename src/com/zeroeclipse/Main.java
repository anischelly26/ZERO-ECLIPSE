
package com.zeroeclipse;
import java.awt.EventQueue;

public final class Main {
    public static void main(String[] args) {
        // Keep Java from applying a soft OS-level fractional UI scale to the pixel canvas.
        System.setProperty("sun.java2d.uiScale","1.0");
        System.setProperty("sun.java2d.opengl","true");
        if (args.length>0 && "--smoke-test".equals(args[0])) {
            System.out.println("ZERO_ECLIPSE_TREASURE_STYLE_AI_V7_SMOKE_OK");
            return;
        }
        if (args.length>0 && "--self-test".equals(args[0])) {
            String result=SelfTest.run();
            System.out.println("ZERO_ECLIPSE_SELF_TEST: "+result);
            if(!result.startsWith("PASS")) System.exit(2);
            return;
        }
        EventQueue.invokeLater(() -> {
            try { new GameWindow().start(); }
            catch (Exception e) { e.printStackTrace(); }
        });
    }
}
