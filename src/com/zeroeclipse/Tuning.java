package com.zeroeclipse;

/** Centralized gameplay tuning. Keeps feel/balance data out of rendering code. */
final class Tuning {
    private Tuning() {}

    static final class Movement {
        static final double WALK_SPEED = 168;
        static final double SPRINT_SPEED = 228;
        static final double GROUND_RESPONSE = 13.5;
        static final double AIR_RESPONSE = 7.8;
        static final double JUMP_SPEED = 425;
        static final double COYOTE_TIME = .115;
        static final double JUMP_BUFFER = .125;
        static final double JUMP_HOLD = .50;
        static final double HELD_GRAVITY = 825;
        static final double CUT_GRAVITY = 1460;
        static final double FALL_GRAVITY = 1720;
        static final double MAX_FALL = 760;
    }

    static final class Combat {
        static final double[] COMBO_DURATION = {.215, .235, .30};
        static final int[] ENEMY_DAMAGE = {1, 1, 2};
        static final int[] BOSS_DAMAGE = {6, 7, 10};
        static final double[] HIT_KNOCKBACK = {95, 125, 185};
        static final double ENEMY_ALERT_RANGE = 285;
        static final double ENEMY_ATTACK_RANGE = 58;
        static final double ENEMY_TELEGRAPH = .33;
        static final double DODGE_SPEED = 385;
        static final double DODGE_TIME = .18;
        static final double DODGE_INVULN = .28;
    }

    static final class Director {
        static final double ELENA_COVER_RANGE = 230;
        static final double ELENA_COVER_COOLDOWN = 6.5;
        static final double ASSIST_PLATFORM_LIFETIME = 22;
    }

    static String selfTest() {
        if (Movement.JUMP_SPEED <= 0 || Movement.FALL_GRAVITY <= Movement.HELD_GRAVITY)
            return "FAIL movement tuning";
        if (Combat.COMBO_DURATION.length != 3 || Combat.BOSS_DAMAGE[2] <= Combat.BOSS_DAMAGE[0])
            return "FAIL combat tuning";
        double approxApex = Movement.JUMP_SPEED * Movement.JUMP_SPEED / (2.0 * Movement.HELD_GRAVITY);
        if (approxApex < 100 || approxApex > 120)
            return "FAIL theoretical jump apex=" + approxApex;

        double dt=1.0/60.0, vy=-Movement.JUMP_SPEED, y=0, minY=0, hold=Movement.JUMP_HOLD;
        for(int i=0;i<120;i++){
            hold=Math.max(0,hold-dt);
            double g=vy<0 ? (hold>0?Movement.HELD_GRAVITY:Movement.CUT_GRAVITY) : Movement.FALL_GRAVITY;
            vy=Math.min(Movement.MAX_FALL,vy+g*dt);
            y+=vy*dt;
            minY=Math.min(minY,y);
            if(i>12 && y>=0)break;
        }
        double simulatedApex=-minY;
        if(simulatedApex<102) return "FAIL simulated jump cannot clear 3-tile platform: "+simulatedApex;
        return String.format(java.util.Locale.ROOT, "PASS jumpApex=%.1f simulated=%.1f combo=3", approxApex,simulatedApex);
    }
}
