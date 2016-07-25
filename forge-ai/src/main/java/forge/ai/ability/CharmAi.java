package forge.ai.ability;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellAbilityAi;
import forge.game.ability.effects.CharmEffect;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.util.Aggregates;
import forge.util.MyRandom;
import forge.util.collect.FCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CharmAi extends SpellAbilityAi {
    @Override
    protected boolean canPlayAI(Player ai, SpellAbility sa) {
        final Random r = MyRandom.getRandom();

        final int num = Integer.parseInt(sa.hasParam("CharmNum") ? sa.getParam("CharmNum") : "1");
        final int min = sa.hasParam("MinCharmNum") ? Integer.parseInt(sa.getParam("MinCharmNum")) : num;
        boolean timingRight = sa.isTrigger(); //is there a reason to play the charm now?

        // Reset the chosen list otherwise it will be locked in forever by earlier calls
        sa.setChosenList(null);
        List<AbilitySub> choices = CharmEffect.makePossibleOptions(sa);
        List<AbilitySub> chosenList;
        
        if (!ai.equals(sa.getActivatingPlayer())) {
            // This branch is for "An Opponent chooses" Charm spells from Alliances
            // Current just choose the first available spell, which seem generally less disastrous for the AI.
            //return choices.subList(0, 1);
            chosenList = choices.subList(1, choices.size());
        } else if ("Triskaidekaphobia".equals(sa.getHostCard().getName())) {
            chosenList = chooseTriskaidekaphobia(choices, ai);
        } else {
            chosenList = min > 1 ? chooseMultipleOptionsAi(choices, ai, min)
                    : chooseOptionsAi(choices, ai, timingRight, num, min, sa.hasParam("CanRepeatModes"), false);
        }

        if (chosenList.isEmpty()) {
            if (timingRight) {
                // Set minimum choices for triggers where chooseMultipleOptionsAi() returns null
                chosenList = chooseOptionsAi(choices, ai, true, num, min, sa.hasParam("CanRepeatModes"), false);
            } else {
                return false;
            }
        }
        sa.setChosenList(chosenList);

        // prevent run-away activations - first time will always return true
        return r.nextFloat() <= Math.pow(.6667, sa.getActivationsThisTurn());
    }

    private List<AbilitySub> chooseOptionsAi(List<AbilitySub> choices, final Player ai, boolean playNow, int num,
            int min, boolean allowRepeat, boolean opponentChoser) {
        List<AbilitySub> chosenList = new ArrayList<AbilitySub>();
        // Make choice(s)
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        for (int i = 0; i < num; i++) {
            AbilitySub thisPick = null;
            for (SpellAbility sub : choices) {
                sub.setActivatingPlayer(ai);
                if (!playNow && AiPlayDecision.WillPlay == aic.canPlaySa(sub)) {
                    thisPick = (AbilitySub) sub;
                    choices.remove(sub);
                    playNow = true;
                    break;
                }
                if ((playNow || i < num - 1) && aic.doTrigger(sub, false)) {
                    thisPick = (AbilitySub) sub;
                    choices.remove(sub);
                    break;
                }
            }
            if (thisPick != null) {
                chosenList.add(thisPick);
            }
        }
        // Set minimum choices for triggers
        if (playNow && chosenList.size() < min) {
            for (int i = 0; i < min; i++) {
                AbilitySub thisPick = null;
                for (SpellAbility sub : choices) {
                    sub.setActivatingPlayer(ai);
                    if (aic.doTrigger(sub, true)) {
                        thisPick = (AbilitySub) sub;
                        choices.remove(sub);
                        break;
                    }
                }
                if (thisPick != null) {
                    chosenList.add(thisPick);
                }
            }
        }
        return chosenList;
    }

    private List<AbilitySub> chooseTriskaidekaphobia(List<AbilitySub> choices, final Player ai) {
        List<AbilitySub> chosenList = new ArrayList<AbilitySub>();
        AbilitySub gain = choices.get(0);
        AbilitySub lose = choices.get(1);
        FCollection<Player> opponents = ai.getOpponents();

        boolean oppTainted = false;
        boolean allyTainted = ai.isCardInPlay("Tainted Remedy");
        final int aiLife = ai.getLife(); 

        //Check if Opponent controls Tainted Remedy
        for (Player p : opponents) {
            if (p.isCardInPlay("Tainted Remedy")) {
                oppTainted = true;
                break;
            }
        }
        // if ai or ally of ai does control Tainted Remedy, prefer gain life instead of lose
        if (!allyTainted) {
            for (Player p : ai.getAllies()) {
                if (p.isCardInPlay("Tainted Remedy")) {
                    allyTainted = true;
                    break;
                }
            }
        }
        
        if (!ai.canLoseLife() || ai.cantLose()) {
            // ai cant lose life, or cant lose the game, don't think about others
            chosenList.add(allyTainted ? gain : lose);
        } else if (oppTainted || ai.getGame().isCardInPlay("Rain of Gore")) {
            // Rain of Gore does negate lifegain, so don't benefit the others
            // same for if a oppoent does control Tainted Remedy
            // but if ai cant gain life, the effects are negated
            chosenList.add(ai.canGainLife() ? lose : gain);
        } else if (ai.getGame().isCardInPlay("Sulfuric Vortex")) {
            // no life gain, but extra life loss.
            if (aiLife >= 17)
                chosenList.add(lose);
            // try to prevent to get to 13 with extra lose
            else if (aiLife < 13 || ((aiLife - 13) % 2) == 1) {
                chosenList.add(gain);
            } else {
                chosenList.add(lose);
            }
        } else if (ai.canGainLife() && aiLife <= 5) {
            // critical Life try to gain more
            chosenList.add(gain);
        } else if(!ai.canGainLife() && aiLife == 14 ) {
            // ai cant gain life, but try to avoid falling to 13
            // but if a oppoent does control Tainted Remedy its irrelevant
            chosenList.add(oppTainted ? lose : gain);
        } else if (allyTainted) {
            // Tainted Remedy negation logic, try gain instead of lose
            // because negation does turn it into lose for opponents
            boolean oppCritical = false;
            // an oppoent is Critical = 14, and can't gain life, try to lose life instead
            // but only if ai doesn't kill itself with that.
            if (aiLife != 14) {
                for (Player p : opponents) {
                    if (p.getLife() == 14 && !p.canGainLife() && p.canLoseLife()) {
                        oppCritical = true;
                        break;
                    }
                }
            }
            chosenList.add(aiLife == 12 || oppCritical ? lose : gain);
        } else {
            // normal logic, try to gain life if its critical
            boolean oppCritical = false;
            // an oppoent is Critical = 12, and can gain life, try to gain life instead
            // but only if ai doesn't kill itself with that.
            if (aiLife != 12) {
                for (Player p : opponents) {
                    if (p.getLife() == 12 && p.canGainLife()) {
                        oppCritical = true;
                        break;
                    }
                }
            }
            chosenList.add(aiLife == 14 || aiLife <= 10 || oppCritical ? gain : lose);
        }
        return chosenList;
    }

    // Choice selection for charms that require multiple choices (eg. Cryptic Command, DTK commands)
    private List<AbilitySub> chooseMultipleOptionsAi(List<AbilitySub> choices, final Player ai, int min) {
        AbilitySub goodChoice = null;
        List<AbilitySub> chosenList = new ArrayList<AbilitySub>();
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        for (AbilitySub sub : choices) {
            sub.setActivatingPlayer(ai);
            // Assign generic good choice to fill up choices if necessary 
            if ("Good".equals(sub.getParam("AILogic")) && aic.doTrigger(sub, false)) {
                goodChoice = sub;
            } else {
                // Standard canPlayAi()
                if (AiPlayDecision.WillPlay == aic.canPlaySa(sub)) {
                    chosenList.add(sub);
                    if (chosenList.size() == min) {
                        break;  // enough choices
                    }
                }
            }
        }
        // Add generic good choice if one more choice is needed
        if (chosenList.size() == min - 1 && goodChoice != null) {
            chosenList.add(0, goodChoice);  // hack to make Dromoka's Command fight targets work
        }
        if (chosenList.size() != min) {
            chosenList.clear();
        }
        return chosenList;
    } 

    @Override
    public Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> opponents) {
        return Aggregates.random(opponents);
    }
}
