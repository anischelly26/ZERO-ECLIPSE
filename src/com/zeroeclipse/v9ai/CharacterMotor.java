package com.zeroeclipse.v9ai;
public final class CharacterMotor {
    public double velocity;
    public double move(double current,double target,double accel,double dt){
        double delta=target-current;
        double max=accel*dt;
        if(Math.abs(delta)<=max)return target;
        return current+Math.signum(delta)*max;
    }
}
