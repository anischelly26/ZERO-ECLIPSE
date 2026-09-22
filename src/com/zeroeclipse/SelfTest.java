package com.zeroeclipse;

final class SelfTest {
    private SelfTest() {}

    static String run() {
        String tuning=Tuning.selfTest();
        if(!tuning.startsWith("PASS")) return tuning;

        AdaptiveAI ai=new AdaptiveAI();
        ai.hintNeed=.80;
        ai.jumpFails=3;
        double assist=ai.jumpAssistScale();
        if(assist<=1.0 || assist>1.14) return "FAIL ai jump assist="+assist;

        double prev=ai.difficultyScale();
        ai.combatSkill=.85;
        ai.confidence=.82;
        ai.hintNeed=.25;
        double harder=ai.difficultyScale();
        if(harder<prev*.90) return "FAIL adaptive difficulty";

        double comboTotal=0;
        for(double d:Tuning.Combat.COMBO_DURATION) comboTotal+=d;
        if(comboTotal<.65 || comboTotal>.90) return "FAIL combo timing="+comboTotal;

        return String.format(java.util.Locale.ROOT,
                "PASS | %s | aiJump=%.3f | adaptiveDifficulty=%.3f | comboTotal=%.3f",
                tuning,assist,harder,comboTotal);
    }
}
