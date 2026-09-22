package com.zeroeclipse.v9ai;
public final class CompanionBrain {
    public AIState state=AIState.FOLLOW;
    public double desiredDistance=110;
    public double chooseSpeed(double distance){
        if(distance>260){state=AIState.FOLLOW;return 150;}
        if(distance<70){state=AIState.FORMATION;return 0;}
        state=AIState.FORMATION;return 85;
    }
}
