
package com.zeroeclipse;

import com.zeroeclipse.v9ai.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

final class GameWindow extends Canvas implements Runnable, KeyListener {
    static final int IW=1024, IH=512, TILE=32, ROWS=16, COLS=80;
    static final int WORLD_W=COLS*TILE;
    static final int PW=42, PH=50;
    static final int SPRITE_FW=96, SPRITE_FH=84, SPRITE_DRAW_W=96, SPRITE_DRAW_H=84;

    enum State { MENU, PLAY, DIALOGUE, CHAT, PAUSE, END }
    enum PickType { CHEST, MAP, COIN, CRYSTAL }
    enum EnemyState { PATROL, CHASE, TELEGRAPH, RECOVER, STUNNED }
    enum EnemyType { CRAB, WISP }

    private final Frame frame=new Frame("ZERO: ECLIPSE — Treasure Hunter Edition v7");
    private final GraphicsDevice graphicsDevice=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    private Rectangle windowedBounds;
    private boolean fullscreen=false;
    private final Assets a;
    private final AdaptiveAI ai=new AdaptiveAI();
    private AdaptiveAI.Style lastAIStyle=ai.style();

    private Thread loop;
    private volatile boolean running=true;
    private final boolean[] keys=new boolean[512];
    private final BufferedImage frameBuffer=new BufferedImage(IW,IH,BufferedImage.TYPE_INT_ARGB);

    private State state=State.MENU;
    private int level=0;
    private int[][] map=new int[ROWS][COLS];

    private final Player p=new Player();
    private final Companion elena=new Companion();
    private NPC mira=new NPC("MIRA");
    private NPC noah=new NPC("NOAH");
    private final Boss kael=new Boss();

    private final List<Enemy> enemies=new ArrayList<>();
    private final List<Projectile> projectiles=new ArrayList<>();
    private final List<Particle> particles=new ArrayList<>();
    private final List<FloatingText> floatingTexts=new ArrayList<>();
    private final List<Pickup> pickups=new ArrayList<>();
    private final List<TemporalGate> gates=new ArrayList<>();

    private double total=0,camX=0,camY=0,shake=0,footTimer=0,roomStart=0,hitStop=0;
    private int fps=0,renderFrames=0;
    private long fpsStamp=System.nanoTime();
    private double respawnX=64;
    private String directorMode="OBSERVING";
    private double directorTimer=0;
    private boolean assistPlatform=false;
    private double assistPlatformX=0,assistPlatformY=0,assistPlatformTimer=0;
    private int fallsThisLevel=0;
    private boolean checkpointReached=false;

    private int objectiveStep=0,mapFragments=0,coins=0,treasures=0;
    private boolean timeUnlocked=false,pulseUnlocked=false,bossStarted=false;

    private String chapter="TREASURE HUNTER EDITION";
    private String objective="PRESS ENTER TO START";
    private String hint="",banner="";
    private double hintTimer=0,bannerTimer=0;

    // dialogue / typewriter
    private String speaker="",dialogue="",portraitKey="",blipKey="";
    private double dialogueTimer=0,revealAccumulator=0,blipCooldown=0;
    private int revealChars=0;
    private Runnable dialogueAfter;

    // NOVA chat
    private boolean aiPanel=false;
    private final StringBuilder chatInput=new StringBuilder();
    private String chatReply="NOVA is online. Ask me about the route, your team, powers, or how I learn.";

    private boolean pressE,pressQ,pressF,pressAttack,pressDodge,pressSpace,pressEnter,pressTab;
    private boolean suppressNextTyped=false;

    GameWindow() throws Exception {
        System.setProperty("sun.java2d.opengl","true");
        a=new Assets();

        setPreferredSize(new Dimension(IW,IH));
        setMinimumSize(new Dimension(IW,IH));
        setFocusable(true);
        setIgnoreRepaint(true);
        addKeyListener(this);
        addFocusListener(new FocusAdapter(){
            @Override public void focusLost(FocusEvent e){
                Arrays.fill(keys,false);
                if(state==State.PLAY)state=State.PAUSE;
            }
        });

        frame.setLayout(new BorderLayout());
        frame.add(this,BorderLayout.CENTER);
        frame.pack();
        frame.setResizable(true);
        frame.setSize(1280,640);
        frame.setLocationRelativeTo(null);
        windowedBounds=frame.getBounds();
        frame.addWindowListener(new WindowAdapter(){
            @Override public void windowClosing(WindowEvent e){ ai.save(); stop(); }
        });

        buildLevel(0);
    }

    void start(){
        frame.setVisible(true);
        requestFocus();
        loop=new Thread(this,"zero-eclipse-v7-loop");
        loop.start();
    }

    private void toggleFullscreen(){
        if(!fullscreen){
            windowedBounds=frame.getBounds();
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            fullscreen=true;
        }else{
            frame.setExtendedState(Frame.NORMAL);
            if(windowedBounds!=null)frame.setBounds(windowedBounds);
            fullscreen=false;
        }
        requestFocus();
    }

    void qaWarpToLevel(int targetLevel){
        targetLevel=Math.max(0,Math.min(3,targetLevel));
        timeUnlocked=targetLevel>=1;
        pulseUnlocked=targetLevel>=3;
        buildLevel(targetLevel);
        state=State.PLAY;
        if(targetLevel==3){
            p.x=1785;p.y=360;
            camX=clamp(p.x-IW*.36,0,WORLD_W-IW);
        }
    }

    private void stop(){
        running=false;
        a.audio.stop();
        frame.dispose();
    }

    @Override public void run(){
        createBufferStrategy(3);
        BufferStrategy bs=getBufferStrategy();
        long prev=System.nanoTime();
        double acc=0, fixed=1.0/60.0;
        final long targetRenderNanos=1_000_000_000L/120L;

        while(running){
            long frameStart=System.nanoTime();
            long now=frameStart;
            double dt=Math.min(.05,(now-prev)/1e9);
            prev=now;acc+=dt;

            while(acc>=fixed){
                update(fixed);
                acc-=fixed;
            }

            do{
                do{
                    Graphics2D out=(Graphics2D)bs.getDrawGraphics();
                    try{ renderScaled(out); }
                    finally{ out.dispose(); }
                }while(bs.contentsRestored());

                bs.show();
                Toolkit.getDefaultToolkit().sync();
            }while(bs.contentsLost());

            renderFrames++;
            long fpsNow=System.nanoTime();
            if(fpsNow-fpsStamp>=1_000_000_000L){
                fps=renderFrames;renderFrames=0;fpsStamp=fpsNow;
            }

            long elapsed=System.nanoTime()-frameStart;
            long remaining=targetRenderNanos-elapsed;
            if(remaining>0){
                try{
                    long ms=remaining/1_000_000L;
                    int ns=(int)(remaining%1_000_000L);
                    Thread.sleep(ms,ns);
                }catch(InterruptedException ignored){}
            }
        }
    }

    private void renderScaled(Graphics2D out){
        int w=getWidth(),h=getHeight();
        double s=Math.min(w/(double)IW,h/(double)IH);
        int rw=Math.max(IW,(int)Math.floor(IW*s));
        int rh=Math.max(IH,(int)Math.floor(IH*s));
        double corrected=Math.min(rw/(double)IW,rh/(double)IH);
        rw=(int)Math.floor(IW*corrected);
        rh=(int)Math.floor(IH*corrected);
        int ox=(w-rw)/2,oy=(h-rh)/2;

        Graphics2D g=frameBuffer.createGraphics();
        try{
            g.setComposite(AlphaComposite.Src);
            g.setColor(Color.BLACK);g.fillRect(0,0,IW,IH);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            render(g);
        }finally{
            g.dispose();
        }

        out.setColor(Color.BLACK);out.fillRect(0,0,w,h);
        out.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        out.drawImage(frameBuffer,ox,oy,rw,rh,null);
    }

    private void newGame(){
        ai.startSession();
        lastAIStyle=ai.style();
        level=0;objectiveStep=0;mapFragments=0;coins=0;treasures=0;
        timeUnlocked=false;pulseUnlocked=false;bossStarted=false;
        buildLevel(0);
        state=State.PLAY;
        showBanner("LEVEL 1 // LOST RUINS",1.7);
        showHint("NOVA ONLINE // "+ai.style()+" PLAYER PROFILE",2.4);
    }

    private void buildLevel(int n){
        level=n;
        map=new int[ROWS][COLS];
        enemies.clear();projectiles.clear();particles.clear();floatingTexts.clear();pickups.clear();gates.clear();
        checkpointReached=false;respawnX=64;
        bossStarted=false;kael.active=false;kael.hp=kael.maxHp=100;
        fallsThisLevel=0;
        assistPlatform=false;assistPlatformTimer=0;
        directorMode="SCANNING "+(n+1)+"/4";directorTimer=2.0;

        // two rows of default ground, then carve gaps
        int baseTile=switch(n){case 0->1;case 1->6;case 2->3;default->5;};
        for(int c=0;c<COLS;c++){map[14][c]=baseTile;map[15][c]=baseTile;}

        if(n==0){
            // Lost Ruins: gentle tutorial platforming
            platform(11,5,10,1);
            platform(10,16,21,6);
            platform(8,29,35,2);
            platform(11,40,46,1);
            platform(9,55,60,6);
            spikes(13,24,26);spikes(13,48,50);
            gap(14,63,65);gap(15,63,65);

            elena.visible=true;elena.following=false;elena.x=650;elena.y=398;elena.dir=-1;
            pickups.add(new Pickup(930,390,PickType.CHEST));
            pickups.add(new Pickup(1240,300,PickType.COIN));
            pickups.add(new Pickup(1590,390,PickType.COIN));
            pickups.add(new Pickup(1990,335,PickType.CRYSTAL));
            enemies.add(new Enemy(1160,416,2));
            enemies.add(new Enemy(1480,416,2));
            enemies.add(new Enemy(1850,416,2));

            chapter="LEVEL 1 / 4 // LOST RUINS";
            objective="Find Elena near the sealed treasure chest";
            objectiveStep=0;
            a.audio.music("music1");
        }else if(n==1){
            // Waterfall Archive
            platform(11,4,10,6);platform(9,16,23,2);platform(7,30,37,6);
            platform(10,44,51,2);platform(8,58,64,6);platform(11,69,74,2);
            spikes(13,12,14);spikes(13,39,42);spikes(13,66,68);
            gap(14,26,28);gap(15,26,28);gap(14,53,55);gap(15,53,55);

            mira=new NPC("MIRA");mira.visible=true;mira.x=230;mira.y=398;
            pickups.add(new Pickup(630,350,PickType.MAP));
            pickups.add(new Pickup(1110,222,PickType.MAP));
            pickups.add(new Pickup(1610,350,PickType.MAP));
            pickups.add(new Pickup(890,390,PickType.COIN));
            pickups.add(new Pickup(1880,300,PickType.CRYSTAL));
            enemies.add(new Enemy(820,416,2));
            enemies.add(new Enemy(1360,310,2,EnemyType.WISP));
            enemies.add(new Enemy(2020,300,3,EnemyType.WISP));

            chapter="LEVEL 2 / 4 // WATERFALL ARCHIVE";
            objective="Talk to Mira";
            objectiveStep=0;
            a.audio.music("music2");
        }else if(n==2){
            // Fractured Canyon: full bridge with temporal gates and a few raised bridge sections
            for(int c=0;c<COLS;c++){map[13][c]=3;map[14][c]=0;map[15][c]=0;}
            platform(12,0,8,1);platform(12,70,79,1);
            platform(11,25,31,3);platform(10,48,54,3);
            noah=new NPC("NOAH");noah.visible=true;noah.x=245;noah.y=366;
            gates.add(new TemporalGate(700,.2));
            gates.add(new TemporalGate(1120,1.7));
            gates.add(new TemporalGate(1570,3.0));
            gates.add(new TemporalGate(1940,4.3));
            pickups.add(new Pickup(980,348,PickType.COIN));
            pickups.add(new Pickup(1770,316,PickType.CRYSTAL));

            chapter="LEVEL 3 / 4 // FRACTURED CANYON";
            objective="Talk to Noah";
            objectiveStep=0;
            a.audio.music("music3");
        }else{
            // Eclipse Temple
            platform(11,3,9,5);platform(9,16,23,2);platform(11,30,37,5);
            platform(8,45,52,2);platform(10,57,63,5);platform(7,68,74,2);
            spikes(13,12,14);spikes(13,39,41);spikes(13,65,67);
            pickups.add(new Pickup(620,350,PickType.COIN));
            pickups.add(new Pickup(1080,280,PickType.COIN));
            pickups.add(new Pickup(1450,350,PickType.CRYSTAL));
            enemies.add(new Enemy(690,416,3));
            enemies.add(new Enemy(1110,300,3,EnemyType.WISP));
            enemies.add(new Enemy(1500,416,3));
            kael.active=true;kael.x=2050;kael.y=392;
            kael.maxHp=(int)Math.round(110*ai.difficultyScale());
            kael.hp=kael.maxHp;

            pulseUnlocked=true;
            chapter="LEVEL 4 / 4 // ECLIPSE TEMPLE";
            objective="Reach Kael";
            objectiveStep=0;
            a.audio.music("music4");
        }

        // NOVA's learned model changes actual encounter composition, not only numbers.
        if(n<3){
            if(ai.combatSkill>.74){
                double ex=n==0?2180:n==1?2260:0;
                if(ex>0){
                    enemies.add(n==1?new Enemy(ex,300,3,EnemyType.WISP):new Enemy(ex,416,3));
                    directorMode="DIRECTOR // ELITE ENEMY ADDED";
                }
            }else if(ai.combatSkill<.36 && enemies.size()>1){
                enemies.remove(enemies.size()-1);
                directorMode="DIRECTOR // ENCOUNTER PRESSURE REDUCED";
            }

            if(ai.hintNeed>.70){
                double supportX=n==0?1710:n==1?1810:0;
                if(supportX>0){
                    boolean occupied=false;
                    for(Pickup pu:pickups)if(Math.abs(pu.x-supportX)<70)occupied=true;
                    if(!occupied)pickups.add(new Pickup(supportX,350,PickType.CRYSTAL));
                }
            }
        }

        if(n>0 && elena.visible && elena.following){
            elena.x=120;
            elena.y=findGroundY(elena.x);
            elena.dir=1;
        }

        resetPlayer();
        roomStart=total;
        ai.beginRoom(p.x);
    }

    private void resetPlayer(){
        p.x=respawnX;p.y=80;p.vx=p.vy=0;p.dir=1;p.hp=Math.max(70,p.hp);p.energy=100;
        p.fracture=0;p.attack=0;p.inv=0;p.hurt=0;p.onGround=false;p.anim=0;
        p.coyote=0;p.jumpBuffer=0;p.jumpHold=0;
        p.comboStep=0;p.comboQueued=false;p.comboWindow=0;p.attackDuration=0;p.dodge=0;p.dodgeCooldown=0;
        elena.supportCooldown=0;
        elena.attackCooldown=0;elena.shotFxTimer=0;
        camX=clamp(respawnX-IW*.30,0,WORLD_W-IW);
    }

    private void platform(int row,int c1,int c2,int tile){
        for(int c=c1;c<=c2&&c<COLS;c++)if(c>=0)map[row][c]=tile;
    }
    private void spikes(int row,int c1,int c2){
        for(int c=c1;c<=c2&&c<COLS;c++)if(c>=0)map[row][c]=7;
    }
    private void gap(int row,int c1,int c2){
        for(int c=c1;c<=c2&&c<COLS;c++)if(c>=0)map[row][c]=0;
    }

    private void update(double dt){
        if(state==State.PAUSE){
            if(pressTab){pressTab=false;aiPanel=!aiPanel;}
            return;
        }

        total+=dt;
        if(hintTimer>0)hintTimer-=dt;
        if(bannerTimer>0)bannerTimer-=dt;
        if(directorTimer>0)directorTimer-=dt;
        if(assistPlatformTimer>0){
            assistPlatformTimer-=dt;
            if(assistPlatformTimer<=0)assistPlatform=false;
        }
        if(shake>0)shake=Math.max(0,shake-dt*28);
        if(p.fracture>0)p.fracture-=dt;
        if(p.inv>0)p.inv-=dt;
        if(p.hurt>0)p.hurt-=dt;
        if(p.pulseCd>0)p.pulseCd-=dt;
        if(p.dodgeCooldown>0)p.dodgeCooldown-=dt;
        if(p.comboWindow>0)p.comboWindow-=dt;
        p.energy=Math.min(100,p.energy+dt*8*ai.energyRegenScale());
        if(elena.supportCooldown>0)elena.supportCooldown-=dt;
        if(elena.attackCooldown>0)elena.attackCooldown-=dt;

        if(pressTab){pressTab=false;aiPanel=!aiPanel;}

        if(state==State.MENU){
            if(pressEnter||pressSpace){pressEnter=pressSpace=false;newGame();}
            return;
        }
        if(state==State.END){
            if(pressEnter||pressSpace){pressEnter=pressSpace=false;state=State.MENU;a.audio.stopMusic();}
            return;
        }
        if(state==State.DIALOGUE){
            updateDialogue(dt);
            return;
        }
        if(state==State.CHAT){
            if(pressEnter){
                pressEnter=false;
                String q=chatInput.toString().trim();
                if(!q.isEmpty()){
                    chatReply=novaReply(q);
                    chatInput.setLength(0);
                    ai.hintShown();
                }
            }
            return;
        }

        if(hitStop>0){
            hitStop=Math.max(0,hitStop-dt);
            updateParticles(dt*.15);
            updateFloatingTexts(dt*.15);
            return;
        }

        updateAttackTimer(dt);
        updatePlayer(dt);
        updateCompanion(dt);
        updateEnemies(dt);
        updateBoss(dt);
        updateProjectiles(dt);
        updateGates(dt);
        updatePickups();
        updateParticles(dt);
        updateFloatingTexts(dt);
        updateCheckpoint();
        updateAIDirector(dt);

        Double tx=targetX();
        ai.observeFrame(dt,p.x,p.vx,tx==null?p.x:tx);
        if(ai.shouldHint()&&hintTimer<=0){
            ai.hintShown();
            showHint(ai.contextualHint(objective),3.1);
            elena.leadTimer=3.2;
        }

        if(pressE){pressE=false;interact();}
        if(pressQ){pressQ=false;activateFracture();}
        if(pressF){pressF=false;zeroPulse();}
        if(pressAttack){pressAttack=false;meleeAttack();}
        if(pressDodge){pressDodge=false;startDodge();}

        if(p.hp<=0){
            ai.observeRetry();
            showBanner("CHECKPOINT RESTORED",1.0);
            p.hp=100;
            resetPlayer();
        }

        if(p.x>WORLD_W-92&&level<3){
            if(canExitLevel()){
                ai.objectiveComplete(Math.max(1,total-roomStart));
                int nextLevel=level+1;
                buildLevel(nextLevel);
                if(nextLevel==3){
                    showBanner("ZERO PULSE ONLINE // F",1.8);
                    showHint("NOVA: Eclipse interference unlocked Zero Pulse. Press F at close range.",3.0);
                }else{
                    showBanner("LEVEL "+(nextLevel+1)+" / 4",1.4);
                }
            }else{
                p.x=WORLD_W-104;
                p.vx=0;
                showHint("NOVA: Exit route locked. Current objective: "+objective,2.2);
                directorMode="MISSION GATE // OBJECTIVE REQUIRED";
                directorTimer=1.5;
            }
        }
    }

    private void updateDialogue(double dt){
        if(blipCooldown>0)blipCooldown-=dt;
        if(revealChars<dialogue.length()){
            revealAccumulator+=dt*46;
            int target=Math.min(dialogue.length(),(int)revealAccumulator);
            if(target>revealChars){
                revealChars=target;
                if(blipCooldown<=0 && revealChars%4==0){
                    a.audio.play(blipKey,-8f);
                    blipCooldown=.045;
                }
            }
            if(pressE||pressSpace){
                pressE=pressSpace=false;
                revealChars=dialogue.length();
                revealAccumulator=revealChars;
            }
        }else{
            dialogueTimer-=dt;
            if(pressE||pressSpace||dialogueTimer<=0){
                pressE=pressSpace=false;
                Runnable next=dialogueAfter;dialogueAfter=null;
                if(next!=null)next.run();else state=State.PLAY;
            }
        }
    }

    private void updatePlayer(double dt){
        boolean wasGrounded=p.onGround;
        double impactVy=p.vy;

        // Modern platformer jump forgiveness: coyote time + buffered input.
        if(p.onGround)p.coyote=Tuning.Movement.COYOTE_TIME;
        else p.coyote=Math.max(0,p.coyote-dt);

        p.jumpBuffer=Math.max(0,p.jumpBuffer-dt);
        if(pressSpace){
            pressSpace=false;
            p.jumpBuffer=Tuning.Movement.JUMP_BUFFER;
        }

        double move=0;
        if(key(KeyEvent.VK_A)||key(KeyEvent.VK_LEFT))move-=1;
        if(key(KeyEvent.VK_D)||key(KeyEvent.VK_RIGHT))move+=1;

        boolean sprint=key(KeyEvent.VK_SHIFT);
        double groundSpeed=sprint?Tuning.Movement.SPRINT_SPEED:Tuning.Movement.WALK_SPEED;
        double airSpeed=sprint?Tuning.Movement.SPRINT_SPEED*.96:Tuning.Movement.WALK_SPEED*.95;

        if(p.dodge>0){
            p.dodge=Math.max(0,p.dodge-dt);
            p.vx=p.dir*Tuning.Combat.DODGE_SPEED;
            p.inv=Math.max(p.inv,Tuning.Combat.DODGE_INVULN);
            move=0;
        }

        if(move!=0 && p.dodge<=0){
            p.dir=move>0?1:-1;
            double target=move*(p.onGround?groundSpeed:airSpeed);
            double response=p.onGround?Tuning.Movement.GROUND_RESPONSE:Tuning.Movement.AIR_RESPONSE;
            p.vx+=(target-p.vx)*Math.min(1,dt*response);
            p.anim+=dt*(sprint?13.5:10.5);
            footTimer-=dt;
            if(p.onGround&&footTimer<=0){
                footTimer=sprint?.16:.24;
                a.audio.play("foot",-10f);
            }
        }else{
            double drag=p.attack>0?.035:(p.onGround?.0015:.10);
            p.vx*=Math.pow(drag,dt);
            p.anim+=dt*2;
        }

        boolean jumpHeld=key(KeyEvent.VK_SPACE)||key(KeyEvent.VK_W);

        if(p.jumpBuffer>0 && p.coyote>0){
            p.jumpBuffer=0;
            p.coyote=0;
            p.jumpHold=Tuning.Movement.JUMP_HOLD;
            p.vy=-Tuning.Movement.JUMP_SPEED*ai.jumpAssistScale();
            p.onGround=false;
            ai.observeJump();
            a.audio.play("jump",-2f);
            burst(p.x+PW/2,p.y+PH,new Color(215,205,165),10);
        }

        if(p.jumpHold>0)p.jumpHold-=dt;
        if(!jumpHeld && p.vy<0)p.jumpHold=0;

        // Variable-height jump: held jumps float slightly, released jumps cut quickly,
        // falling is faster so landing feels deliberate instead of floaty.
        double gravity;
        if(p.vy<0){
            gravity=(jumpHeld&&p.jumpHold>0)?Tuning.Movement.HELD_GRAVITY:Tuning.Movement.CUT_GRAVITY;
        }else{
            gravity=Tuning.Movement.FALL_GRAVITY;
        }
        p.vy=Math.min(Tuning.Movement.MAX_FALL,p.vy+gravity*dt);

        double oldX=p.x;
        p.x+=p.vx*dt;

        Double missionBarrier=missionBarrierX();
        if(missionBarrier!=null){
            if(p.dir>0 && oldX+PW<=missionBarrier && p.x+PW>missionBarrier){
                p.x=missionBarrier-PW;
                p.vx=0;
                showHint("NOVA: Route sealed until the current objective is complete.",1.8);
                directorMode="MISSION BARRIER // OBJECTIVE REQUIRED";
                directorTimer=1.4;
            }else if(p.dir<0 && oldX>=missionBarrier+18 && p.x<missionBarrier+18){
                p.x=missionBarrier+18;
                p.vx=0;
            }
        }

        double oldFeet=p.y+PH;
        double ny=p.y+p.vy*dt;
        p.onGround=false;

        // NOVA can create a temporary traversal platform after repeated failed jumps.
        boolean landedOnAssist=false;
        if(assistPlatform && p.vy>=0
                && p.x+PW>assistPlatformX && p.x<assistPlatformX+112
                && oldFeet<=assistPlatformY+3 && ny+PH>=assistPlatformY){
            p.y=assistPlatformY-PH;
            p.vy=0;
            p.onGround=true;
            landedOnAssist=true;
        }

        if(!landedOnAssist){
            if(p.vy>=0){
                Double landingTop=findLandingTop(p.x,oldFeet,ny+PH);
                if(landingTop!=null){
                    p.y=landingTop-PH;
                    p.vy=0;
                    p.onGround=true;
                }else{
                    p.y=ny;
                }
            }else{
                // Elevated platforms are deliberately one-way. This removes the
                // "head bonk" / sticky underside that made the old jump feel bad.
                p.y=ny;
            }
        }

        p.x=clamp(p.x,0,WORLD_W-PW);

        if(!wasGrounded && p.onGround && impactVy>145){
            int dust=(int)Math.min(16,6+impactVy/70);
            burst(p.x+PW/2,p.y+PH,new Color(204,192,158),dust);
            shake=Math.max(shake,Math.min(2.5,impactVy/260));
            a.audio.play("land",-9f);
        }

        // spikes
        Rectangle2D pr=p.rect();
        int minC=(int)(p.x/TILE),maxC=(int)((p.x+PW)/TILE);
        int minR=(int)(p.y/TILE),maxR=(int)((p.y+PH)/TILE);
        for(int r=Math.max(0,minR);r<=Math.min(ROWS-1,maxR);r++){
            for(int c=Math.max(0,minC);c<=Math.min(COLS-1,maxC);c++){
                if(map[r][c]==7){
                    Rectangle2D sp=new Rectangle2D.Double(c*TILE,r*TILE+12,TILE,20);
                    if(pr.intersects(sp))damage(18);
                }
            }
        }

        if(p.y>IH+100){
            fallsThisLevel++;
            ai.observeJumpFailure();
            p.hp-=14;
            maybeCreateTraversalAssist();
            p.x=respawnX;p.y=80;p.vy=0;p.vx=0;
        }

        // Camera leads in the direction the player is moving instead of lagging behind.
        double lookAhead=p.dir*72+clamp(p.vx*.15,-35,35);
        double desired=clamp(p.x-IW*.38+lookAhead,0,WORLD_W-IW);
        camX+=(desired-camX)*Math.min(1,dt*6.8);
    }

    private Double findLandingTop(double x,double oldFeet,double newFeet){
        int minC=Math.max(0,(int)((x+5)/TILE));
        int maxC=Math.min(COLS-1,(int)((x+PW-5)/TILE));
        Double best=null;

        for(int r=0;r<ROWS;r++){
            double top=r*TILE;
            if(oldFeet>top+2 || newFeet<top)continue;

            for(int c=minC;c<=maxC;c++){
                int t=map[r][c];
                if(t>=1&&t<=6){
                    if(best==null||top<best)best=top;
                    break;
                }
            }
        }
        return best;
    }

    private void updateCompanion(double dt){
        if(!elena.visible||!elena.following)return;

        double lead=ai.companionLeadDistance();
        Double tx=targetX();
        double target=p.x-(p.dir>0?lead:-lead);

        if(elena.leadTimer>0){
            elena.leadTimer-=dt;
            if(tx!=null)target=clamp(tx-(p.dir>0?90:-90),30,WORLD_W-90);
        }

        double dx=target-elena.x;
        if(Math.abs(dx)>28){
            elena.dir=dx>0?1:-1;
            elena.x+=Math.signum(dx)*Math.min(Math.abs(dx),115*dt);
            elena.anim+=dt*9;
        }else elena.anim+=dt*2;

        elena.y=findGroundY(elena.x);

        // Adaptive companion support
        if(p.hp<35 && elena.supportCooldown<=0 && Math.abs(elena.x-p.x)<220){
            int heal=ai.style()==AdaptiveAI.Style.CAUTIOUS?18:12;
            p.hp=Math.min(100,p.hp+heal);
            p.energy=Math.min(100,p.energy+12);
            floatingTexts.add(new FloatingText("+"+heal+" HP",p.x+PW/2,p.y-10,new Color(255,224,120)));
            elena.supportCooldown=15;
            showHint("ELENA: You're not dropping here. Patch applied +"+heal+" HP.",2.4);
            directorMode="ELENA SUPPORT // MED PATCH";
            directorTimer=2.2;
            burst(p.x,p.y,new Color(245,210,120),18);
            a.audio.play("checkpoint",-3f);
        }

        if(elena.shotFxTimer>0)elena.shotFxTimer=Math.max(0,elena.shotFxTimer-dt);

        // NOVA can authorize Elena to cover the player when the learned combat profile
        // says pressure is too high. This is a real gameplay intervention, not UI text.
        if(elena.attackCooldown<=0 && (ai.combatSkill<.58 || p.hp<62 || bossStarted)){
            Enemy best=null;
            double bestDist=Tuning.Director.ELENA_COVER_RANGE;
            for(Enemy e:enemies){
                if(e.dead)continue;
                double d=Math.abs(e.x-elena.x);
                if(d<bestDist){best=e;bestDist=d;}
            }

            if(best!=null){
                best.hp-=1;
                best.flash=.10;
                best.state=EnemyState.STUNNED;
                best.stateTimer=.22;
                best.knockVx=Math.signum(best.x-elena.x)*80;
                elena.attackCooldown=Tuning.Director.ELENA_COVER_COOLDOWN*(.85+ai.combatSkill*.45);
                elena.shotFxTimer=.20;
                elena.shotTargetX=best.x+24;
                elena.shotTargetY=best.y+14;
                directorMode="ELENA COVER SHOT // AI AUTHORIZED";
                directorTimer=2.0;
                burst(best.x+24,best.y+14,new Color(255,214,90),12);
                a.audio.play("elena_shot",-3f);
                if(best.hp<=0){
                    best.dead=true;
                    coins++;
                }
            }else if(bossStarted && kael.hp>0 && Math.abs(kael.x-elena.x)<Tuning.Director.ELENA_COVER_RANGE+80){
                kael.hp-=3;
                kael.flash=.10;
                elena.attackCooldown=Tuning.Director.ELENA_COVER_COOLDOWN*1.2;
                elena.shotFxTimer=.20;
                elena.shotTargetX=kael.x+28;
                elena.shotTargetY=kael.y+20;
                directorMode="ELENA COVER SHOT // BOSS";
                directorTimer=2.0;
                a.audio.play("pulse",-10f);
                if(kael.hp<=0)bossDefeated();
            }
        }
    }

    private double findGroundY(double x){
        int c=(int)(x/TILE);
        c=Math.max(0,Math.min(COLS-1,c));
        for(int r=0;r<ROWS;r++){
            int t=map[r][c];
            if(t>=1&&t<=6)return r*TILE-50;
        }
        return 400;
    }

    private void updateEnemies(double dt){
        double timeScale=p.fracture>0?.18:1;
        double difficulty=ai.difficultyScale();
        double combatAdapt=clamp(.78+ai.combatSkill*.46,.80,1.16);
        double worldDt=dt*timeScale;

        for(Enemy e:enemies){
            if(e.dead)continue;

            e.anim+=worldDt*(e.type==EnemyType.WISP?8:6)*difficulty;
            e.attackCd=Math.max(0,e.attackCd-worldDt);
            e.flash=Math.max(0,e.flash-dt);
            e.brain.update(e.x, p.x, worldDt);
            if(e.stateTimer>0)e.stateTimer-=worldDt;
            if(e.alertFlash>0)e.alertFlash=Math.max(0,e.alertFlash-dt);

            if(e.type==EnemyType.WISP){
                e.baseY+=(clamp(p.y-35,245,370)-e.baseY)*Math.min(1,worldDt*.25);
                e.y=e.baseY+Math.sin(total*2.6+e.phase)*15;
            }

            double dist=(p.x+PW/2)-(e.x+24);
            double absDist=Math.abs(dist);
            double baseSpeed=(38+15*difficulty)*combatAdapt*(e.type==EnemyType.WISP?1.05:1.0);
            double alertRange=e.type==EnemyType.WISP?360:Tuning.Combat.ENEMY_ALERT_RANGE;
            double attackRange=e.type==EnemyType.WISP?270:Tuning.Combat.ENEMY_ATTACK_RANGE;

            if(e.state==EnemyState.STUNNED){
                e.x+=e.knockVx*worldDt;
                e.knockVx*=Math.pow(.015,worldDt);
                if(e.stateTimer<=0)e.state=absDist<alertRange?EnemyState.CHASE:EnemyState.PATROL;
            }else if(e.state==EnemyState.TELEGRAPH){
                e.dir=dist>=0?1:-1;
                if(e.stateTimer<=0){
                    a.audio.play("enemy_attack",-5f);
                    if(e.type==EnemyType.WISP){
                        double dx=(p.x+PW/2)-(e.x+24);
                        double dy=(p.y+PH/2)-(e.y+20);
                        double len=Math.max(1,Math.hypot(dx,dy));
                        double sp=145+25*difficulty;
                        projectiles.add(new Projectile(e.x+24,e.y+20,dx/len*sp,dy/len*sp,3.0,false));
                    }else if(absDist<Tuning.Combat.ENEMY_ATTACK_RANGE+24 && p.inv<=0 && p.dodge<=0){
                        damage((int)Math.round(9*difficulty));
                    }
                    e.attackCd=e.type==EnemyType.WISP?1.25:.85;
                    e.state=EnemyState.RECOVER;
                    e.stateTimer=e.type==EnemyType.WISP?.55:.42;
                }
            }else if(e.state==EnemyState.RECOVER){
                if(e.stateTimer<=0)e.state=absDist<alertRange?EnemyState.CHASE:EnemyState.PATROL;
            }else if(e.state==EnemyState.CHASE){
                if(absDist>alertRange*1.35){
                    e.state=EnemyState.PATROL;
                }else if(absDist<attackRange && e.attackCd<=0){
                    e.state=EnemyState.TELEGRAPH;
                    e.stateTimer=clamp((e.type==EnemyType.WISP?.46:Tuning.Combat.ENEMY_TELEGRAPH)/difficulty,.24,.52);
                    e.vx=0;
                    a.audio.play("enemy_alert",-5f);
                }else{
                    e.dir=dist>=0?1:-1;
                    e.x+=e.dir*baseSpeed*(e.type==EnemyType.WISP?.92:1.12)*worldDt;
                }
            }else{
                if(absDist<alertRange){
                    e.state=EnemyState.CHASE;
                    e.alertFlash=.35;
                }else{
                    e.x+=e.dir*baseSpeed*.78*worldDt;
                    if(e.x<=e.left){e.x=e.left;e.dir=1;}
                    if(e.x>=e.right){e.x=e.right;e.dir=-1;}
                }
            }

            e.x=clamp(e.x,e.left,e.right);

            if(e.type==EnemyType.CRAB && e.state!=EnemyState.TELEGRAPH
                    && p.rect().intersects(e.rect())&&p.inv<=0&&e.attackCd<=0){
                e.state=EnemyState.TELEGRAPH;
                e.stateTimer=.16;
            }else if(e.type==EnemyType.WISP && p.rect().intersects(e.rect())&&p.inv<=0&&p.dodge<=0){
                damage((int)Math.round(6*difficulty));
                e.attackCd=.8;
                e.x-=e.dir*20;
            }

            if(attackActive() && !p.attackHit && attackBox().intersects(e.rect())){
                int step=Math.max(1,p.comboStep);
                int damage=Tuning.Combat.ENEMY_DAMAGE[step-1];
                e.hp-=damage;
                floatingTexts.add(new FloatingText("-"+damage,e.x+24,e.y-8,new Color(255,226,120)));
                e.flash=.11;
                e.state=EnemyState.STUNNED;
                e.stateTimer=step==3?.44:.26;
                e.knockVx=p.dir*Tuning.Combat.HIT_KNOCKBACK[step-1];
                p.attackHit=true;
                ai.observeAttack(true);
                hitStop=step==3?.065:.04;
                shake=Math.max(shake,step==3?5:2.5);
                burst(e.x+24,e.y+14,new Color(255,220,110),step==3?20:12);
                a.audio.play(step==3?"boss_hit":"hit",step==3?-1f:-3f);

                if(e.hp<=0){
                    e.dead=true;
                    int adaptiveHeal=ai.combatSkill<.45?12:ai.combatSkill<.62?7:4;
                    p.hp=Math.min(100,p.hp+adaptiveHeal);
                    floatingTexts.add(new FloatingText("+"+adaptiveHeal+" HP",e.x+24,e.y-20,new Color(135,235,160)));
                    coins++;
                    directorMode="COMBAT LEARNED // RECOVERY +"+adaptiveHeal+" HP";
                    directorTimer=2.0;
                    a.audio.play("pickup",-4f);
                }
            }
        }
    }

    private void updateBoss(double dt){
        if(level!=3||!kael.active||kael.hp<=0)return;

        if(!bossStarted){
            if(p.x>1760){
                bossStarted=true;
                objectiveStep=1;
                objective="Defeat Kael";
                a.audio.music("boss");
                showBossIntro();
            }
            return;
        }

        double difficulty=ai.difficultyScale();
        double timeScale=p.fracture>0?.30:1.0;
        double bossDt=dt*timeScale;
        kael.anim+=bossDt*4;
        kael.flash=Math.max(0,kael.flash-dt);
        kael.enraged=kael.hp<kael.maxHp*.5;

        if(kael.telegraph>0){
            kael.telegraph-=bossDt;
            if(kael.telegraph<=0)executeBossPattern(difficulty);
        }else{
            kael.timer-=bossDt;
            if(kael.timer<=0){
                int patternCount=kael.enraged?4:3;
                kael.phase=(kael.phase+1)%patternCount;
                kael.telegraph=clamp((kael.enraged?.38:.48)/difficulty,.24,.52);
                directorMode="BOSS READ // PATTERN "+(kael.phase+1)+(kael.enraged?" ENRAGED":"");
                directorTimer=1.0;
            }
        }

        if(p.rect().intersects(kael.rect())&&p.inv<=0&&p.dodge<=0)
            damage((int)Math.round(10*difficulty));

        if(attackActive()&&!p.attackHit&&attackBox().intersects(kael.rect())){
            int step=Math.max(1,p.comboStep);
            int bossDamage=Tuning.Combat.BOSS_DAMAGE[step-1];
            kael.hp-=bossDamage;
            floatingTexts.add(new FloatingText("-"+bossDamage,kael.x+28,kael.y-8,new Color(225,155,255)));
            p.attackHit=true;
            ai.observeAttack(true);
            hitStop=step==3?.075:.045;
            shake=Math.max(shake,step==3?6:3);
            kael.flash=.12;
            burst(kael.x+28,kael.y+22,new Color(205,130,255),step==3?24:16);
            a.audio.play("boss_hit",step==3?0f:-2f);
            if(kael.hp<=0)bossDefeated();
        }
    }

    private void executeBossPattern(double difficulty){
        kael.timer=clamp((kael.enraged?1.55:2.0)/difficulty,1.0,2.25);

        if(kael.phase==0){
            // Shadow step. The telegraph gives the player time to dodge.
            double side=p.dir>0?1:-1;
            kael.x=clamp(p.x+side*(kael.enraged?145:205),1720,2380);
            burst(kael.x,kael.y,new Color(165,90,225),28);
            if(Math.abs(kael.x-p.x)<85&&p.inv<=0&&p.dodge<=0)
                damage((int)Math.round(8*difficulty));
        }else{
            int shots=kael.phase==1?1:kael.phase==2?3:5;
            double baseDx=(p.x+PW/2)-kael.x;
            double baseDy=(p.y+PH/2)-kael.y;
            double angle=Math.atan2(baseDy,baseDx);
            double spread=kael.phase==3?.18:.13;
            double speed=(kael.enraged?205:175)+25*difficulty;

            for(int i=0;i<shots;i++){
                double offset=(i-(shots-1)/2.0)*spread;
                double a0=angle+offset;
                projectiles.add(new Projectile(
                        kael.x+24,kael.y+20,
                        Math.cos(a0)*speed,Math.sin(a0)*speed,
                        kael.phase==3?4.0:3.4,
                        kael.phase>=2));
            }
            a.audio.play("fracture",-7f);
        }
    }

    private void showBossIntro(){
        showDialogue("KAEL",
                "Four ruins. Three fragments. One relic. You still think you're hunting treasure?",
                "kael_portrait","blip_kael",3.4,()->
        showDialogue("ALEX",
                "I'm hunting answers.",
                "alex_portrait","blip_alex",2.1,()->
        showDialogue("KAEL",
                "Then survive long enough to ask the right question.",
                "kael_portrait","blip_kael",2.8,()-> state=State.PLAY)));
    }

    private void updateProjectiles(double dt){
        if(projectiles.isEmpty())return;
        double timeScale=p.fracture>0?.30:1.0;
        double difficulty=ai.difficultyScale();

        for(int i=projectiles.size()-1;i>=0;i--){
            Projectile q=projectiles.get(i);
            q.life-=dt*timeScale;
            q.x+=q.vx*dt*timeScale;
            q.y+=q.vy*dt*timeScale;

            if(q.life<=0 || q.x<-100 || q.x>WORLD_W+100 || q.y<-100 || q.y>IH+120){
                projectiles.remove(i);
                continue;
            }

            if(p.rect().intersects(q.rect())&&p.inv<=0&&p.dodge<=0){
                damage((int)Math.round((q.heavy?15:10)*difficulty));
                burst(q.x,q.y,new Color(185,115,240),10);
                projectiles.remove(i);
            }
        }
    }

    private void updateGates(double dt){
        if(level!=2)return;
        double slow=p.fracture>0?.14:1;

        for(TemporalGate g:gates){
            g.y=140+Math.sin(total*3*slow+g.phase)*115;
            Rectangle2D gr=new Rectangle2D.Double(g.x,g.y,26,255);
            if(p.rect().intersects(gr)&&p.fracture<=0){
                p.x-=Math.signum(p.vx==0?1:p.vx)*18;p.vx=0;
                damage(6);
                showHint("NOVA: Temporal gate too fast. Press Q, then move through.",2.3);
            }
        }
    }

    private void updatePickups(){
        for(Pickup pu:pickups){
            if(pu.taken)continue;
            double dx=Math.abs((p.x+PW/2)-pu.x);
            double dy=Math.abs((p.y+PH/2)-pu.y);

            if((pu.type==PickType.COIN||pu.type==PickType.CRYSTAL)&&dx<28&&dy<45){
                pu.taken=true;ai.observeTreasure();
                if(pu.type==PickType.COIN){
                    coins++;p.energy=Math.min(100,p.energy+8);
                }else{
                    treasures++;p.energy=Math.min(100,p.energy+22);
                }
                burst(pu.x,pu.y,pu.type==PickType.COIN?new Color(245,205,70):new Color(90,225,235),14);
                a.audio.play("pickup",-3f);
            }
        }
    }

    private void updateCheckpoint(){
        if(!checkpointReached&&p.x>WORLD_W*.52){
            checkpointReached=true;
            respawnX=p.x;
            showBanner("CHECKPOINT",1.1);
            a.audio.play("checkpoint",-3f);
        }
    }

    private void maybeCreateTraversalAssist(){
        if(!ai.wantsTraversalAssist())return;

        assistPlatform=true;
        assistPlatformTimer=22;
        assistPlatformX=clamp(p.x-35,48,WORLD_W-160);
        assistPlatformY=(level==2)?350:372;

        directorMode="TRAVERSAL ASSIST // TEMP PLATFORM";
        directorTimer=5.0;
        showHint("NOVA: I detected repeated traversal failure. Temporary anchor deployed.",3.1);
        a.audio.play("ui",-2f);
    }

    private void updateAIDirector(double dt){
        AdaptiveAI.Style currentStyle=ai.style();
        if(currentStyle!=lastAIStyle){
            lastAIStyle=currentStyle;
            showBanner("NOVA PROFILE SHIFT // "+currentStyle,1.8);
            directorMode="MODEL UPDATED // "+currentStyle;
            directorTimer=2.0;
            a.audio.play("ui",-4f);
            return;
        }

        if(directorTimer>0)return;

        if(assistPlatform){
            directorMode="TRAVERSAL ASSIST ACTIVE";
        }else if(bossStarted&&kael.hp>0){
            directorMode=String.format(Locale.ROOT,"BOSS MODEL %.2fx // %s",ai.difficultyScale(),ai.style());
        }else if(p.hp<38&&elena.visible&&elena.following){
            directorMode="ELENA SUPPORT PRIORITY";
        }else if(ai.hintNeed>.64){
            directorMode="ROUTE GUIDANCE // HIGH";
            if(elena.visible&&elena.following)elena.leadTimer=Math.max(elena.leadTimer,1.5);
        }else if(ai.combatSkill<.43){
            directorMode="COMBAT ASSIST // LOWER PRESSURE";
        }else if(ai.combatSkill>.72){
            directorMode="COMBAT CHALLENGE // HIGH PRESSURE";
        }else if(ai.treasureDrive>.67){
            directorMode="TREASURE SCAN // OPTIONAL RELICS";
        }else{
            directorMode="ADAPTIVE "+ai.style()+" // OBSERVING";
        }
        directorTimer=1.2;
    }

    private void updateAttackTimer(double dt){
        if(p.attack<=0)return;

        p.attack-=dt;
        if(p.attack<=0){
            p.attack=0;
            if(p.comboQueued && p.comboStep<3){
                p.comboQueued=false;
                startAttack(p.comboStep+1);
            }else{
                p.comboWindow=.16;
            }
        }
    }

    private void meleeAttack(){
        if(state!=State.PLAY||p.dodge>0)return;

        if(p.attack>0){
            // Queue the next strike instead of eating the input.
            if(p.comboStep<3)p.comboQueued=true;
            return;
        }

        int next=(p.comboWindow>0 && p.comboStep>0 && p.comboStep<3)?p.comboStep+1:1;
        startAttack(next);
    }

    private void startAttack(int step){
        p.comboStep=step;
        p.comboQueued=false;
        p.attackDuration=Tuning.Combat.COMBO_DURATION[step-1];
        p.attack=p.attackDuration;
        p.attackHit=false;
        p.comboWindow=.24;
        p.vx+=p.dir*(step==3?55:30);
        String swing=step==1?"attack":step==2?"attack2":"attack3";
        a.audio.play(swing,step==3?-2f:-3f);
        ai.observeAttack(false);
    }

    private boolean attackActive(){
        if(p.attack<=0||p.attackDuration<=0)return false;
        double progress=1.0-p.attack/p.attackDuration;
        return progress>.22 && progress<.72;
    }

    private Rectangle2D attackBox(){
        int step=Math.max(1,p.comboStep);
        double reach=step==3?54:step==2?46:40;
        double x=p.dir>0?p.x+PW-3:p.x-reach+3;
        return new Rectangle2D.Double(x,p.y+7,reach,35);
    }

    private void startDodge(){
        if(state!=State.PLAY||p.dodgeCooldown>0||p.attack>0)return;
        p.dodge=Tuning.Combat.DODGE_TIME;
        p.dodgeCooldown=.52;
        p.inv=Math.max(p.inv,Tuning.Combat.DODGE_INVULN);
        p.vx=p.dir*Tuning.Combat.DODGE_SPEED;
        directorMode="EVASIVE MOVE // I-FRAMES";
        directorTimer=.8;
        a.audio.play("dodge",-3f);
        burst(p.x+PW/2,p.y+PH/2,new Color(175,225,225),10);
    }

    private void interact(){
        if(level==0){
            if(objectiveStep==0&&near(elena.x,78)){
                sequence(new Line[]{
                    new Line("ELENA","There you are. I was starting to think the ruins had swallowed you.","elena_portrait","blip_elena"),
                    new Line("ALEX","They tried. What's with the chest?","alex_portrait","blip_alex"),
                    new Line("ELENA","It opened when you crossed the gate. Then NOVA started saying your name.","elena_portrait","blip_elena"),
                    new Line("NOVA",novaStoryLine("identity"),"nova_portrait","blip_nova")
                },()->{
                    objectiveStep=1;objective="Open the sealed treasure chest";
                    elena.following=true;state=State.PLAY;
                });
            }else if(objectiveStep==1&&near(930,82)){
                objectiveStep=2;objective="Reach the exit flag";
                timeUnlocked=true;treasures++;ai.observeTreasure();
                for(Pickup pu:pickups)if(pu.type==PickType.CHEST)pu.taken=true;
                burst(930,390,new Color(246,207,95),32);
                a.audio.play("chest",-1f);
                sequence(new Line[]{
                    new Line("NOVA",novaStoryLine("relic"),"nova_portrait","blip_nova"),
                    new Line("ALEX","Restored? I've never seen this thing.","alex_portrait","blip_alex"),
                    new Line("ELENA","That's exactly what you said last time.","elena_portrait","blip_elena")
                },()->{
                    showBanner("TIME FRACTURE UNLOCKED",1.8);
                    showHint("Press Q to fracture time. Enemies and temporal hazards slow down.",3.0);
                    state=State.PLAY;
                });
            }
        }else if(level==1){
            if(objectiveStep==0&&near(mira.x,78)){
                sequence(new Line[]{
                    new Line("MIRA","Good, you're alive. Bad news: the map exploded.","mira_portrait","blip_mira"),
                    new Line("ALEX","Exploded?","alex_portrait","blip_alex"),
                    new Line("MIRA","Into three pieces. Ancient artifacts love drama.","mira_portrait","blip_mira"),
                    new Line("NOVA",novaStoryLine("map"),"nova_portrait","blip_nova")
                },()->{
                    objectiveStep=1;objective="Recover Map Fragment 1 / 3";state=State.PLAY;
                });
            }else if(objectiveStep==1){
                for(Pickup pu:pickups){
                    if(!pu.taken&&pu.type==PickType.MAP&&near(pu.x,72)&&Math.abs((p.y+PH/2)-pu.y)<100){
                        pu.taken=true;mapFragments++;ai.observeTreasure();
                        a.audio.play("pickup",-2f);
                        burst(pu.x,pu.y,new Color(240,210,135),18);

                        if(mapFragments<3){
                            objective="Recover Map Fragment "+(mapFragments+1)+" / 3";
                            showHint("NOVA: Fragment synchronized. Route confidence "+(33*mapFragments)+"%.",1.8);
                        }else{
                            objectiveStep=2;objective="Reach the exit flag";
                            sequence(new Line[]{
                                new Line("ELENA","The complete route ends at the canyon.","elena_portrait","blip_elena"),
                                new Line("MIRA","No. It ends in empty air.","mira_portrait","blip_mira"),
                                new Line("NOVA",novaStoryLine("route"),"nova_portrait","blip_nova")
                            },()->{showBanner("ANCIENT MAP COMPLETE",1.4);state=State.PLAY;});
                        }
                        break;
                    }
                }
            }
        }else if(level==2){
            if(objectiveStep==0&&near(noah.x,78)){
                sequence(new Line[]{
                    new Line("NOAH","Don't watch the bridge. Listen to it.","noah_portrait","blip_noah"),
                    new Line("ALEX","That's not how bridges work.","alex_portrait","blip_alex"),
                    new Line("NOAH","This one disagrees.","noah_portrait","blip_noah"),
                    new Line("NOVA",novaStoryLine("canyon"),"nova_portrait","blip_nova")
                },()->{
                    objectiveStep=1;objective="Cross the temporal bridge";state=State.PLAY;
                });
            }
        }else if(level==3&&kael.hp<=0&&near(kael.x,115)){
            sequence(new Line[]{
                new Line("KAEL","You learned faster this time.","kael_portrait","blip_kael"),
                new Line("ALEX","This time?","alex_portrait","blip_alex"),
                new Line("KAEL","Ask NOVA who taught it your name.","kael_portrait","blip_kael"),
                new Line("NOVA",novaStoryLine("final"),"nova_portrait","blip_nova"),
                new Line("ELENA","Alex... don't open that door.","elena_portrait","blip_elena")
            },()->{
                ai.save();
                a.audio.stopMusic();
                a.audio.play("complete",-1f);
                state=State.END;
            });
        }
    }

    private void activateFracture(){
        if(!timeUnlocked){
            showHint("NOVA: Time Fracture remains locked until the Zero Relic synchronizes.",1.7);
            return;
        }
        if(p.energy<22||p.fracture>0)return;
        p.energy-=22;p.fracture=3.3;shake=3;
        ai.observePower();a.audio.play("fracture",-2f);
        burst(p.x,p.y,new Color(80,225,245),26);
    }

    private void zeroPulse(){
        if(!pulseUnlocked){
            showHint("NOVA: Zero Pulse is unavailable outside the Eclipse Temple.",1.7);
            return;
        }
        if(p.energy<18||p.pulseCd>0)return;
        p.energy-=18;p.pulseCd=.38;shake=4;ai.observePower();
        a.audio.play("pulse",-2f);
        burst(p.x,p.y,new Color(90,230,250),28);

        for(Enemy e:enemies){
            if(!e.dead&&Math.abs(e.x-p.x)<165){
                e.hp-=2;
                if(e.hp<=0){e.dead=true;coins++;a.audio.play("pickup",-5f);}
            }
        }

        if(bossStarted&&kael.hp>0&&Math.abs(kael.x-p.x)<220){
            int pulseDamage=13;
            kael.hp-=pulseDamage;
            kael.flash=.12;
            floatingTexts.add(new FloatingText("-"+pulseDamage,kael.x+28,kael.y-12,new Color(120,235,250)));
            hitStop=.045;
            shake=Math.max(shake,4);
            burst(kael.x,kael.y,new Color(170,95,235),18);
            a.audio.play("boss_hit",-1f);
            if(kael.hp<=0)bossDefeated();
        }
    }

    private void bossDefeated(){
        kael.hp=0;bossStarted=false;projectiles.clear();
        objective="Approach Kael";
        showBanner("KAEL DEFEATED",1.7);
        a.audio.music("music4");
        a.audio.play("complete",-2f);
        ai.objectiveComplete(Math.max(1,total-roomStart));
    }

    private void damage(int amount){
        if(p.inv>0)return;
        p.hp-=amount;p.inv=.70;p.hurt=.28;shake=5;
        ai.observeDamage();a.audio.play("hit",-2f);
    }

    private void sequence(Line[] lines,Runnable after){
        runLine(lines,0,after);
    }

    private void runLine(Line[] lines,int idx,Runnable after){
        if(idx>=lines.length){after.run();return;}
        Line l=lines[idx];
        showDialogue(l.who,l.text,l.portrait,l.blip,2.1,()->runLine(lines,idx+1,after));
    }

    private void showDialogue(String who,String text,String portrait,String blip,double hold,Runnable after){
        speaker=who;dialogue=text;portraitKey=portrait;blipKey=blip;
        dialogueTimer=hold;dialogueAfter=after;
        revealChars=0;revealAccumulator=0;blipCooldown=0;
        state=State.DIALOGUE;
    }

    private void showHint(String s,double secs){hint=s;hintTimer=secs;}
    private void showBanner(String s,double secs){banner=s;bannerTimer=secs;}
    private boolean near(double wx,double r){return Math.abs((p.x+PW/2)-wx)<r;}

    private String novaStoryLine(String event){
        AdaptiveAI.Style style=ai.style();
        return switch(event){
            case "identity" -> switch(style){
                case CAUTIOUS -> "Correction: I remembered his name. I am withholding the rest until his memory stabilizes.";
                case TACTICAL -> "Correction: I remembered his name. Prior expedition data is still encrypted.";
                case CURIOUS -> "Correction: I remembered his name. The strange part is that the ruins remembered it too.";
                case BOLD -> "Correction: I remembered his name. He has done this before.";
            };
            case "relic" -> switch(style){
                case CAUTIOUS -> "Zero Relic synchronized. I will damp the first fracture until your timing stabilizes.";
                case TACTICAL -> "Zero Relic synchronized. Time Fracture combat telemetry is now active.";
                case CURIOUS -> "Zero Relic synchronized. It contains a memory of being opened by you.";
                case BOLD -> "Zero Relic synchronized. Time is yours to break again.";
            };
            case "map" -> switch(style){
                case CAUTIOUS -> "Intentional fragmentation: ninety-six percent. I will mark the safest fragment order.";
                case TACTICAL -> "Intentional fragmentation: ninety-six percent. Three retrieval vectors calculated.";
                case CURIOUS -> "Intentional fragmentation: ninety-six percent. Someone wanted the map to be solved, not found.";
                case BOLD -> "Intentional fragmentation: ninety-six percent. Three pieces. Keep moving.";
            };
            case "route" -> switch(style){
                case CAUTIOUS -> "Only in normal time. I can stabilize the dangerous windows if your jumps fail repeatedly.";
                case TACTICAL -> "Only in normal time. Fracture the gates, sprint the safe window, conserve Zero energy.";
                case CURIOUS -> "Only in normal time. The bridge exists in several versions at once.";
                case BOLD -> "Only in normal time. Break time and make your own route.";
            };
            case "canyon" -> switch(style){
                case CAUTIOUS -> "Temporal gates detected. I will intervene if repeated traversal failures exceed tolerance.";
                case TACTICAL -> "Temporal gates detected. Q slows gate cycles and enemy decision speed.";
                case CURIOUS -> "Temporal gates detected. Each one is a different second of the same bridge.";
                case BOLD -> "Temporal gates detected. You know what Q does.";
            };
            case "final" -> switch(style){
                case CAUTIOUS -> "Memory conflict detected. Alex, do not force the archive open.";
                case TACTICAL -> "Memory conflict detected. Source signature matches my own training data.";
                case CURIOUS -> "Memory conflict detected. The missing memory begins before my first recorded boot.";
                case BOLD -> "Memory conflict detected. Kael is telling the truth about one thing: I knew you first.";
            };
            default -> "Memory integrity uncertain.";
        };
    }

    private String novaReply(String q){
        String s=q.toLowerCase(Locale.ROOT);

        if(s.contains("where")||s.contains("objective")||s.contains("go")||s.contains("what do i do"))
            return ai.contextualHint(objective);

        if(s.contains("elena"))
            return "Elena is your expedition partner. I change her follow distance and support timing from your learned profile.";

        if(s.contains("mira"))
            return level<1?"Mira's signal is deeper in the archive.":"Mira understands relic maps better than any of us. Her sarcasm appears nonessential but persistent.";

        if(s.contains("noah"))
            return level<2?"Noah's signal is beyond the archive.":"Noah survived the Fractured Canyon. Follow his timing, not the bridge's appearance.";

        if(s.contains("kael"))
            return level<3?"Kael's signal is shielded. Recover more route data.":"Kael's boss timing and aggression adapt to your learned combat profile.";

        if(s.contains("learn")||s.contains("train")||s.contains("ai"))
            return String.format(Locale.ROOT,
                    "I learn locally from hesitation, wrong turns, objective speed, damage, attacks, treasure collection, jump failures, power use, retries and hints. Current confidence %.0f%%, combat %.0f%%, treasure %.0f%%. Director action: %s.",
                    ai.confidence*100,ai.combatSkill*100,ai.treasureDrive*100,directorMode);

        if(s.contains("time")||s.contains("fracture"))
            return timeUnlocked?"Press Q. Time Fracture slows moving enemies, temporal gates and some boss patterns.":"Synchronize the Zero Relic in the first ruins to unlock it.";

        if(s.contains("pulse"))
            return pulseUnlocked?"Press F. Zero Pulse is a short-range area attack that damages corruption and Kael.":"Zero Pulse synchronizes inside the Eclipse Temple.";

        if(s.contains("attack")||s.contains("fight"))
            return "Press J or Up Arrow for melee. F uses Zero Pulse when available. I raise or lower encounter pressure from your combat profile.";

        if(s.contains("treasure")||s.contains("relic"))
            return "Coins restore a little Zero energy. Cyan crystals restore more. The Zero Relic itself stores memories from previous timelines.";

        return ai.profileLine()+" Ask me about the objective, team, powers, combat, treasure or how I learn.";
    }

    private Double missionBarrierX(){
        if(level==0 && objectiveStep<2)return 1048.0;
        if(level==1 && objectiveStep<2)return 1745.0;
        if(level==2 && objectiveStep<1)return 430.0;
        return null;
    }

    private boolean canExitLevel(){
        if(level==0)return objectiveStep>=2;
        if(level==1)return objectiveStep>=2;
        if(level==2)return objectiveStep>=1;
        return false;
    }

    private Double targetX(){
        if(level==0){
            if(objectiveStep==0)return elena.x;
            if(objectiveStep==1)return 930.0;
            return WORLD_W-80.0;
        }
        if(level==1){
            if(objectiveStep==0)return mira.x;
            if(objectiveStep==1){
                for(Pickup pu:pickups)if(!pu.taken&&pu.type==PickType.MAP)return pu.x;
            }
            return WORLD_W-80.0;
        }
        if(level==2){
            if(objectiveStep==0)return noah.x;
            return WORLD_W-80.0;
        }
        if(!bossStarted&&kael.hp>0)return kael.x;
        return kael.x;
    }

    private void burst(double x,double y,Color c,int n){
        Random r=new Random();
        for(int i=0;i<n;i++){
            double a0=r.nextDouble()*Math.PI*2,sp=30+r.nextDouble()*125;
            particles.add(new Particle(x,y,Math.cos(a0)*sp,Math.sin(a0)*sp,.35+r.nextDouble()*.7,1+r.nextDouble()*3,c));
        }
    }

    private void updateParticles(double dt){
        for(int i=particles.size()-1;i>=0;i--){
            Particle q=particles.get(i);
            q.life-=dt;q.x+=q.vx*dt;q.y+=q.vy*dt;
            q.vx*=.96;q.vy=q.vy*.96+55*dt;
            if(q.life<=0)particles.remove(i);
        }
    }

    // ---------- RENDER ----------
    private void render(Graphics2D g){
        if(state==State.MENU){drawMenu(g);return;}

        int shakeX=shake>0?(int)Math.round(Math.sin(total*71.0)*shake):0;
        int shakeY=shake>0?(int)Math.round(Math.cos(total*59.0)*shake*.65):0;
        if(shakeX!=0||shakeY!=0)g.translate(shakeX,shakeY);

        drawParallax(g);
        drawWorld(g);
        drawObjects(g);
        drawCharacters(g);
        drawProjectiles(g);
        drawParticles(g);
        drawFloatingTexts(g);
        drawAtmosphere(g);
        drawEffects(g);

        if(shakeX!=0||shakeY!=0)g.translate(-shakeX,-shakeY);
        drawHUD(g);

        if(state==State.DIALOGUE)drawDialogue(g);
        if(state==State.CHAT)drawChat(g);
        if(aiPanel)drawAIPanel(g);
        if(state==State.PAUSE)drawPause(g);
        if(state==State.END)drawEnd(g);
    }

    private void drawMenu(Graphics2D g){
        drawLayer(g,a.get("sky1"),0);
        drawLayer(g,a.get("far1"),0);
        drawLayer(g,a.get("mid1"),0);
        g.setColor(new Color(0,0,0,105));g.fillRect(0,0,IW,IH);

        g.setFont(font(Font.BOLD,42));center(g,"ZERO: ECLIPSE",150,Color.WHITE);
        g.setFont(font(Font.BOLD,17));center(g,"TREASURE HUNTER EDITION // V7",185,new Color(255,218,105));
        g.setFont(font(Font.PLAIN,13));center(g,"Sharper pixel art • rebuilt jump • 3-hit combat • adaptive NOVA Director • smarter enemies • boss phases",230,new Color(244,244,230));

        g.setColor(new Color(0,0,0,155));g.fillRoundRect(250,275,524,90,10,10);
        g.setFont(font(Font.BOLD,18));center(g,"PRESS ENTER",307,new Color(255,236,165));
        g.setFont(font(Font.PLAIN,11));center(g,"A/D MOVE • SPACE JUMP • J/UP ATTACK • CTRL/K DODGE • E INTERACT • Q FRACTURE • F PULSE • N NOVA",340,new Color(225,230,220));

        // character line-up
        drawSheetFrame(g,"alex_sheet",0,270,382,96,84,1);
        drawSheetFrame(g,"elena_sheet",0,380,382,96,84,1);
        drawSheetFrame(g,"mira_sheet",0,490,382,96,84,1);
        drawSheetFrame(g,"noah_sheet",0,600,382,96,84,1);
        drawSheetFrame(g,"kael_sheet",0,710,382,96,84,1);
        drawNovaAt(g,755,400);
    }

    private void drawParallax(Graphics2D g){
        int idx=level+1;
        drawRepeatedLayer(g,a.get("sky"+idx),camX*.03);
        drawRepeatedLayer(g,a.get("far"+idx),camX*.12);
        drawRepeatedLayer(g,a.get("mid"+idx),camX*.28);
    }

    private void drawRepeatedLayer(Graphics2D g,BufferedImage im,double scroll){
        int w=im.getWidth();
        int x=-(int)(scroll%w);
        g.drawImage(im,x,0,null);
        g.drawImage(im,x+w,0,null);
    }

    private void drawLayer(Graphics2D g,BufferedImage im,int x){g.drawImage(im,x,0,null);}

    private void drawWorld(Graphics2D g){
        int start=Math.max(0,(int)(camX/TILE)-1);
        int end=Math.min(COLS-1,start+IW/TILE+3);

        for(int r=0;r<ROWS;r++){
            for(int c=start;c<=end;c++){
                int t=map[r][c];
                if(t==0)continue;
                drawTile(g,t,(int)(c*TILE-camX),r*TILE);
            }
        }

        // exit flag
        int exitX=(int)(WORLD_W-82-camX);
        drawTile(g,9,exitX,384);
        if(level<3 && !canExitLevel()){
            g.setColor(new Color(180,55,45,180));g.fillRoundRect(exitX-3,356,38,18,6,6);
            g.setFont(font(Font.BOLD,8));g.setColor(Color.WHITE);g.drawString("LOCK",exitX+5,368);
        }

        // foreground
        BufferedImage front=a.get("front"+(level+1));
        int fx=-(int)((camX*.52)%front.getWidth());
        g.drawImage(front,fx,0,null);g.drawImage(front,fx+front.getWidth(),0,null);
    }

    private void drawTile(Graphics2D g,int id,int x,int y){
        BufferedImage ts=a.get("tiles");
        int sx=0,sy=0;
        switch(id){
            case 1->{sx=0;sy=0;}
            case 2->{sx=32;sy=0;}
            case 3->{sx=64;sy=0;}
            case 4->{sx=96;sy=0;}
            case 5->{sx=128;sy=0;}
            case 6->{sx=160;sy=0;}
            case 7->{sx=0;sy=32;}
            case 8->{sx=32;sy=32;}
            case 9->{sx=64;sy=32;}
            case 10->{sx=96;sy=32;}
            case 11->{sx=128;sy=32;}
            case 12->{sx=160;sy=32;}
            case 13->{sx=192;sy=32;}
            case 14->{sx=240;sy=32;}
            case 15->{sx=272;sy=32;}
            case 16->{sx=304;sy=32;}
            case 17->{sx=352;sy=32;}
            default->{return;}
        }
        g.drawImage(ts,x,y,x+32,y+32,sx,sy,sx+32,sy+32,null);
    }

    private void drawObjects(Graphics2D g){
        Double mb=missionBarrierX();
        if(mb!=null){
            int bx=(int)(mb-camX);
            for(int i=0;i<5;i++){
                int off=i*5;
                g.setColor(new Color(85,225,245,28+i*10));
                g.fillRect(bx-off/2,116,18+off,332);
            }
            g.setColor(new Color(185,250,255,210));g.drawLine(bx+8,118,bx+8,446);
            g.setFont(font(Font.BOLD,8));g.setColor(new Color(200,250,255));
            g.drawString("OBJECTIVE LOCK",bx-23,107);
        }

        if(assistPlatform){
            int ax=(int)(assistPlatformX-camX), ay=(int)assistPlatformY;
            g.setColor(new Color(80,225,245,45));g.fillRoundRect(ax-8,ay-8,128,28,8,8);
            g.setColor(new Color(130,245,255,210));g.fillRect(ax,ay,112,7);
            g.setColor(new Color(220,255,255,210));
            for(int xx=ax+5;xx<ax+108;xx+=16)g.fillRect(xx,ay+2,7,2);
            g.setFont(font(Font.BOLD,8));g.setColor(new Color(165,245,255));
            g.drawString("NOVA ANCHOR",ax+17,ay-12);
        }

        for(Pickup pu:pickups){
            if(pu.taken)continue;
            int x=(int)(pu.x-camX),y=(int)pu.y;
            if(pu.type==PickType.CHEST){
                if(level==0&&objectiveStep==1){
                    int rr=30+(int)(Math.sin(total*4)*3);
                    g.setColor(new Color(255,210,85,28));g.fillOval(x-rr,y+14-rr,rr*2,rr*2);
                }
                drawTile(g,8,x-16,y);
            }
            else if(pu.type==PickType.COIN)drawTile(g,14,x-16,y-16);
            else if(pu.type==PickType.CRYSTAL)drawTile(g,17,x-16,y-16);
            else drawMapPickup(g,pu);

            // A curious / treasure-focused player gets a real AI scan assistance layer.
            if((pu.type==PickType.COIN||pu.type==PickType.CRYSTAL)
                    && (ai.treasureDrive>.64 || directorMode.contains("TREASURE SCAN"))){
                int rr=17+(int)(Math.sin(total*4+pu.x*.01)*3);
                g.setColor(new Color(95,235,245,85));g.drawOval(x-rr,y-rr,rr*2,rr*2);
                g.setColor(new Color(95,235,245,45));g.drawLine(x,y-rr-4,x,y-rr-18);
            }
        }

        if(level==2){
            for(TemporalGate tg:gates)drawGate(g,tg);
        }

        for(Enemy e:enemies)if(!e.dead)drawEnemy(g,e);
    }

    private void drawMapPickup(Graphics2D g,Pickup pu){
        int x=(int)(pu.x-camX),y=(int)pu.y;
        g.setColor(new Color(247,224,166));g.fillRect(x-11,y-14,22,28);
        g.setColor(new Color(112,80,39));g.drawRect(x-11,y-14,21,27);
        g.drawLine(x-7,y-7,x+6,y+7);
        g.setColor(new Color(95,230,245,90));g.drawOval(x-20,y-23,40,40);
    }

    private void drawGate(Graphics2D g,TemporalGate tg){
        int x=(int)(tg.x-camX),y=(int)tg.y;
        g.setColor(new Color(75,220,245,55));g.fillRect(x,y,26,255);
        g.setColor(new Color(190,250,255));g.drawRect(x,y,26,255);
        for(int yy=y+10;yy<y+250;yy+=18){
            g.setColor(new Color(100,225,245,110));g.drawLine(x+3,yy,x+22,yy+8);
        }
    }

    private void drawEnemy(Graphics2D g,Enemy e){
        int x=(int)(e.x-camX),y=(int)e.y;

        if(e.state==EnemyState.TELEGRAPH){
            int pulse=20+(int)(Math.sin(total*22)*4);
            Color warning=e.type==EnemyType.WISP?new Color(195,110,255,50):new Color(255,115,70,45);
            g.setColor(warning);g.fillOval(x+24-pulse,y+16-pulse,pulse*2,pulse*2);
            g.setColor(e.type==EnemyType.WISP?new Color(225,165,255,215):new Color(255,185,95,210));
            g.drawOval(x+5,y-8,38,38);
            g.setFont(font(Font.BOLD,8));g.drawString("!",x+21,y-12);
        }else if(e.alertFlash>0){
            g.setColor(new Color(255,220,110,170));g.drawOval(x+8,y-5,34,34);
        }

        if(e.type==EnemyType.WISP){
            BufferedImage sh=a.get("wisp_sheet");
            int f=((int)(e.anim*5))%4;
            if(e.dir<0)g.drawImage(sh,x+48,y,x,y+48,f*48,0,f*48+48,48,null);
            else g.drawImage(sh,x,y,x+48,y+48,f*48,0,f*48+48,48,null);
        }else{
            BufferedImage sh=a.get("crab_sheet");
            int f=((int)(e.anim*5))%4;
            if(e.dir<0)g.drawImage(sh,x+48,y,x,y+32,f*48,0,f*48+48,32,null);
            else g.drawImage(sh,x,y,x+48,y+32,f*48,0,f*48+48,32,null);
        }

        if(e.flash>0){
            g.setColor(new Color(255,255,255,(int)(190*Math.min(1,e.flash/.11))));
            if(e.type==EnemyType.WISP)g.fillOval(x+12,y+12,24,24);
            else g.fillOval(x+11,y+10,26,18);
        }

        if(e.hp>1){
            g.setColor(new Color(0,0,0,150));g.fillRect(x+7,y-7,34,4);
            g.setColor(e.type==EnemyType.WISP?new Color(174,92,225):new Color(225,78,60));
            g.fillRect(x+8,y-6,(int)(32*(e.hp/(double)e.maxHp)),2);
        }
    }

    private void drawCharacters(Graphics2D g){
        if(elena.visible)drawChar(g,"elena_sheet",elena.x,elena.y,elena.dir,elena.anim,false,false);
        if(level==1&&mira.visible)drawChar(g,"mira_sheet",mira.x,mira.y,1,total,false,false);
        if(level==2&&noah.visible)drawChar(g,"noah_sheet",noah.x,noah.y,1,total,false,false);
        if(level==3&&kael.active)drawBoss(g);

        if(elena.visible && elena.shotFxTimer>0){
            int x1=(int)(elena.x-camX+48), y1=(int)(elena.y+28);
            int x2=(int)(elena.shotTargetX-camX), y2=(int)elena.shotTargetY;
            Stroke old=g.getStroke();
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(245,213,90,210));g.drawLine(x1,y1,x2,y2);
            g.setColor(new Color(255,248,190,80));g.drawLine(x1,y1-2,x2,y2-2);
            g.setStroke(old);
        }

        if(p.fracture>0){
            Composite old=g.getComposite();
            for(int i=4;i>=1;i--){
                g.setComposite(AlphaComposite.SrcOver.derive(.055f*i));
                drawChar(g,"alex_sheet",p.x-i*p.vx*.03,p.y,p.dir,p.anim,false,false);
            }
            g.setComposite(old);
        }
        drawPlayer(g);
        drawNovaAt(g,(int)(p.x-camX+22),(int)(p.y-28+Math.sin(total*3)*4));
        if(ai.hintNeed>.62 || elena.leadTimer>0 || directorMode.contains("GUIDANCE") || assistPlatform)
            drawGuidanceBeam(g);
        drawWaypoint(g);
    }

    private void drawPlayer(Graphics2D g){
        boolean hurt=p.hurt>0;
        boolean attacking=p.attack>0;
        boolean dodging=p.dodge>0;
        int frame;

        if(hurt)frame=14+((int)(total*12)%2);
        else if(dodging)frame=8;
        else if(attacking){
            double dur=Math.max(.001,p.attackDuration);
            double progress=1-Math.max(0,p.attack)/dur;
            frame=10+Math.min(3,(int)(progress*4));
        }else if(!p.onGround)frame=8+((int)(total*6)%2);
        else if(Math.abs(p.vx)>10)frame=4+((int)(p.anim*1.5)%4);
        else frame=((int)(p.anim*1.4)%4);

        if(dodging){
            Composite old=g.getComposite();
            for(int i=3;i>=1;i--){
                g.setComposite(AlphaComposite.SrcOver.derive(.10f*i));
                drawSheetFrameWorld(g,"alex_sheet",8,p.x-i*p.dir*18,p.y+4,p.dir);
            }
            g.setComposite(old);
        }

        drawSheetFrameWorld(g,"alex_sheet",frame,p.x,p.y,p.dir);

        if(attacking&&attackActive()){
            Rectangle2D ab=attackBox();
            Rectangle abr=ab.getBounds();
            g.setColor(new Color(255,225,120,p.comboStep==3?90:55));
            g.fillRect(abr.x-(int)camX,abr.y,abr.width,abr.height);

            int cx=(int)(p.x-camX+PW/2),cy=(int)(p.y+PH/2);
            int reach=p.comboStep==3?55:45;
            g.setColor(new Color(255,242,180,160));
            int start=p.dir>0?-55:145;
            g.drawArc(cx-reach/2,cy-reach/2,reach,reach,start,p.dir>0?80:-80);
        }
    }

    private void drawChar(Graphics2D g,String sheet,double wx,double wy,int dir,double anim,boolean attacking,boolean hurt){
        int frame=4+((int)(Math.abs(anim)*1.4)%4);
        drawSheetFrameWorld(g,sheet,frame,wx,wy,dir);
    }

    private void drawSheetFrameWorld(Graphics2D g,String key,int frame,double wx,double wy,int dir){
        BufferedImage sh=a.get(key);
        int sx=frame*SPRITE_FW;
        int x=(int)Math.round(wx-camX+PW/2.0-SPRITE_DRAW_W/2.0);
        int y=(int)Math.round(wy+PH-SPRITE_DRAW_H);
        if(dir<0)g.drawImage(sh,x+SPRITE_DRAW_W,y,x,y+SPRITE_DRAW_H,sx,0,sx+SPRITE_FW,SPRITE_FH,null);
        else g.drawImage(sh,x,y,x+SPRITE_DRAW_W,y+SPRITE_DRAW_H,sx,0,sx+SPRITE_FW,SPRITE_FH,null);
    }

    private void drawSheetFrame(Graphics2D g,String key,int frame,int x,int y,int fw,int fh,int scale){
        BufferedImage sh=a.get(key);
        g.drawImage(sh,x,y,x+fw*scale,y+fh*scale,frame*fw,0,frame*fw+fw,fh,null);
    }

    private void drawBoss(Graphics2D g){
        int frame;
        if(kael.hp<=0)frame=14;
        else if(kael.telegraph>0)frame=10+((int)(total*9)%4);
        else if(bossStarted)frame=4+((int)(total*6)%4);
        else frame=((int)(total*2)%4);

        int bx=(int)(kael.x-camX);
        if(kael.telegraph>0){
            int r=42+(int)(Math.sin(total*18)*6);
            g.setColor(new Color(195,95,255,45));g.fillOval(bx+32-r,(int)kael.y+28-r,r*2,r*2);
            g.setColor(new Color(235,170,255,210));g.drawOval(bx-7,(int)kael.y-12,78,78);
            g.setFont(font(Font.BOLD,9));
            g.drawString(kael.enraged?"VOID SURGE":"VOID READ",bx-1,(int)kael.y-18);
        }

        drawSheetFrameWorld(g,"kael_sheet",frame,kael.x,kael.y,-1);

        if(kael.flash>0){
            g.setColor(new Color(255,255,255,(int)(175*Math.min(1,kael.flash/.12))));
            g.fillOval(bx+13,(int)kael.y+9,40,43);
        }

        if(bossStarted&&kael.hp>0){
            g.setColor(new Color(145,80,205,30));g.fillOval(bx-20,(int)kael.y-16,100,90);
            if(kael.enraged){
                g.setColor(new Color(215,80,125,34));g.fillOval(bx-28,(int)kael.y-24,116,106);
            }
        }
    }

    private void drawNovaAt(Graphics2D g,int x,int y){
        BufferedImage sh=a.get("nova_sheet");
        int f=((int)(total*8))%6;
        int r=20+(int)(Math.sin(total*4)*2);
        g.setColor(new Color(85,225,240,24));g.fillOval(x+16-r,y+16-r,r*2,r*2);
        g.setColor(new Color(130,245,255,35));g.fillOval(x+3,y+3,26,26);
        g.drawImage(sh,x,y,x+32,y+32,f*32,0,f*32+32,32,null);
    }

    private void drawGuidanceBeam(Graphics2D g){
        Double tx=targetX(); if(tx==null)return;
        int x1=(int)(p.x-camX+38);
        int y1=(int)(p.y-12);
        int x2=(int)clamp(tx-camX,25,IW-25);
        int y2=115;

        Stroke old=g.getStroke();
        g.setStroke(new BasicStroke(1f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,0,new float[]{4f,6f},(float)(total*18)));
        g.setColor(new Color(95,230,245,125));
        g.drawLine(x1,y1,x2,y2);
        g.setStroke(old);
    }

    private void drawWaypoint(Graphics2D g){
        Double tx=targetX();if(tx==null)return;
        double raw=tx-camX;
        double sx=clamp(raw,24,IW-24);
        int y=83+(int)(Math.sin(total*5)*4);

        g.setColor(new Color(80,235,255,55));g.fillOval((int)sx-18,y-18,36,36);
        Polygon tri=new Polygon(new int[]{(int)sx,(int)sx-7,(int)sx+7},new int[]{y,y-12,y-12},3);
        g.setColor(new Color(105,240,255));g.fill(tri);

        if(raw<0||raw>IW){
            g.setFont(font(Font.BOLD,9));g.setColor(Color.WHITE);
            g.drawString(raw<0?"←":"→",(int)sx-3,y+28);
        }
    }

    private void drawProjectiles(Graphics2D g){
        for(Projectile q:projectiles){
            int x=(int)(q.x-camX),y=(int)q.y;
            int outer=q.heavy?18:12;
            int inner=q.heavy?7:5;
            g.setColor(new Color(150,80,220,q.heavy?58:45));g.fillOval(x-outer,y-outer,outer*2,outer*2);
            g.setColor(q.heavy?new Color(235,155,255):new Color(205,145,255));g.fillOval(x-inner,y-inner,inner*2,inner*2);
            if(q.heavy){
                g.setColor(new Color(250,215,255,120));g.drawOval(x-12,y-12,24,24);
            }
        }
    }

    private void drawParticles(Graphics2D g){
        Composite old=g.getComposite();
        for(Particle q:particles){
            g.setComposite(AlphaComposite.SrcOver.derive((float)Math.min(1,q.life)));
            g.setColor(q.color);g.fillRect((int)(q.x-camX),(int)q.y,(int)q.size,(int)q.size);
        }
        g.setComposite(old);
    }

    private void updateFloatingTexts(double dt){
        for(int i=floatingTexts.size()-1;i>=0;i--){
            FloatingText f=floatingTexts.get(i);
            f.life-=dt;
            f.y-=22*dt;
            if(f.life<=0)floatingTexts.remove(i);
        }
    }

    private void drawFloatingTexts(Graphics2D g){
        Composite old=g.getComposite();
        g.setFont(font(Font.BOLD,10));
        for(FloatingText f:floatingTexts){
            float alpha=(float)Math.min(1,f.life/.25);
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(.05f,alpha)));
            g.setColor(f.color);
            g.drawString(f.text,(int)(f.x-camX),(int)f.y);
        }
        g.setComposite(old);
    }

    private void drawAtmosphere(Graphics2D g){
        if(level==0){
            // Jungle leaf motes and broken sun shafts.
            for(int i=0;i<22;i++){
                int x=(int)((i*83 + total*(12+i%4)*7 - camX*.16)% (IW+80))-40;
                int y=105+(i*47)%330+(int)(Math.sin(total*1.4+i)*9);
                g.setColor(new Color(105,175,80,45+(i%3)*15));
                g.fillRect(x,y,2+(i%2),1+(i%2));
            }
            g.setColor(new Color(255,236,170,13));
            g.fillPolygon(new int[]{80,230,360,155},new int[]{0,0,430,430},4);
        }else if(level==1){
            for(int i=0;i<18;i++){
                int x=(int)((i*101-total*15-camX*.08)%(IW+100))-50;
                int y=165+(i*61)%290;
                g.setColor(new Color(210,248,245,28));
                g.fillRect(x,y,18+(i%4)*7,2);
            }
            g.setColor(new Color(190,245,245,10));g.fillRect(0,315,IW,150);
        }else if(level==2){
            for(int i=0;i<24;i++){
                int x=(int)((i*73-total*(7+i%5)*5-camX*.10)%(IW+70))-35;
                int y=120+(i*53)%330;
                g.setColor(new Color(235,185,115,38));
                g.fillRect(x,y,2,2);
            }
            g.setColor(new Color(255,160,70,10));g.fillRect(0,0,IW,IH);
        }else{
            for(int i=0;i<28;i++){
                int x=(int)((i*61+total*(9+i%4)*4-camX*.11)%(IW+50))-25;
                int y=85+(i*43)%360+(int)(Math.sin(total*2+i)*6);
                g.setColor(new Color(173,94,235,38+(i%3)*12));
                g.fillRect(x,y,2,2);
            }
            g.setColor(new Color(85,45,120,14));g.fillRect(0,0,IW,IH);
        }
    }

    private void drawPause(Graphics2D g){
        g.setColor(new Color(0,0,0,205));g.fillRect(0,0,IW,IH);
        g.setFont(font(Font.BOLD,31));center(g,"PAUSED",185,Color.WHITE);
        g.setFont(font(Font.BOLD,13));center(g,"ESC — RESUME",235,new Color(255,222,125));
        g.setFont(font(Font.PLAIN,11));center(g,"R — restart checkpoint   •   F11 — fullscreen   •   N — NOVA after resume",270,new Color(215,225,220));
        g.setFont(font(Font.PLAIN,10));center(g,"Movement: A/D  Jump: SPACE/W  Combo: J/UP  Dodge: CTRL/K  Fracture: Q  Pulse: F",305,new Color(175,205,200));
        g.setFont(font(Font.PLAIN,9));center(g,"NOVA learning is saved locally. ESC again resumes immediately.",344,new Color(145,175,170));
    }

    private void drawEffects(Graphics2D g){
        if(p.fracture>0){
            g.setColor(new Color(45,165,255,22));g.fillRect(0,0,IW,IH);
            g.setColor(new Color(115,235,255,70));
            for(int i=0;i<4;i++){
                int r=38+i*20+(int)(Math.sin(total*6+i)*3);
                g.drawOval((int)(p.x-camX+PW/2-r),(int)(p.y+PH/2-r),r*2,r*2);
            }
        }

        if(p.dodge>0){
            g.setColor(new Color(120,235,245,20));g.fillRect(0,0,IW,IH);
        }

        if(p.hp<30){
            int a0=(int)(18+16*(.5+.5*Math.sin(total*5.5)));
            g.setColor(new Color(165,20,20,a0));
            g.fillRect(0,0,IW,10);g.fillRect(0,IH-10,IW,10);
            g.fillRect(0,0,10,IH);g.fillRect(IW-10,0,10,IH);
        }

        if(p.hurt>0){
            g.setColor(new Color(255,255,255,(int)(34*Math.min(1,p.hurt/.28))));
            g.fillRect(0,0,IW,IH);
        }
    }

    private void drawHUD(Graphics2D g){
        // objective
        g.setColor(new Color(0,0,0,180));g.fillRoundRect(8,8,500,64,8,8);
        g.setColor(new Color(255,211,96));g.fillRect(8,8,4,64);
        g.setFont(font(Font.BOLD,10));g.setColor(new Color(255,220,120));g.drawString(chapter,20,26);
        g.setFont(font(Font.BOLD,14));g.setColor(Color.WHITE);g.drawString(objective,20,49);

        // stats
        g.setColor(new Color(0,0,0,180));g.fillRoundRect(758,8,258,72,8,8);
        g.setFont(font(Font.PLAIN,9));g.setColor(Color.WHITE);g.drawString("HP",771,23);
        g.setColor(new Color(60,20,20));g.fillRect(800,16,195,8);
        g.setColor(new Color(222,70,65));g.fillRect(800,16,(int)(195*Math.max(0,p.hp)/100.0),8);

        g.drawString("ZERO",771,42);
        g.setColor(new Color(15,50,60));g.fillRect(800,35,195,8);
        g.setColor(new Color(70,214,235));g.fillRect(800,35,(int)(195*p.energy/100.0),8);

        g.setColor(new Color(255,215,100));g.drawString("COINS "+coins+"   RELICS "+treasures,771,60);
        g.setColor(new Color(130,225,235));g.drawString("NOVA "+ai.style()+"  [TAB]",771,73);

        // NOVA diagnostics are intentionally hidden during normal play.
        // The player should experience adaptation through behavior, not developer telemetry.

        if(p.comboStep>1 && (p.attack>0||p.comboWindow>0)){
            g.setFont(font(Font.BOLD,10));
            g.setColor(new Color(255,218,100));
            g.drawString("COMBO x"+p.comboStep,690,98);
        }
        if(p.dodge>0){
            g.setFont(font(Font.BOLD,9));
            g.setColor(new Color(160,240,245));
            g.drawString("DODGE",690,113);
        }

        if(level==3&&bossStarted&&kael.hp>0){
            g.setColor(new Color(0,0,0,185));g.fillRoundRect(315,84,395,30,8,8);
            g.setColor(new Color(140,75,195));g.fillRect(365,96,(int)(325*(kael.hp/(double)kael.maxHp)),7);
            g.setFont(font(Font.BOLD,10));g.setColor(Color.WHITE);g.drawString("KAEL",327,103);
        }

        // level progress
        for(int i=0;i<4;i++){
            g.setColor(i==level?new Color(255,215,100):i<level?new Color(105,200,135):new Color(75,88,86));
            g.fillRect(18+i*30,84,18,4);
        }

        String prompt=contextPrompt();
        if(prompt!=null){
            g.setFont(font(Font.BOLD,12));int tw=g.getFontMetrics().stringWidth(prompt);
            int bx=(IW-tw)/2-14;
            g.setColor(new Color(0,0,0,215));g.fillRoundRect(bx,IH-58,tw+28,31,7,7);
            g.setColor(new Color(255,220,120));g.drawRoundRect(bx,IH-58,tw+28,31,7,7);
            g.setColor(Color.WHITE);g.drawString(prompt,bx+14,IH-37);
        }

        if(hintTimer>0){
            g.setColor(new Color(0,0,0,200));g.fillRoundRect(150,120,724,38,8,8);
            g.setFont(font(Font.BOLD,11));g.setColor(new Color(105,235,245));g.drawString(hint,166,144);
        }

        if(bannerTimer>0){
            g.setFont(font(Font.BOLD,18));int tw=g.getFontMetrics().stringWidth(banner);
            g.setColor(new Color(0,0,0,195));g.fillRoundRect((IW-tw)/2-16,170,tw+32,40,8,8);
            g.setColor(new Color(255,228,145));g.drawString(banner,(IW-tw)/2,196);
        }

        g.setColor(new Color(0,0,0,176));g.fillRect(0,IH-30,IW,30);
        drawControlChip(g,10,IH-25,"A/D","MOVE");
        drawControlChip(g,126,IH-25,"SPACE","JUMP");
        drawControlChip(g,270,IH-25,"J","COMBO");
        drawControlChip(g,386,IH-25,"K","DODGE");
        if(timeUnlocked)drawControlChip(g,502,IH-25,"Q","FRACTURE");
        if(pulseUnlocked)drawControlChip(g,654,IH-25,"F","PULSE");
        drawControlChip(g,770,IH-25,"N","NOVA");
        g.setFont(font(Font.PLAIN,8));g.setColor(new Color(170,190,185));g.drawString("ESC PAUSE",936,IH-10);
    }

    private String contextPrompt(){
        if(state!=State.PLAY)return null;

        if(level==0){
            if(objectiveStep==0&&near(elena.x,82))return "[E] TALK TO ELENA";
            if(objectiveStep==1&&near(930,85))return "[E] OPEN TREASURE CHEST";
        }else if(level==1){
            if(objectiveStep==0&&near(mira.x,82))return "[E] TALK TO MIRA";
            if(objectiveStep==1){
                for(Pickup pu:pickups)if(!pu.taken&&pu.type==PickType.MAP&&near(pu.x,78))return "[E] TAKE MAP FRAGMENT";
            }
        }else if(level==2){
            if(objectiveStep==0&&near(noah.x,82))return "[E] TALK TO NOAH";
        }else{
            if(!bossStarted&&kael.hp>0&&near(kael.x,180))return "MOVE CLOSER — KAEL IS WAITING";
            if(bossStarted&&kael.hp>0&&Math.abs(kael.x-p.x)<220)return "[J] MELEE   [F] ZERO PULSE";
            if(kael.hp<=0&&near(kael.x,120))return "[E] CONFRONT KAEL";
        }
        return null;
    }

    private void drawControlChip(Graphics2D g,int x,int y,String key,String label){
        int w=key.length()>2?132:108;
        g.setColor(new Color(20,31,30,225));g.fillRoundRect(x,y,w,20,6,6);
        g.setColor(new Color(255,219,112));g.drawRoundRect(x,y,w,20,6,6);
        g.setFont(font(Font.BOLD,8));g.setColor(new Color(255,224,125));g.drawString(key,x+7,y+13);
        int ox=x+(key.length()>2?46:28);
        g.setColor(new Color(226,235,230));g.drawString(label,ox,y+13);
    }

    private void drawDialogue(Graphics2D g){
        int x=90,y=330,w=844,h=148;
        g.setColor(new Color(0,0,0,230));g.fillRoundRect(x,y,w,h,9,9);
        g.setColor(new Color(255,215,100));g.drawRoundRect(x,y,w,h,9,9);

        BufferedImage portrait=a.get(portraitKey);
        if(portrait!=null)g.drawImage(portrait,x+10,y+10,112,112,null);

        g.setFont(font(Font.BOLD,13));g.setColor(new Color(255,217,105));g.drawString(speaker,x+140,y+32);
        g.setFont(font(Font.PLAIN,14));g.setColor(Color.WHITE);
        String visible=dialogue.substring(0,Math.min(revealChars,dialogue.length()));
        drawWrapped(g,visible,x+140,y+58,w-166,20);

        if(revealChars>=dialogue.length()){
            g.setFont(font(Font.PLAIN,9));g.setColor(new Color(180,185,175));
            g.drawString("E / SPACE — CONTINUE",x+w-165,y+h-14);
        }
    }

    private void drawChat(Graphics2D g){
        g.setColor(new Color(0,0,0,235));g.fillRoundRect(80,86,864,315,10,10);
        g.setColor(new Color(95,230,240));g.drawRoundRect(80,86,864,315,10,10);
        g.drawImage(a.get("nova_portrait"),100,105,96,96,null);

        g.setFont(font(Font.BOLD,16));g.setColor(new Color(105,235,245));g.drawString("NOVA // RESPONSIVE AI",215,122);
        g.setFont(font(Font.PLAIN,10));g.setColor(new Color(205,220,215));
        g.drawString("Ask: where do I go? / attack / Elena / Mira / Noah / Kael / relic / powers / how do you learn?",215,145);

        g.setColor(new Color(18,28,28));g.fillRoundRect(215,164,700,42,7,7);
        g.setFont(font(Font.PLAIN,13));g.setColor(Color.WHITE);g.drawString("> "+chatInput,228,190);

        g.setColor(new Color(14,24,24));g.fillRoundRect(100,222,815,118,7,7);
        g.setFont(font(Font.BOLD,11));g.setColor(new Color(255,214,100));g.drawString("NOVA",118,244);
        g.setFont(font(Font.PLAIN,12));g.setColor(Color.WHITE);drawWrapped(g,chatReply,118,268,775,18);

        g.setFont(font(Font.PLAIN,9));g.setColor(new Color(165,185,180));
        g.drawString("ENTER SEND • BACKSPACE EDIT • N CLOSE",100,378);
    }

    private void drawAIPanel(Graphics2D g){
        int x=635,y=90,w=370,h=320;
        g.setColor(new Color(0,0,0,230));g.fillRoundRect(x,y,w,h,10,10);
        g.setColor(new Color(95,230,240));g.drawRoundRect(x,y,w,h,10,10);
        g.setFont(font(Font.BOLD,13));g.setColor(new Color(105,235,245));g.drawString("NOVA // LEARNED PLAYER MODEL",x+18,y+27);

        String[] rows={
                "Style: "+ai.style(),
                String.format(Locale.ROOT,"Confidence: %.0f%%",ai.confidence*100),
                String.format(Locale.ROOT,"Combat skill: %.0f%%",ai.combatSkill*100),
                String.format(Locale.ROOT,"Exploration: %.0f%%",ai.exploration*100),
                String.format(Locale.ROOT,"Treasure drive: %.0f%%",ai.treasureDrive*100),
                String.format(Locale.ROOT,"Power affinity: %.0f%%",ai.powerAffinity*100),
                String.format(Locale.ROOT,"Hint need: %.0f%%",ai.hintNeed*100),
                String.format(Locale.ROOT,"Difficulty: %.2fx",ai.difficultyScale()),
                String.format(Locale.ROOT,"Jump assist: %.2fx  Fails: %d",ai.jumpAssistScale(),ai.jumpFails),
                "Director: "+directorMode,
                "FPS: "+fps+"   Hints: "+ai.hintsShown+"   Objectives: "+ai.objectives
        };

        g.setFont(font(Font.PLAIN,11));g.setColor(Color.WHITE);
        int yy=y+52;
        for(String row:rows){g.drawString(row,x+20,yy);yy+=21;}
        g.setColor(new Color(170,190,185));g.drawString("Saved locally between sessions.",x+20,y+h-18);
    }

    private void drawEnd(Graphics2D g){
        g.setColor(new Color(0,0,0,225));g.fillRect(0,0,IW,IH);
        g.setFont(font(Font.BOLD,33));center(g,"ECLIPSE TEMPLE CLEARED",190,new Color(255,220,105));
        g.setFont(font(Font.PLAIN,15));center(g,"Kael knows why NOVA remembers Alex.",232,Color.WHITE);
        g.setFont(font(Font.BOLD,12));center(g,ai.profileLine(),274,new Color(105,235,245));
        g.setFont(font(Font.PLAIN,11));center(g,"Coins "+coins+"  •  Relics "+treasures+"  •  NOVA profile saved",307,new Color(205,215,205));
        g.setFont(font(Font.BOLD,15));center(g,"PRESS ENTER TO REPLAY",365,Color.WHITE);
    }

    private void center(Graphics2D g,String s,int y,Color c){g.setColor(c);g.drawString(s,(IW-g.getFontMetrics().stringWidth(s))/2,y);}
    private Font font(int style,int size){return new Font("Monospaced",style,size);}

    private void drawWrapped(Graphics2D g,String text,int x,int y,int width,int lh){
        String line="";
        for(String word:text.split(" ")){
            String test=line.isEmpty()?word:line+" "+word;
            if(g.getFontMetrics().stringWidth(test)>width){
                if(!line.isEmpty())g.drawString(line,x,y);
                y+=lh;line=word;
            }else line=test;
        }
        if(!line.isEmpty())g.drawString(line,x,y);
    }

    private boolean key(int code){return code>=0&&code<keys.length&&keys[code];}
    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}

    @Override public void keyPressed(KeyEvent e){
        int c=e.getKeyCode();if(c<keys.length)keys[c]=true;
        if(c==KeyEvent.VK_E)pressE=true;
        if(c==KeyEvent.VK_Q)pressQ=true;
        if(c==KeyEvent.VK_F)pressF=true;
        if(c==KeyEvent.VK_J||c==KeyEvent.VK_UP)pressAttack=true;
        if(c==KeyEvent.VK_K||c==KeyEvent.VK_CONTROL)pressDodge=true;
        if(c==KeyEvent.VK_SPACE||c==KeyEvent.VK_W)pressSpace=true;
        if(c==KeyEvent.VK_ENTER)pressEnter=true;
        if(c==KeyEvent.VK_TAB)pressTab=true;

        if(c==KeyEvent.VK_N){
            if(state==State.PLAY){state=State.CHAT;chatInput.setLength(0);suppressNextTyped=true;}
            else if(state==State.CHAT){state=State.PLAY;suppressNextTyped=true;}
        }

        if(c==KeyEvent.VK_BACK_SPACE&&state==State.CHAT&&chatInput.length()>0)
            chatInput.deleteCharAt(chatInput.length()-1);

        if(c==KeyEvent.VK_F11){
            toggleFullscreen();
            return;
        }

        if(c==KeyEvent.VK_R && state==State.PAUSE){
            p.hp=100;
            resetPlayer();
            state=State.PLAY;
            showBanner("CHECKPOINT RESTART",1.0);
            return;
        }

        if(c==KeyEvent.VK_ESCAPE){
            if(state==State.CHAT)state=State.PLAY;
            else if(state==State.PLAY)state=State.PAUSE;
            else if(state==State.PAUSE)state=State.PLAY;
            else if(state==State.MENU){ai.save();stop();}
        }
    }

    @Override public void keyReleased(KeyEvent e){
        int c=e.getKeyCode();if(c<keys.length)keys[c]=false;
    }

    @Override public void keyTyped(KeyEvent e){
        if(state!=State.CHAT)return;
        char ch=e.getKeyChar();
        if(suppressNextTyped){suppressNextTyped=false;return;}
        if(ch>=32&&ch<127&&chatInput.length()<110)chatInput.append(ch);
    }

    // ---------- data ----------
    record Line(String who,String text,String portrait,String blip){}

    static final class Player {
        double x=64,y=80,vx=0,vy=0,anim=0,energy=100,fracture=0,attack=0,pulseCd=0,inv=0,hurt=0;
        double coyote=0,jumpBuffer=0,jumpHold=0;
        double attackDuration=0,comboWindow=0,dodge=0,dodgeCooldown=0;
        int comboStep=0;
        int dir=1,hp=100;
        boolean onGround=false,attackHit=false,comboQueued=false;
        Rectangle2D rect(){return new Rectangle2D.Double(x+6,y+3,PW-12,PH-4);}
    }

    static final class Companion {
        final CompanionBrain brain=new CompanionBrain();
        final CharacterMotor motor=new CharacterMotor();
        double x=650,y=398,anim=0,supportCooldown=0,attackCooldown=0,leadTimer=0;
        double shotFxTimer=0,shotTargetX=0,shotTargetY=0;
        int dir=-1;
        boolean visible=false,following=false;
    }

    static final class NPC {
        final String name;double x=0,y=398;boolean visible=false;
        NPC(String n){name=n;}
    }

    static final class Enemy {
        final EnemyBrain brain=new EnemyBrain();
        final CharacterMotor motor=new CharacterMotor();
        double x,y,baseY,left,right,anim=0,attackCd=0,stateTimer=0,knockVx=0,flash=0,alertFlash=0,vx=0,phase=0;
        int dir=1,hp,maxHp;
        boolean dead=false;
        final EnemyType type;
        EnemyState state=EnemyState.PATROL;

        Enemy(double x,double y,int hp){this(x,y,hp,EnemyType.CRAB);}

        Enemy(double x,double y,int hp,EnemyType type){
            this.x=x;this.y=this.baseY=y;this.hp=this.maxHp=hp;this.type=type;
            left=x-(type==EnemyType.WISP?155:120);
            right=x+(type==EnemyType.WISP?155:120);
            phase=x*.013;
        }

        Rectangle2D rect(){
            if(type==EnemyType.WISP)return new Rectangle2D.Double(x+6,y+6,36,36);
            return new Rectangle2D.Double(x+5,y+4,38,26);
        }
    }

    static final class Boss {
        double x=2050,y=392,timer=1.3,anim=0,telegraph=0,flash=0;
        int hp=100,maxHp=100,phase=0;
        boolean active=false,enraged=false;
        Rectangle2D rect(){return new Rectangle2D.Double(x+8,y+4,48,50);}
    }

    static final class Projectile {
        double x,y,vx,vy,life;
        final boolean heavy;
        Projectile(double x,double y,double vx,double vy,double life){this(x,y,vx,vy,life,false);}
        Projectile(double x,double y,double vx,double vy,double life,boolean heavy){
            this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.life=life;this.heavy=heavy;
        }
        Rectangle2D rect(){
            double r=heavy?8:6;
            return new Rectangle2D.Double(x-r,y-r,r*2,r*2);
        }
    }

    static final class Pickup {
        final double x,y;final PickType type;boolean taken=false;
        Pickup(double x,double y,PickType t){this.x=x;this.y=y;type=t;}
    }

    static final class TemporalGate {
        final double x,phase;double y;
        TemporalGate(double x,double p){this.x=x;phase=p;}
    }

    static final class FloatingText {
        final String text;
        final double x;
        double y,life=0.72;
        final Color color;
        FloatingText(String text,double x,double y,Color color){
            this.text=text;this.x=x;this.y=y;this.color=color;
        }
    }

    static final class Particle {
        double x,y,vx,vy,life,size;Color color;
        Particle(double x,double y,double vx,double vy,double life,double size,Color c){
            this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.life=life;this.size=size;color=c;
        }
    }
}
