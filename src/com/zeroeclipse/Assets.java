
package com.zeroeclipse;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

final class Assets {
    final Map<String,BufferedImage> img=new HashMap<>();
    final Audio audio=new Audio();

    Assets() throws Exception {
        for(String n:new String[]{
                "alex_sheet","elena_sheet","mira_sheet","noah_sheet","kael_sheet","nova_sheet",
                "alex_portrait","elena_portrait","mira_portrait","noah_portrait","kael_portrait","nova_portrait",
                "tiles","crab_sheet","wisp_sheet",
                "sky1","sky2","sky3","sky4","far1","far2","far3","far4",
                "mid1","mid2","mid3","mid4","front1","front2","front3","front4"
        }) img.put(n,read("/assets/"+n+".png"));

        for(String n:new String[]{
                "music1","music2","music3","music4","boss",
                "pickup","ui","jump","foot","hit","fracture","pulse","portal","complete",
                "attack","attack2","attack3","dodge","enemy_alert","enemy_attack","elena_shot","land",
                "chest","checkpoint","boss_hit",
                "blip_alex","blip_elena","blip_mira","blip_noah","blip_kael","blip_nova"
        }) audio.load(n,"/assets/"+n+".wav");
    }

    BufferedImage get(String n){ return img.get(n); }

    private BufferedImage read(String path) throws IOException {
        try(InputStream in=Assets.class.getResourceAsStream(path)) {
            if(in==null) throw new IOException("Missing "+path);
            return ImageIO.read(in);
        }
    }
}
