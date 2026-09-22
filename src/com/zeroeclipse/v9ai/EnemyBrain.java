package com.zeroeclipse.v9ai;
public final class EnemyBrain {
    public AIState state=AIState.IDLE;
    public final Perception perception=new Perception();
    public void update(double npcX,double playerX,double dt){
        perception.update(npcX,playerX,360);
        if(perception.canSeePlayer){
            state=AIState.COMBAT;
        } else if(perception.heardNoise){
            state=AIState.INVESTIGATE;
        } else {
            state=AIState.IDLE;
        }
    }
}
