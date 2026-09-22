package com.zeroeclipse.v9ai;
public final class Perception {
    public boolean canSeePlayer;
    public boolean heardNoise;
    public double distance;
    public double lastKnownX;
    public void update(double npcX,double playerX,double range){
        distance=Math.abs(playerX-npcX);
        canSeePlayer=distance<=range;
    }
}
