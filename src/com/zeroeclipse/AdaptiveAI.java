
package com.zeroeclipse;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

final class AdaptiveAI {
    enum Style { CURIOUS, CAUTIOUS, TACTICAL, BOLD }

    private final Path savePath = Paths.get(System.getProperty("user.home"), ".zero_eclipse_ai.properties");

    double exploration = .5;
    double combatSkill = .5;
    double treasureDrive = .5;
    double confidence = .5;
    double powerAffinity = .5;
    double hintNeed = .5;
    double persistence = .5;

    int moves=0, jumps=0, powers=0, hintsShown=0, damageEvents=0, objectives=0, retries=0, sessions=0, jumpFails=0;
    double roomTime=0;
    double stillTime=0;
    double wrongWayTime=0;
    double lastX=0;

    AdaptiveAI() { load(); }

    void beginRoom(double playerX) {
        roomTime=0;stillTime=0;wrongWayTime=0;lastX=playerX;
    }

    void observeFrame(double dt, double x, double vx, double targetX) {
        roomTime += dt;
        if (Math.abs(vx) < 8) stillTime += dt; else { stillTime = Math.max(0, stillTime-dt*1.5); moves++; }
        if (targetX > x+80 && vx < -15) wrongWayTime += dt;
        else if (targetX < x-80 && vx > 15) wrongWayTime += dt;
        else wrongWayTime = Math.max(0, wrongWayTime-dt*2);
        lastX=x;
    }

    void observeJump(){ jumps++; exploration = ema(exploration, .72, .10); }

    void observeJumpFailure(){
        jumpFails++;
        hintNeed=ema(hintNeed,.86,.12);
        confidence=ema(confidence,.34,.08);
        save();
    }

    double jumpAssistScale(){
        // NOVA gives only a subtle physical assist; it never takes control away.
        return clamp(1.0 + Math.max(0, hintNeed-.48)*.22 + Math.min(.06, jumpFails*.012), 1.0, 1.14);
    }

    boolean wantsTraversalAssist(){
        return jumpFails>=2 && hintNeed>.54;
    }
    void observePower(){ powers++; powerAffinity = ema(powerAffinity, .84, .12); combatSkill=ema(combatSkill,.68,.06); confidence=ema(confidence,.66,.05); }
    void observeDamage(){ damageEvents++; combatSkill=ema(combatSkill,.32,.10); confidence=ema(confidence,.28,.12); hintNeed=ema(hintNeed,.78,.10); }
    void observeRetry(){ retries++; persistence=ema(persistence,.78,.10); combatSkill=ema(combatSkill,.42,.06); confidence=ema(confidence,.36,.07); }

    void startSession(){ sessions++; save(); }


    void observeAttack(boolean hit){
        combatSkill=ema(combatSkill, hit ? .82 : .48, hit ? .10 : .025);
        confidence=ema(confidence, hit ? .72 : .50, .035);
        save();
    }

    void observeTreasure(){
        treasureDrive=ema(treasureDrive,.90,.12);
        exploration=ema(exploration,.78,.06);
        save();
    }


    void objectiveComplete(double seconds) {
        objectives++;
        double speed = seconds < 18 ? .85 : seconds < 35 ? .62 : .35;
        confidence = ema(confidence, speed, .15);
        hintNeed = ema(hintNeed, 1.0-speed, .12);
        exploration = ema(exploration, jumps>objectives ? .72:.45, .05);
        save();
    }

    boolean shouldHint() {
        double base = 8.5 - hintNeed*4.2;
        if (stillTime > base) return true;
        if (wrongWayTime > Math.max(3.0, base*.55)) return true;
        return false;
    }

    void hintShown() {
        hintsShown++;
        stillTime=0;
        wrongWayTime=0;
        hintNeed=ema(hintNeed,.68,.04);
        save();
    }

    double difficultyScale() {
        double skill = .38*confidence + .25*combatSkill + .18*powerAffinity + .19*(1-hintNeed);
        return clamp(.82 + skill*.40, .82, 1.22);
    }

    double companionLeadDistance() {
        // More help for players who need hints, less hand-holding for confident players.
        return 90 + hintNeed*95;
    }

    double energyRegenScale() {
        return clamp(1.22 - confidence*.25 + hintNeed*.18, .92, 1.35);
    }

    Style style() {
        if (hintNeed > .64) return Style.CAUTIOUS;
        if (powerAffinity > .66) return Style.TACTICAL;
        if (exploration > .63) return Style.CURIOUS;
        return Style.BOLD;
    }

    String profileLine() {
        return switch(style()) {
            case CURIOUS -> "NOVA profile: CURIOUS — favors exploration and relic discovery.";
            case CAUTIOUS -> "NOVA profile: CAUTIOUS — gives earlier hints and safer pacing.";
            case TACTICAL -> "NOVA profile: TACTICAL — expects heavier power use.";
            case BOLD -> "NOVA profile: BOLD — reduces hand-holding and raises challenge slightly.";
        };
    }

    String contextualHint(String objectiveName) {
        String dir = wrongWayTime > 1.5 ? "Turn back. " : "";
        return switch(style()) {
            case CURIOUS -> dir+"NOVA: Scan the environment. The relic path is marked in cyan. "+objectiveName;
            case CAUTIOUS -> dir+"NOVA: I will guide you. Follow the cyan marker. "+objectiveName;
            case TACTICAL -> dir+"NOVA: Objective vector locked. Use your abilities when the route resists. "+objectiveName;
            case BOLD -> dir+"NOVA: You already know the route. Follow the marker. "+objectiveName;
        };
    }

    String companionReaction() {
        return switch(style()) {
            case CURIOUS -> "ELENA AI: Let's check every chamber. Hidden relics matter.";
            case CAUTIOUS -> "ELENA AI: Stay close. I'll move toward the next objective.";
            case TACTICAL -> "ELENA AI: Save Zero energy for the next hazard.";
            case BOLD -> "ELENA AI: You're moving fast. I'll keep up.";
        };
    }

    private static double ema(double old, double sample, double alpha) {
        return old*(1-alpha)+sample*alpha;
    }

    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}

    void save() {
        Properties p=new Properties();
        p.setProperty("exploration",Double.toString(exploration));
        p.setProperty("confidence",Double.toString(confidence));
        p.setProperty("combatSkill",Double.toString(combatSkill));
        p.setProperty("treasureDrive",Double.toString(treasureDrive));
        p.setProperty("powerAffinity",Double.toString(powerAffinity));
        p.setProperty("hintNeed",Double.toString(hintNeed));
        p.setProperty("persistence",Double.toString(persistence));
        p.setProperty("sessions",Integer.toString(sessions));
        p.setProperty("jumpFails",Integer.toString(jumpFails));
        try(OutputStream out=Files.newOutputStream(savePath)){
            p.store(out,"ZERO: ECLIPSE adaptive NOVA profile");
        }catch(Exception ignored){}
    }

    private void load() {
        if(!Files.exists(savePath))return;
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(savePath)){
            p.load(in);
            exploration=parse(p,"exploration",exploration);
            confidence=parse(p,"confidence",confidence);
            combatSkill=parse(p,"combatSkill",combatSkill);
            treasureDrive=parse(p,"treasureDrive",treasureDrive);
            powerAffinity=parse(p,"powerAffinity",powerAffinity);
            hintNeed=parse(p,"hintNeed",hintNeed);
            persistence=parse(p,"persistence",persistence);
            sessions=(int)parse(p,"sessions",sessions);
            jumpFails=(int)parse(p,"jumpFails",jumpFails);
        }catch(Exception ignored){}
    }

    private static double parse(Properties p,String key,double fallback){
        try{return Double.parseDouble(p.getProperty(key));}catch(Exception e){return fallback;}
    }
}
